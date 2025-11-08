package org.example.serverstate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.consensus.LivenessTimer;
import org.example.statemachine.BankStateMachine;
import org.example.messaging.MessageUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public class StateMachineOperator {
    private static final Logger logger = LogManager.getLogger(StateMachineOperator.class);

    private final ServerState state;
    private final OperationLog operationLog;
    private final StateMachine stateMachine;

    private long lastExecutedSeqNum = 0L;
    private long lastExecutedView = 0L;
    private final Map<Long, MessageServiceOuterClass.ClientRequest> pendingOperations = new ConcurrentHashMap<>();
    private final ExecutorService stateMachineExecutor;
    private final BiConsumer<MessageServiceOuterClass.ClientRequest, MessageServiceOuterClass.ClientReply> replySender;
    private final BiConsumer<ServerState, Long> checkpointSender;

    private final LivenessTimer livenessTimer;

    // Reply tracking moved here from ServerState
    private final ConcurrentHashMap<String, Long> replyTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MessageServiceOuterClass.ClientReply> replyCache = new ConcurrentHashMap<>();

    // all methods should be called from within state's runSync
    public StateMachineOperator(ServerState state, OperationLog operationLog, LivenessTimer livenessTimer,
                                BiConsumer<MessageServiceOuterClass.ClientRequest, MessageServiceOuterClass.ClientReply> replySender,
                                BiConsumer<ServerState, Long> checkpointSender) {
        this.state = state;
        this.operationLog = operationLog;
        this.livenessTimer = livenessTimer;
        this.replySender = replySender;
        this.checkpointSender = checkpointSender;
        this.stateMachine = new BankStateMachine(new HashMap<>(Config.getClientBalances()));
        this.stateMachineExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("state-machine-executor-" + state.getServerId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Core execution logic shared by both executeOperation and executePendingOperations.
     * Executes the operation on the state machine and returns the reply.
     */
    private MessageServiceOuterClass.ClientReply executeAndBuildReply(
            MessageServiceOuterClass.ClientRequest request, long seqNum) {

        MessageServiceOuterClass.Operation operation = request.getOperation();
        logger.info("Executing operation of type: {} at seq {}", operation.getOpCase(), seqNum);

        MessageServiceOuterClass.OperationResult result = stateMachine.execute(operation);

        pendingOperations.remove(seqNum);
        lastExecutedSeqNum = seqNum;

        updateTimer();

        if (operationLog != null) {
            operationLog.updateStatus(seqNum, OperationStatus.EXECUTED);
        }

        // Send checkpoint if at checkpoint interval
        checkpointSender.accept(state, seqNum);

        return MessageServiceOuterClass.ClientReply.newBuilder()
                .setViewNumber(state.getViewNumber())
                .setTimestamp(request.getTimestamp())
                .setClientId(request.getClientId())
                .setServerId(state.getServerId())
                .setResult(result)
                .build();
    }

    // State-machine operations — example transfer and read-only balance

    // Generic execute that delegates to the pluggable state machine
    public CompletableFuture<MessageServiceOuterClass.ClientReply> executeOperation(MessageServiceOuterClass.ClientRequest request, long seqNum) {
        // Execute operation in the dedicated state machine executor
        // All checks must happen inside the executor to avoid race conditions
        return CompletableFuture.supplyAsync(() -> {

            String clientId = request.getClientId();
            // Use internal replyTimestamps instead of querying ServerState
            Long lastTimestamp = replyTimestamps.get(clientId);
            if (lastTimestamp != null && lastTimestamp >= request.getTimestamp()) {
                logger.warn("Stale request from client {} with timestamp {}. Last reply timestamp is {}", clientId, request.getTimestamp(), lastTimestamp);
                return null;
            }

            // Add to pending operations if not already there
            if(!pendingOperations.containsKey(seqNum)) {
                pendingOperations.put(seqNum, request);
            }

            // do not repeat execution
            if (seqNum <= lastExecutedSeqNum) {
                logger.warn("Operation with seqNum {} has already been executed up to {}", seqNum, lastExecutedSeqNum);
                return null;
            }

            // do not execute out of order
            if (seqNum > lastExecutedSeqNum + 1) {
                logger.info("Operation with seqNum {} is pending execution. Last executed seqNum is {}", seqNum, lastExecutedSeqNum);
                return null;
            }

            // Execute the operation using shared logic
            MessageServiceOuterClass.ClientReply reply = executeAndBuildReply(request, seqNum);

            // Update lastExecutedView after successful execution
            lastExecutedView = state.getViewNumber();

            // Check for more pending operations
            if (!pendingOperations.isEmpty()) {
                executePendingOperations();
            }

            logger.info("Executed operation with seqNum {}. Last executed seqNum is now {}, Now returning reply", seqNum, lastExecutedSeqNum);

            rememberReply(reply);

            return reply;
        }, stateMachineExecutor);
    }

    /**
     * Execute a read-only operation (no sequence number) on the state machine.
     * Currently supports only BALANCE_REQUEST operations. Execution runs on the
     * same single-threaded stateMachineExecutor to avoid races with state updates.
     * Returns a CompletableFuture that completes with the ClientReply or null if
     * the request does not contain a BALANCE_REQUEST.
     */
    public CompletableFuture<MessageServiceOuterClass.ClientReply> executeReadOnly(MessageServiceOuterClass.ClientRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            MessageServiceOuterClass.Operation op = request.getOperation();
            // Check for balance request
            if (op == null || op.getOpCase() != MessageServiceOuterClass.Operation.OpCase.BALANCE_REQUEST) {
                logger.warn("executeReadOnly called with non-BALANCE_REQUEST operation: {}", op == null ? "null" : op.getOpCase());
                return null;
            }

            // Execute on the state machine (read-only) and build reply. Do not mutate execution state.
            MessageServiceOuterClass.OperationResult result = stateMachine.execute(op);

            return MessageServiceOuterClass.ClientReply.newBuilder()
                    .setViewNumber(state.getViewNumber())
                    .setTimestamp(request.getTimestamp())
                    .setClientId(request.getClientId())
                    .setServerId(state.getServerId())
                    .setResult(result)
                    .build();
        }, stateMachineExecutor);
    }

    private void executePendingOperations() {
        // Process pending operations in order starting from lastExecutedSeqNum + 1
        // This runs synchronously in the current thread (should be the state machine executor thread)
        while (true) {
            long nextSeqNum = lastExecutedSeqNum + 1;
            MessageServiceOuterClass.ClientRequest nextRequest = pendingOperations.get(nextSeqNum);

            if (nextRequest == null) {
                // No more consecutive pending operations
                break;
            }

            // Execute the operation using shared logic
            MessageServiceOuterClass.ClientReply reply = executeAndBuildReply(nextRequest, nextSeqNum);

            // Update lastExecutedView after successful execution
            lastExecutedView = state.getViewNumber();

            // remember the reply
            rememberReply(reply);

            // Send the reply to the client for the pending operation using the callback
            replySender.accept(nextRequest, reply);
        }
        logger.info("Finished executing pending operations up to seqNum {}", lastExecutedSeqNum);
    }

    public void updateTimer() {
        if (livenessTimer == null) {
            logger.warn("Liveness timer is null, cannot update after operation");
            return;
        }

        boolean hasPending = areOperationsPending();
        logger.info("Pending operations present? : {}", hasPending);
        if (hasPending) {
            logger.info("Restarting liveness timer - operations still pending");
            livenessTimer.restart();
        } else {
            logger.info("Stopping liveness timer - no pending operations");
            livenessTimer.stop();
        }
    }

    public boolean areOperationsPending() {
        return !pendingOperations.isEmpty();
    }

    public List<MessageServiceOuterClass.ClientRequest> getPendingOperations() {
        return Map.copyOf(pendingOperations).values().stream().toList();
    }

    public boolean isExecuted(long seqNum) {
        return seqNum <= lastExecutedSeqNum;
    }

    public boolean applySnapshot(Object snapshot, long seqNum) {
        lastExecutedSeqNum = seqNum;
        pendingOperations.clear();
        return stateMachine.applySnapshot(snapshot);
    }

    public Object snapshot() {
        return stateMachine.snapshot();
    }

    public String snapshotToString() {
        return stateMachine.snapshotToString();
    }

    public long getLastExecutedView() {
        return lastExecutedView;
    }

    public void reset() {
        lastExecutedSeqNum = 0L;
        lastExecutedView = 0L;
        pendingOperations.clear();
        stateMachine.reset();
        // Clear reply caches when operator is reset
        replyTimestamps.clear();
        replyCache.clear();
    }

    public void shutdown() {
        stateMachineExecutor.shutdown();
    }

    // Reply tracking methods moved from ServerState
    public void rememberReply(MessageServiceOuterClass.ClientReply reply) {
        if (reply == null) return;
        String clientId = reply.getClientId();
        long timestamp = reply.getTimestamp();
        Long prev = replyTimestamps.get(clientId);
        if (prev == null || timestamp >= prev) {
            replyTimestamps.put(clientId, timestamp);
        }
        String requestId = MessageUtil.requestIdFor(clientId, timestamp);
        if (!replyCache.containsKey(requestId)) {
            logger.info("Remembering reply for clientId: {} timestamp: {} requestId: {}", clientId, timestamp, requestId);
            replyCache.put(requestId, reply);
        }
    }

    public Long lastReplyTimestamp(String clientId) {
        return replyTimestamps.getOrDefault(clientId, 0L);
    }

    public MessageServiceOuterClass.ClientReply cachedReply(String clientId, long timestamp) {
        return replyCache.get(MessageUtil.requestIdFor(clientId, timestamp));
    }

    public Map<String, Long> getClientReplyTimestamps() {
        return Map.copyOf(replyTimestamps);
    }

    public Map<String, MessageServiceOuterClass.ClientReply> getClientReplyCache() {
        return Map.copyOf(replyCache);
    }
}
