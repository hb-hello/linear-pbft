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

import static org.example.messaging.MessageUtil.requestIdFor;

public class ClientNode extends Node {
    private static final Logger logger = LogManager.getLogger(ClientNode.class);

    private String primaryServerId;

    private final ClientMessageSender sender;
    private final ClientMessageReceiver receiver;
    private final org.example.consensus.ConsensusMessageTracker<String, MessageServiceOuterClass.OperationResult> messageTracker;

    private static final int MAJORITY_REQUIRED = 3;

    public ClientNode(String nodeId) {
        super(nodeId);
        this.sender = new ClientMessageSender(nodeId, commLogger, auth);
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
                .build();
    }

    // Send request(s) and await consensus; returns true if consensus reached, false on timeout.
    private boolean broadcastOrSendClientRequestWithTimeout(MessageServiceOuterClass.ClientRequest clientRequest) {
        String clientId = clientRequest.getClientId();
        long timestamp = clientRequest.getTimestamp();
        String requestId = requestIdFor(clientId, timestamp);

        if (primaryServerId == null) {
            // No known primary: broadcast to all servers concurrently
            logger.info("No primary known. Broadcasting request {} to all servers", requestId);
            for (String serverId : Config.getServerIds()) {
                this.sender.sendRequest(serverId, clientRequest);
            }
        } else {
            // Send to known primary
            this.sender.sendRequest(primaryServerId, clientRequest);
            logger.info("Sent client request to primary {} for id {}", primaryServerId, requestId);
        }

        // Await consensus
        try {
            Message consensus = messageTracker.awaitConsensus(requestId, Duration.ofMillis(getClientRequestTimeoutMillis()), MAJORITY_REQUIRED);
            handleOperationResult((MessageServiceOuterClass.ClientReply) consensus);
            return true;
        } catch (TimeoutException te) {
            logger.warn("Timed out waiting for consensus for id {} after {} ms", requestId, getClientRequestTimeoutMillis());
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for consensus for id {}", requestId);
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

        // Consensus tracker will be implicitly created when first reply arrives
        // Keep retrying forever until consensus is reached
        while (true) {
            boolean success = broadcastOrSendClientRequestWithTimeout(clientRequest);
            if (success) {
                break;
            }
            // On timeout, forget primary to trigger broadcast on the next attempt
            primaryServerId = null;
            logger.info("Retrying request after timeout for client {} at ts {} (requestId={})", clientRequest.getClientId(), clientRequest.getTimestamp(), requestId);
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
                majorityCount());

        if (quorumReached) {
            logger.info("Consensus reached for request {} after receiving reply from {} (quorum={})",
                    requestId, reply.getServerId(), majorityCount());
        } else {
            logger.info("Recorded ClientReply from {} for request {}, waiting for more replies (need quorum={})",
                    reply.getServerId(), requestId, majorityCount());
        }
    }

    public void start() {
        this.startAsync(receiver);
    }
}