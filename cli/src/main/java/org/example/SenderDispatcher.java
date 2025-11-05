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
        submitted.incrementAndGet();
        ex.execute(() -> {
            try {
                ClientNode clientNode = clients.get(clientId);
                clientNode.reset();

                // Process the operation directly
                clientNode.processOperation(operation);
            } finally {
                completed.incrementAndGet();
            }
        });
    }

    public Status snapshotStatus() {
        long s = submitted.get();
        long c = completed.get();
        return new Status(s, c, Math.max(0, s - c));
    }

    /**
     * Cancels all pending operations and resets for a new transaction set.
     * Shuts down all executors and recreates them with fresh client nodes.
     */
    public void reset() {
        // Shutdown all existing executors
        // Forcefully shutdown, cancelling pending tasks
        executors.values().forEach(ExecutorService::shutdownNow);

        // Shutdown all existing client nodes
        clients.values().forEach(ClientNode::shutdown);

        // Clear the maps
        executors.clear();
        clients.clear();

        // Reset counters
        submitted.set(0);
        completed.set(0);

        // Recreate executors and clients
        for (char c = 'A'; c <= 'J'; c++) {
            String id = String.valueOf(c);
            executors.put(id, newSingle("sender-" + id));
            ClientNode client = new ClientNode(id);
            client.start();
            clients.put(id, client);
        }
        executors.put(LF_SENDER, newSingle("sender-LF"));
    }

    @Override
    public void close() {
        executors.values().forEach(ExecutorService::shutdown);
    }

    public record Status(long submitted, long completed, long outstanding) {
    }
}
