package org.example.serverstate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.statemachine.BankStateMachine;

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

    // all methods should be called from within state's runSync
    public StateMachineOperator(ServerState state, OperationLog operationLog,
                                BiConsumer<MessageServiceOuterClass.ClientRequest, MessageServiceOuterClass.ClientReply> replySender,
                                BiConsumer<ServerState, Long> checkpointSender) {
        this.state = state;
        this.operationLog = operationLog;
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

    public void markExecutedUpTo(long executedSeqNum) {
        pendingOperations.remove(executedSeqNum);
        lastExecutedSeqNum = Math.max(lastExecutedSeqNum, executedSeqNum);
        lastExecutedView = state.getViewNumber();
        if (!pendingOperations.isEmpty()) executePendingOperations();
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

            return reply;
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

            // Send the reply to the client for the pending operation using the callback
            replySender.accept(nextRequest, reply);
        }
        logger.info("Finished executing pending operations up to seqNum {}", lastExecutedSeqNum);
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
    }

    public void shutdown() {
        stateMachineExecutor.shutdown();
    }

}
