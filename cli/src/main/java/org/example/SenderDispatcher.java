package org.example;

import org.example.statemachine.BalanceRequestOp;
import org.example.statemachine.StateMachineOperation;
import org.example.statemachine.TransferOp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class SenderDispatcher implements AutoCloseable {
    public static final String LF_SENDER = "LF";

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<String, ClientNode> clients = new ConcurrentHashMap<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();

    // Generation token to distinguish tasks belonging to different transaction set runs.
    // When reset() is called, this token is incremented so previously-submitted tasks
    // will not affect the new counters or perform work for the new set.
    private final AtomicLong generation = new AtomicLong(0);

    public SenderDispatcher() {
        for (char c = 'A'; c <= 'J'; c++) {
            String id = String.valueOf(c);
            executors.put(id, newSingle("sender-" + id));
            ClientNode client = new ClientNode(id);
            client.start();
            clients.put(id, client);
        }
        executors.put(LF_SENDER, newSingle("sender-LF"));
    }

    private static ExecutorService newSingle(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName(name);
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(StateMachineOperation operation) {
        // Determine the client ID based on operation type
        String clientId;
        if (operation instanceof TransferOp transferOp) {
            clientId = transferOp.sender();
        } else if (operation instanceof BalanceRequestOp balanceRequestOp) {
            clientId = balanceRequestOp.accountId();
        } else {
            // Unknown operation type, skip
            return;
        }

        ExecutorService ex = executors.get(clientId);
        if (ex == null) throw new IllegalStateException("No executor for client " + clientId);

        // Capture the current generation so we can ignore tasks submitted for previous runs
        final long taskGen = generation.get();
        submitted.incrementAndGet();
        ex.execute(() -> {
            try {
                // If the generation has changed since submission, skip processing this task.
                if (taskGen != generation.get()) {
                    return;
                }

                ClientNode clientNode = clients.get(clientId);
                // If client has been removed for some reason, skip
                if (clientNode == null) return;

                // Process the operation directly
                clientNode.processOperation(operation);
            } finally {
                // Only count completion for tasks that belong to the current generation.
                if (taskGen == generation.get()) {
                    completed.incrementAndGet();
                }
            }
        });
    }

    public Status snapshotStatus() {
        long s = submitted.get();
        long c = completed.get();
        return new Status(s, c, Math.max(0, s - c));
    }

    /**
     * Cancels pending operations for the current transaction set and resets for a new set.
     * Clients remain running; we advance the generation token so previously-submitted tasks
     * become no-ops and will not affect counters. Client state is reset via ClientNode.reset().
     */
    public void reset() {
        // Bump generation so previously-submitted tasks will be ignored.
        generation.incrementAndGet();

        // Reset client state (do not shut them down)
        clients.values().forEach(ClientNode::reset);

        // Reset counters for the new transaction set
        submitted.set(0);
        completed.set(0);

        // Executors and clients remain intact to avoid tearing down network resources.
    }

    @Override
    public void close() {
        executors.values().forEach(ExecutorService::shutdown);
    }

    public record Status(long submitted, long completed, long outstanding) {
    }

    // Pause/resume control
    public boolean pauseClient(String clientId) {
        ClientNode client = clients.get(clientId);
        if (client == null) return false;
        client.pause();
        return true;
    }

    public boolean resumeClient(String clientId) {
        ClientNode client = clients.get(clientId);
        if (client == null) return false;
        client.resume();
        return true;
    }

    public boolean isClientPaused(String clientId) {
        ClientNode client = clients.get(clientId);
        if (client == null) return false;
        return client.isPaused();
    }

    public void pauseAll() {
        clients.values().forEach(ClientNode::pause);
    }

    public void resumeAll() {
        clients.values().forEach(ClientNode::resume);
    }
}
