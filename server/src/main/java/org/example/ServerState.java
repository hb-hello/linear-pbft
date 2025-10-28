package org.example;

import java.util.Map;
import java.util.concurrent.*;

import static org.example.Node.computePrimaryServerId;

/**
 * Actor-like state holder: all mutations are serialized on the single-threaded state-manager executor.
 * Default API is blocking (runSync) with optional async variants for composition.
 */
public final class ServerState {

    // Executor provided by ExecutorManager (named "state-manager-*" thread)
    private final ExecutorService stateExec;

    // Re-entrancy: rely on the named thread "state-manager-*"
    private boolean onStateThread() {
        String name = Thread.currentThread().getName();
        return name != null && name.startsWith("state-manager");
    }

    private static final long INIT_VIEW = 0L;

    // Header fields — only accessed/mutated on the stateExec thread
    private String serverId;
    private long viewNumber;
    private String primaryServerId;
    private boolean isPrimary;
    private boolean isFaulty;
    private long seqNum;
    private long lastExecutedSeqNum;

    // State machine: balances
    private final ConcurrentHashMap<String, Double> balances = new ConcurrentHashMap<>();

    // Reply tracking and caches
    private final ConcurrentHashMap<String, Long> replyTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> replyCache = new ConcurrentHashMap<>();

    // Checkpoints and message history
    private final ConcurrentLinkedQueue<Object> checkpoints = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Object> serverMessages = new ConcurrentLinkedQueue<>();

    // Output buffer drained by networking; enqueue from actor for ordering with state updates
    private final BlockingQueue<Object> outputBuffer = new LinkedBlockingQueue<>();

    // DTO for safe read snapshots
    public record Header(long view, String primary, boolean primaryFlag, boolean faulty, long seq, long lastExec) {}

    public ServerState(String serverId, boolean isFaulty, ExecutorService stateExec) {
        this.stateExec = stateExec;
        // Initialize header using synchronous entry to ensure serialization early
        runSync(() -> {
            this.serverId = serverId;
            this.viewNumber = INIT_VIEW;
            this.primaryServerId = computePrimaryServerId(viewNumber);
            this.isPrimary = primaryServerId.equals(serverId);
            this.isFaulty = isFaulty;
            this.seqNum = 0L;
            this.lastExecutedSeqNum = 0L;
            return null;
        });
    }

    // Core scheduling helpers

    public <T> CompletableFuture<T> runAsync(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        stateExec.execute(() -> {
            try { f.complete(task.call()); }
            catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }

    // Overload for void-returning work
    public CompletableFuture<Void> runAsync(Runnable task) {
        return runAsync(() -> { task.run(); return null; });
    }

    public <T> T runSync(Callable<T> task) {
        if (onStateThread()) {
            try { return task.call(); } catch (Exception e) { throw wrap(e); }
        }
        // No timeout: block until completion
        try {
            return runAsync(task).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("State task interrupted", ie);
        } catch (ExecutionException ee) {
            throw wrap(ee.getCause());
        }
    }

    // Overload for void-returning work
    public void runSync(Runnable task) {
        runSync(() -> { task.run(); return null; });
    }

    private RuntimeException wrap(Throwable t) {
        return (t instanceof RuntimeException re) ? re : new RuntimeException(t);
    }

    // Header operations — blocking by default

    public void setViewAndPrimary(long newView) {
        runSync(() -> {
            viewNumber = newView;
            primaryServerId = computePrimaryServerId(newView);
            isPrimary = primaryServerId.equals(serverId);
            return null;
        });
    }

    public void setFaulty(boolean value) {
        runSync(() -> { isFaulty = value; });
    }

    public long nextSeq() {
        return runSync(() -> ++seqNum);
    }

    public void markExecutedUpTo(long executedSeqNum) {
        runSync(() -> { lastExecutedSeqNum = Math.max(lastExecutedSeqNum, executedSeqNum); });
    }

    public Header snapshotHeader() {
        return runSync(() -> new Header(viewNumber, primaryServerId, isPrimary, isFaulty, seqNum, lastExecutedSeqNum));
    }

    // State-machine operations — example transfer and read-only balance

    public boolean applyTransfer(String from, String to, double amount) {
        return runSync(() -> {
            double fromBal = balances.getOrDefault(from, 10.0); // initialize if needed
            if (fromBal < amount) return false;
            balances.put(from, fromBal - amount);
            balances.merge(to, amount, Double::sum);
            lastExecutedSeqNum = Math.max(lastExecutedSeqNum, seqNum); // correlate if desired
            return true;
        });
    }

    public double readBalance(String accountId) {
        return runSync(() -> balances.getOrDefault(accountId, 10.0));
    }

    public Map<String, Double> snapshotBalances() {
        return runSync(() -> Map.copyOf(balances));
    }

    // Reply tracking — store the highest timestamp per client and a reply object

    public void rememberReply(String clientId, long timestamp, Object reply) {
        runSync(() -> {
            Long prev = replyTimestamps.get(clientId);
            if (prev == null || timestamp >= prev) {
                replyTimestamps.put(clientId, timestamp);
                replyCache.put(clientId, reply);
            }
            return null;
        });
    }

    public Long lastReplyTimestamp(String clientId) {
        return runSync(() -> replyTimestamps.get(clientId));
    }

    public Object cachedReply(String clientId) {
        return runSync(() -> replyCache.get(clientId));
    }

    // Logs and buffers

    public void appendServerMessage(Object msg) {
        runSync(() -> { serverMessages.add(msg); });
    }

    public void enqueueOutbound(Object msg) {
        runSync(() -> { outputBuffer.add(msg); });
    }

    public BlockingQueue<Object> outboundQueue() {
        // Expose the queue for a dedicated draining thread; callers must not mutate state directly
        return outputBuffer;
    }

    // Async variants for composition where needed

    public CompletableFuture<Void> setViewAndPrimaryAsync(long newView) {
        return runAsync(() -> { setViewAndPrimary(newView); });
    }

    public CompletableFuture<Long> nextSeqAsync() {
        return runAsync(this::nextSeq);
    }

    public CompletableFuture<Boolean> applyTransferAsync(String from, String to, double amount) {
        return runAsync(() -> applyTransfer(from, to, amount));
    }

    // Reset everything between test sets
    public void reset() {
        runSync(() -> {
            viewNumber = INIT_VIEW;
            primaryServerId = computePrimaryServerId(INIT_VIEW);
            isPrimary = primaryServerId.equals(serverId);
            isFaulty = false;
            seqNum = 0L;
            lastExecutedSeqNum = 0L;
            balances.clear();
            replyTimestamps.clear();
            replyCache.clear();
            checkpoints.clear();
            serverMessages.clear();
            outputBuffer.clear();
            return null;
        });
    }
}
