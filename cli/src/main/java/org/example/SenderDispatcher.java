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
            clients.put(id, new ClientNode(id));
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
                clientNode.start();

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

    @Override
    public void close() {
        executors.values().forEach(ExecutorService::shutdown);
    }

    public record Status(long submitted, long completed, long outstanding) {
    }
}
