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
    private final StateMachine stateMachine;
    private long lastExecutedSeqNum = 0L;
    private final Map<Long, MessageServiceOuterClass.ClientRequest> pendingOperations = new ConcurrentHashMap<>();
    private final ExecutorService stateMachineExecutor;
    private final BiConsumer<MessageServiceOuterClass.ClientRequest, MessageServiceOuterClass.ClientReply> replySender;

    // all methods should be called from within state's runSync
    public StateMachineOperator(ServerState state,
                                BiConsumer<MessageServiceOuterClass.ClientRequest, MessageServiceOuterClass.ClientReply> replySender) {
        this.state = state;
        this.replySender = replySender;
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
        if (!pendingOperations.isEmpty()) executePendingOperations();
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

            // Execute the operation
            MessageServiceOuterClass.Operation operation = request.getOperation();
            logger.info("Executing operation of type: {}", operation.getOpCase());
            MessageServiceOuterClass.OperationResult result = stateMachine.execute(operation);
            markExecutedUpTo(seqNum);

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

            // Execute the operation directly without calling executeOperation to avoid recursion
            MessageServiceOuterClass.Operation operation = nextRequest.getOperation();
            logger.info("Executing pending operation with seqNum {} of type: {}", nextSeqNum, operation.getOpCase());
            MessageServiceOuterClass.OperationResult result = stateMachine.execute(operation);

            MessageServiceOuterClass.ClientReply reply = MessageServiceOuterClass.ClientReply.newBuilder()
                    .setViewNumber(state.getViewNumber())
                    .setTimestamp(nextRequest.getTimestamp())
                    .setClientId(nextRequest.getClientId())
                    .setServerId(state.getServerId())
                    .setResult(result)
                    .build();

            pendingOperations.remove(nextSeqNum);
            lastExecutedSeqNum = nextSeqNum;

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

    public Object snapshot() {
        return stateMachine.snapshot();
    }

    public void reset() {
        lastExecutedSeqNum = 0L;
        pendingOperations.clear();
        stateMachine.reset();
    }

    public void shutdown() {
        stateMachineExecutor.shutdown();
    }

}
