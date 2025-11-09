package org.example;

import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.consensus.ConsensusMessageTracker;
import org.example.messaging.ClientMessageReceiver;
import org.example.messaging.ClientMessageSender;
import org.example.statemachine.BalanceRequestOp;
import org.example.statemachine.StateMachineOperation;
import org.example.statemachine.TransferOp;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.example.messaging.MessageUtil.requestIdFor;

public class ClientNode extends Node {
    private static final Logger logger = LogManager.getLogger(ClientNode.class);

    private String primaryServerId;

    private final ClientMessageSender sender;
    private final ClientMessageReceiver receiver;
    private final ConsensusMessageTracker<String, MessageServiceOuterClass.OperationResult> messageTracker;

    // Pause support
    private final ReentrantLock pauseLock = new ReentrantLock();
    private final Condition unpaused = pauseLock.newCondition();
    private volatile boolean paused = false;

    // Generation token to cancel in-flight operations on reset
    private final AtomicLong runGeneration = new AtomicLong(0);

    public ClientNode(String nodeId) {
        super(nodeId);
        this.sender = new ClientMessageSender(nodeId, commLogger, auth, executorManager.getNetworkExecutor());
        this.receiver = new ClientMessageReceiver(this, auth);

        // Initialize message tracker with extractors for ClientReply messages
        this.messageTracker = new ConsensusMessageTracker<>(
                (Message m) -> {
                    MessageServiceOuterClass.ClientReply r = (MessageServiceOuterClass.ClientReply) m;
                    return requestIdFor(r.getClientId(), r.getTimestamp());
                },
                (Message m) -> ((MessageServiceOuterClass.ClientReply) m).getServerId(),
                (Message m) -> ((MessageServiceOuterClass.ClientReply) m).getResult()
        );

        updatePrimary(1L); // Initial view
    }

    private void updatePrimary(long viewNumber) {
        this.primaryServerId = computePrimaryServerId(viewNumber);
        logger.info("Updated primary server to {} for view {}", primaryServerId, viewNumber);
    }

    /**
     * Generates a ClientRequest proto message from a StateMachineOperation.
     *
     * @param operation The state machine operation (TransferOp or BalanceRequestOp)
     * @param clientId The client ID making the request
     * @return ClientRequest proto message
     */
    private MessageServiceOuterClass.ClientRequest generateClientRequest(
            org.example.statemachine.StateMachineOperation operation, String clientId) {
        logger.info("generateClientRequest: operation={}, clientId={}", operation, clientId);
        long timestamp = System.currentTimeMillis();

        MessageServiceOuterClass.Operation.Builder opBuilder = MessageServiceOuterClass.Operation.newBuilder();

        // Convert StateMachineOperation to proto Operation
        if (operation instanceof TransferOp(String sender1, String receiver1, double amount)) {
            logger.info("generateClientRequest: Creating Transfer - sender='{}', receiver='{}', amount={}",
                sender1, receiver1, amount);
            MessageServiceOuterClass.Transfer transfer = MessageServiceOuterClass.Transfer.newBuilder()
                    .setSender(sender1)
                    .setReceiver(receiver1)
                    .setAmount(amount)
                    .build();
            opBuilder.setTransfer(transfer);
        } else if (operation instanceof BalanceRequestOp(String accountId)) {
            logger.info("generateClientRequest: Creating BalanceRequest - accountId='{}'", accountId);
            MessageServiceOuterClass.BalanceRequest balanceRequest =
                    MessageServiceOuterClass.BalanceRequest.newBuilder()
                    .setAccountId(accountId)
                    .build();
            opBuilder.setBalanceRequest(balanceRequest);
        } else {
            throw new IllegalArgumentException("Unknown operation type: " + operation.getClass().getName());
        }

        return MessageServiceOuterClass.ClientRequest.newBuilder()
                .setOperation(opBuilder.build())
                .setTimestamp(timestamp)
                .setClientId(clientId)
                .setIsReadOnly(opBuilder.hasBalanceRequest())
                .build();
    }

    // Send request(s) and await consensus; returns true if consensus reached, false on timeout or if cancelled.
    private boolean broadcastOrSendClientRequestWithTimeout(MessageServiceOuterClass.ClientRequest clientRequest, long opGen) {
        String clientId = clientRequest.getClientId();
        long timestamp = clientRequest.getTimestamp();
        String requestId = requestIdFor(clientId, timestamp);

        // If the generation changed since this operation started, abort without sending
        if (opGen != runGeneration.get()) {
            logger.info("Aborting send for request {} because client generation changed (opGen={} current={})", requestId, opGen, runGeneration.get());
            return false;
        }

        if (primaryServerId == null) {
            // No known primary: broadcast to all servers concurrently
            logger.info("No primary known. Broadcasting request {} to all servers", requestId);
            MessageServiceOuterClass.ClientRequest clientRequestConsensus = clientRequest.toBuilder().setIsReadOnly(false).build();
            for (String serverId : Config.getServerIds()) {
                // Check generation before each send to avoid sending after reset
                if (opGen != runGeneration.get()) {
                    logger.info("Stopped broadcasting {} because generation changed", requestId);
                    return false;
                }
                try {
                    this.sender.sendRequest(serverId, clientRequestConsensus);
                } catch (IllegalStateException ise) {
                    logger.info("Send aborted for {} because sender inactive: {}", requestId, ise.getMessage());
                    return false;
                } catch (RuntimeException re) {
                    logger.warn("Unexpected exception while sending {} to {}: {}", requestId, serverId, re.getMessage());
                    return false;
                }
            }
        } else {
            // multicast to all servers for read-only requests
            if (clientRequest.getIsReadOnly()) {
                for (String serverId : Config.getServerIds()) {
                    if (opGen != runGeneration.get()) {
                        logger.info("Stopped multicast {} because generation changed", requestId);
                        return false;
                    }
                    try {
                        this.sender.sendRequest(serverId, clientRequest);
                    } catch (IllegalStateException ise) {
                        logger.info("Send aborted for {} because sender inactive: {}", requestId, ise.getMessage());
                        return false;
                    } catch (RuntimeException re) {
                        logger.warn("Unexpected exception while sending {} to {}: {}", requestId, serverId, re.getMessage());
                        return false;
                    }
                }
                logger.info("Sent read-only client request to all servers for request id {}", requestId);
            // Send to known primary
            } else {
                if (opGen != runGeneration.get()) {
                    logger.info("Stopped send to primary for {} because generation changed", requestId);
                    return false;
                }
                try {
                    this.sender.sendRequest(primaryServerId, clientRequest);
                } catch (IllegalStateException ise) {
                    logger.info("Send aborted for {} because sender inactive: {}", requestId, ise.getMessage());
                    return false;
                } catch (RuntimeException re) {
                    logger.warn("Unexpected exception while sending {} to primary {}: {}", requestId, primaryServerId, re.getMessage());
                    return false;
                }
            }
            logger.info("Sent client request to primary {} for id {}", primaryServerId, requestId);
        }

        // Await consensus
        try {
            Message consensus = messageTracker.awaitConsensus(requestId, Duration.ofMillis(getClientRequestTimeoutMillis()), majorityCountForClient());
            logger.info("Completed waiting for request id {} as consensus has been achieved: {}", requestId, consensus.getDescriptorForType().getName());
            handleOperationResult((MessageServiceOuterClass.ClientReply) consensus);
            return true;
        } catch (TimeoutException te) {
            logger.warn("Timed out waiting for consensus for id {} after {} ms", requestId, getClientRequestTimeoutMillis());
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for consensus for id {}", requestId, ie);
            return false;
        } catch (RuntimeException re) {
            // This may be caused by the consensus future being completed exceptionally (e.g., cancellation)
            logger.info("Aborting request {} because awaitConsensus failed or was cancelled: {}", requestId, re.getMessage());
            return false;
        }
    }

    /**
     * Processes a state machine operation (TransferOp or BalanceRequestOp).
     * Sends the operation to servers and awaits consensus.
     *
     * @param operation The state machine operation to process
     */
    public void processOperation(StateMachineOperation operation) {

        MessageServiceOuterClass.ClientRequest clientRequest = generateClientRequest(operation, this.nodeId);

        String requestId = requestIdFor(clientRequest.getClientId(), clientRequest.getTimestamp());
        logger.info("Processing operation {} from client {} at ts {} (requestId={})",
                operation.getClass().getSimpleName(), clientRequest.getClientId(),
                clientRequest.getTimestamp(), requestId);

        // Capture generation for this operation so we can abort if a reset occurs
        final long opGen = runGeneration.get();

        // Consensus tracker will be implicitly created when first reply arrives
        // Keep retrying forever until consensus is reached
        while (true) {
            // If generation changed, abort this operation so no further sends occur
            if (opGen != runGeneration.get()) {
                logger.info("Aborting processing of request {} because client generation changed (opGen={} current={})", requestId, opGen, runGeneration.get());
                return;
            }

            // If paused, wait here between retry attempts so operator can inspect server state
            pauseLock.lock();
            try {
                if (paused) {
                    logger.info("Client {} is paused; waiting to be resumed", this.nodeId);
                    while (paused) {
                        try {
                            unpaused.await();
                        } catch (InterruptedException ie) {
                            // Respect interrupt but avoid tight retry loops: set interrupt flag and break out to check
                            Thread.currentThread().interrupt();
                            logger.warn("Interrupted while paused: {}", ie.getMessage());
                            break;
                        }
                    }
                }
            } finally {
                pauseLock.unlock();
            }

            boolean success = broadcastOrSendClientRequestWithTimeout(clientRequest, opGen);

            // If the thread was interrupted (e.g., executor.shutdownNow), stop retrying and abort
            if (Thread.currentThread().isInterrupted()) {
                logger.info("Client {} operation interrupted; aborting further retries for request {}", this.nodeId, requestId);
                return;
            }

            // If generation changed during send/wait, abort rather than retry
            if (opGen != runGeneration.get()) {
                logger.info("Aborting processing of request {} after send/wait because generation changed", requestId);
                return;
            }

            if (success) {
                break;
            }
            // On timeout, forget primary to trigger broadcast on the next attempt
            primaryServerId = null;
            logger.info("Retrying request after timeout for client {} at ts {} (requestId={})", clientRequest.getClientId(), clientRequest.getTimestamp(), requestId);

            // Small backoff to avoid tight retry loops that can spin when interrupted or cancelled
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.info("Client {} interrupted during backoff; aborting retries for request {}", this.nodeId, requestId);
                return;
            }
         }
    }

    private void handleOperationResult(MessageServiceOuterClass.ClientReply reply) {
        MessageServiceOuterClass.OperationResult result = reply.getResult();
        String resultStr;

        // Calculate time taken for the reply
        long requestTimestamp = reply.getTimestamp();
        long currentTime = System.currentTimeMillis();
        long timeTaken = currentTime - requestTimestamp;

        // Handle the OperationResult oneof
        switch (result.getOpCase()) {
            case RESULT:
                // Boolean result from Transfer operation
                resultStr = "success=" + result.getResult();
                break;
            case BALANCE:
                // Double result from BalanceRequest operation
                resultStr = "balance=" + result.getBalance();
                break;
            case OP_NOT_SET:
            default:
                resultStr = "no_result";
                break;
        }

        logger.info("Consensus reached for id {}: result={}, from={}, timeTaken={}ms",
                requestIdFor(reply.getClientId(), reply.getTimestamp()),
                resultStr, reply.getServerId(), timeTaken);
        updatePrimary(reply.getViewNumber());
    }

    public void onClientReply(MessageServiceOuterClass.ClientReply reply) {
        String requestId = requestIdFor(reply.getClientId(), reply.getTimestamp());

        logger.info("Received ClientReply from {} for request {} with result {}",
                reply.getServerId(), requestId, reply.getResult());

        // Record reply and check if consensus is reached
        boolean quorumReached = messageTracker.recordMessageAndCheckQuorum(
                requestId,
                reply,
                majorityCountForClient());

        if (quorumReached) {
            logger.info("Consensus reached for request {} after receiving reply from {} (quorum={})",
                    requestId, reply.getServerId(), majorityCountForClient());
        } else {
            logger.info("Recorded ClientReply from {} for request {}, waiting for more replies (need quorum={})",
                    reply.getServerId(), requestId, majorityCountForClient());
        }
    }

    public void reset() {
        // Deactivate sender so any previously-scheduled network tasks abort when they run
        try {
            this.sender.setActive(false);
        } catch (Exception e) {
            logger.warn("Failed to deactivate sender during reset: {}", e.getMessage());
        }

        // Bump the run generation so any currently-processing operations will stop
        runGeneration.incrementAndGet();
        // Cancel and clear any waiting consensus buckets
        this.messageTracker.clear();
        updatePrimary(1L); // Initial view

        // Allow a short grace period for already-scheduled network tasks to run and observe the inactive flag
        try {
            Thread.sleep(100);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Reactivate sender for the new run
        this.sender.setActive(true);
    }

    public void start() {
        this.startAsync(receiver);
    }

    public void shutdown() {
        this.receiver.shutdown();
        this.sender.shutdown();
        super.shutdown();
    }

    // Pause / resume control used by CLI
    public void pause() {
        pauseLock.lock();
        try {
            paused = true;
            logger.info("Client {} paused", this.nodeId);
        } finally {
            pauseLock.unlock();
        }
    }

    public void resume() {
        pauseLock.lock();
        try {
            paused = false;
            unpaused.signalAll();
            logger.info("Client {} resumed", this.nodeId);
        } finally {
            pauseLock.unlock();
        }
    }

    public boolean isPaused() {
        return paused;
    }
}
