package org.example.serverstate;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.messaging.ServerMessage;
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

        MessageServiceOuterClass.OperationResult result = null;
        if (!request.getClientId().equals("no-op")) result = stateMachine.execute(operation);

        pendingOperations.remove(seqNum);
        lastExecutedSeqNum = seqNum;

        updateTimer();

        if (operationLog != null) {
            operationLog.updateStatus(seqNum, OperationStatus.EXECUTED);
        }

        // Send checkpoint if at checkpoint interval
        checkpointSender.accept(state, seqNum);

        if (result != null) {
            return MessageServiceOuterClass.ClientReply.newBuilder()
                    .setViewNumber(state.getViewNumber())
                    .setTimestamp(request.getTimestamp())
                    .setClientId(request.getClientId())
                    .setServerId(state.getServerId())
                    .setResult(result)
                    .build();
        }
        return null;
    }

    // State-machine operations — example transfer and read-only balance

    // Generic execute that delegates to the pluggable state machine

    /**
     * New variant that accepts a precomputed digest. This lets the operator detect
     * and instantiate a no-op (null-request) based on the digest (DRY: single place for null-request handling).
     */
    public CompletableFuture<MessageServiceOuterClass.ClientReply> executeOperation(MessageServiceOuterClass.ClientRequest request, ByteString digest, long seqNum) {
        // Delegate to the executor-aware implementation which centralizes null-request handling
        return executeOperationWithEffectiveRequest(request, digest, seqNum);
    }

    // Backwards-compatible overload that preserves previous API (digest unknown)
    public CompletableFuture<MessageServiceOuterClass.ClientReply> executeOperation(MessageServiceOuterClass.ClientRequest request, long seqNum) {
        return executeOperation(request, null, seqNum);
    }

    // NOTE: To keep null-request handling centralized we intercept null/empty requests here
    // and construct a no-op if the digest indicates a null operation. This logic must run
    // inside the state machine executor before any execution/pending logic to avoid races.
    private MessageServiceOuterClass.ClientRequest handleNullRequestIfNeeded(MessageServiceOuterClass.ClientRequest request, ByteString digest, long seqNum) {
        if (request == null || !request.hasOperation()) {
            logger.info("Encountered null request for seqNum {}, checking the digest to verify if no-op", seqNum);
            ByteString nullDigest = ByteString.copyFrom(new byte[32]);
            if (nullDigest.equals(digest)) {
                logger.info("Digest matches null digest, executing no-op for seqNum {}", seqNum);

                MessageServiceOuterClass.Operation noOp = MessageServiceOuterClass.Operation.newBuilder()
                        .setBalanceRequest(MessageServiceOuterClass.BalanceRequest.newBuilder().setAccountId("A").build())
                        .build();

                request = MessageServiceOuterClass.ClientRequest.newBuilder().setClientId("no-op")
                        .setTimestamp(System.currentTimeMillis())
                        .setOperation(noOp)
                        .setClientId("no-op")
                        .setSignerId("no-op")
                        .build();
            }
        }
        return request;
    }

    // Internal implementation executed on stateMachineExecutor to centralize logic
    private CompletableFuture<MessageServiceOuterClass.ClientReply> executeOperationWithEffectiveRequest(MessageServiceOuterClass.ClientRequest request, ByteString digest, long seqNum) {
        return CompletableFuture.supplyAsync(() -> {
            // First, ensure null-request logic runs in executor thread
            MessageServiceOuterClass.ClientRequest effectiveRequest = handleNullRequestIfNeeded(request, digest, seqNum);
            if (effectiveRequest == null || !effectiveRequest.hasOperation()) {
                logger.info("Request not found or has no operation after null-digest check for seq {}", seqNum);
                return null;
            }

            String clientId = effectiveRequest.getClientId();
            Long lastTimestamp = replyTimestamps.get(clientId);
            if (lastTimestamp != null && lastTimestamp >= effectiveRequest.getTimestamp()) {
                logger.warn("Stale request from client {} with timestamp {}. Last reply timestamp is {}", clientId, effectiveRequest.getTimestamp(), lastTimestamp);
                return null;
            }

            if (!pendingOperations.containsKey(seqNum)) {
                pendingOperations.put(seqNum, effectiveRequest);
            }

            if (seqNum <= lastExecutedSeqNum) {
                logger.warn("Operation with seqNum {} has already been executed up to {}", seqNum, lastExecutedSeqNum);
                return null;
            }

            if (seqNum > lastExecutedSeqNum + 1) {
                logger.info("Operation with seqNum {} is pending execution. Last executed seqNum is {}", seqNum, lastExecutedSeqNum);
                return null;
            }

            MessageServiceOuterClass.ClientReply reply;
            reply = executeAndBuildReply(effectiveRequest, seqNum);

            lastExecutedView = state.getViewNumber();

            if (!pendingOperations.isEmpty()) {
                executePendingOperations();
            }

            logger.info("Executed operation with seqNum {}. Last executed seqNum is now {}, Now returning reply", seqNum, lastExecutedSeqNum);

            if (reply != null) {
                rememberReply(reply);
                replySender.accept(effectiveRequest, reply);
            }

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

            // Defensive: if the pending request exists but carries no operation (placeholder),
            // attempt to recover the real client request using the pre-prepare digest or instantiate
            // a no-op when the digest equals the null digest. If we cannot resolve it yet, stop
            // processing further pending operations.
            if (nextRequest == null || !nextRequest.hasOperation()) {
                logger.info("Pending request at seq {} has no operation; attempting to resolve via current-view pre-prepare digest", nextSeqNum);

                // Fetch the PrePrepare for the current view at this sequence (direct method requested)
                ServerMessage prePrepareMsg = state.findCurrentViewPrePrepare(nextSeqNum);
                if (prePrepareMsg == null) {
                    logger.info("No PrePrepare found for current view at seq {} yet; cannot execute pending", nextSeqNum);
                    break;
                }

                if (!(prePrepareMsg.getMessage() instanceof MessageServiceOuterClass.PrePrepareMessage ppm)) {
                    logger.warn("PrePrepare message for seq {} is not of expected type; cannot execute pending", nextSeqNum);
                    break;
                }

                ByteString prePrepareDigest = ppm.getDigest();
                if (prePrepareDigest == null) {
                    logger.info("PrePrepare digest is null for seq {}; cannot execute pending", nextSeqNum);
                    break;
                }

                // If digest equals the special null-digest, create the no-op request here
                ByteString nullDigest = ByteString.copyFrom(new byte[32]);
                if (nullDigest.equals(prePrepareDigest)) {
                    logger.info("PrePrepare digest for seq {} indicates null/no-op; creating no-op request", nextSeqNum);
                    MessageServiceOuterClass.ClientRequest noOp = handleNullRequestIfNeeded(null, prePrepareDigest, nextSeqNum);
                    if (noOp != null) {
                        nextRequest = noOp;
                        pendingOperations.put(nextSeqNum, nextRequest);
                    } else {
                        logger.info("Failed to create no-op for seq {} despite null digest; cannot proceed", nextSeqNum);
                        break;
                    }
                } else {
                    // Non-null digest: per request, do not attempt to resolve the client request here; wait
                    logger.info("PrePrepare digest for seq {} is non-null and not a no-op; client request resolution handled elsewhere; cannot execute pending", nextSeqNum);
                    break;
                }
            }

            // Execute the operation using shared logic
            MessageServiceOuterClass.ClientReply reply = executeAndBuildReply(nextRequest, nextSeqNum);

            // Update lastExecutedView after successful execution
            lastExecutedView = state.getViewNumber();

            if (reply != null) {
                rememberReply(reply);
                replySender.accept(nextRequest, reply);
            }
        }
        logger.info("Finished executing pending operations up to seqNum {}", lastExecutedSeqNum);
    }

    public void updateTimer() {
        if (livenessTimer == null) {
            logger.warn("Liveness timer is null, cannot update after operation");
            return;
        }

        boolean hasPending = areOperationsPending() || !state.findClientRequestsNotCommitted().isEmpty();
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
        if (lastExecutedSeqNum >= seqNum) {
            logger.info("Snapshot at seqNum {} is not newer than last executed seqNum {}, skipping apply", seqNum, lastExecutedSeqNum);
            return false;
        }

        lastExecutedSeqNum = seqNum;
        // remove all from pending operations with key less than or equal to seqNum

        boolean applied = stateMachine.applySnapshot((Map<String, Double>) snapshot);
        if (applied) {
            logger.info("APPLY SNAPSHOT: Successfully applied snapshot at seqNum {}", seqNum);
            pendingOperations.keySet().removeIf(key -> key <= seqNum);
            updateTimer();
        }
        return applied;
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
