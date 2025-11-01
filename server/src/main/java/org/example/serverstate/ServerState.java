package org.example.serverstate;

import com.google.protobuf.Message;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.messaging.ServerMessage;
import org.example.statemachine.BankStateMachine;

import java.util.HashMap;
import java.util.concurrent.*;

import static org.example.Node.computePrimaryServerId;

/**
 * Actor-like state holder: all mutations are serialized on the single-threaded state-manager executor.
 * Default API is blocking (runSync) with optional async variants for composition.
 */
public final class ServerState {

    // Executor provided by ExecutorManager (named "state-manager-*" thread)
    private final ExecutorService stateExec;

    private static final long INITIAL_VIEW = 0L;

    // Header fields — only accessed/mutated on the stateExec thread
    private String serverId;
    private long viewNumber;
    private String primaryServerId;
    private boolean isPrimary;
    private boolean isFaulty;
    private long seqNum;
    private long lastExecutedSeqNum;
    private long lowWatermark = 0L;
    private long highWatermark = 100L;

    // State machine: balances
    private StateMachine stateMachine;

    // Reply tracking and caches
    private final ConcurrentHashMap<String, Long> replyTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> replyCache = new ConcurrentHashMap<>();

    // Checkpoints and message history
    private final ConcurrentLinkedQueue<Object> checkpoints = new ConcurrentLinkedQueue<>();
    private final ServerMessageTracker serverMessageTracker = new ServerMessageTracker();

    // Output buffer drained by networking; enqueue from actor for ordering with state updates
    private final BlockingQueue<Object> outputBuffer = new LinkedBlockingQueue<>();

    // DTO for safe read snapshots
    public record Header(long view, String primary, boolean primaryFlag, boolean faulty, long seq, long lastExec) {
    }

    public ServerState(String serverId, boolean isFaulty, ExecutorService stateExec) {
        this.stateExec = stateExec;
        // Initialize header using synchronous entry to ensure serialization early
        runSync(() -> {
            this.serverId = serverId;
            this.viewNumber = INITIAL_VIEW;
            this.primaryServerId = computePrimaryServerId(viewNumber);
            this.isPrimary = primaryServerId.equals(serverId);
            this.isFaulty = isFaulty;
            this.seqNum = 0L;
            this.lastExecutedSeqNum = 0L;
            this.stateMachine = new BankStateMachine(new HashMap<>(Config.getClientBalances()));
            return null;
        });
    }

    // Core scheduling helpers

    // Re-entrancy: rely on the named thread "state-manager-*"
    private boolean onStateThread() {
        String name = Thread.currentThread().getName();
        return name != null && name.startsWith("state-manager");
    }

    public <T> CompletableFuture<T> runAsync(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        stateExec.execute(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    // Overload for void-returning work
    public CompletableFuture<Void> runAsync(Runnable task) {
        return runAsync(() -> {
            task.run();
            return null;
        });
    }

    public <T> T runSync(Callable<T> task) {
        if (onStateThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw wrap(e);
            }
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
        runSync(() -> {
            task.run();
            return null;
        });
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
        runSync(() -> {
            isFaulty = value;
        });
    }

    public void markExecutedUpTo(long executedSeqNum) {
        runSync(() -> {
            lastExecutedSeqNum = Math.max(lastExecutedSeqNum, executedSeqNum);
        });
    }

    public boolean isPrimary() {
        return runSync(() -> isPrimary);
    }

    public boolean isFaulty() {
        return runSync(() -> isFaulty);
    }

    public String getPrimaryServerId() {
        return runSync(() -> primaryServerId);
    }

    public long getViewNumber() {
        return runSync(() -> viewNumber);
    }

    public long nextSeq() {
        return runSync(() -> ++seqNum);
    }

    public boolean seqNumBetweenWatermarks(long sequenceNumber) {
        return runSync(() -> sequenceNumber > lowWatermark && sequenceNumber <= highWatermark);
    }

    public long getLowWatermark() {
        return lowWatermark;
    }

    public long getHighWatermark() {
        return highWatermark;
    }

    public Header snapshotHeader() {
        return runSync(() -> new Header(viewNumber, primaryServerId, isPrimary, isFaulty, seqNum, lastExecutedSeqNum));
    }

    // State-machine operations — example transfer and read-only balance

    // Generic execute that delegates to the pluggable state machine
    public MessageServiceOuterClass.OperationResult executeOperation(MessageServiceOuterClass.Operation operation) {
        return runSync(() -> stateMachine.execute(operation));
    }

    public Object snapshotStateMachine() {
        return runSync(() -> stateMachine.snapshot());
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

    public void appendServerMessage(ServerMessage msg) {
        runSync(() -> {
            serverMessageTracker.append(msg);
        });
    }

    public void appendServerMessage(Message msg) {
        appendServerMessage(ServerMessage.wrap(msg));
    }

    public ServerMessageTracker getServerMessageTracker() {
        return serverMessageTracker;
    }

    public ServerMessage findPrePrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.findPrePrepare(viewNumber, sequenceNumber));
    }

    public boolean hasPrePrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.findPrePrepare(viewNumber, sequenceNumber) != null);
    }

    public ServerMessage findPrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.findPrepare(viewNumber, sequenceNumber));
    }

    public boolean hasPrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.findPrepare(viewNumber, sequenceNumber) != null);
    }

    public ServerMessage findCommit(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.findCommit(viewNumber, sequenceNumber));
    }

    public boolean hasCommit(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.findCommit(viewNumber, sequenceNumber) != null);
    }

    public void enqueueOutbound(Object msg) {
        runSync(() -> {
            outputBuffer.add(msg);
        });
    }

    public BlockingQueue<Object> outboundQueue() {
        // Expose the queue for a dedicated draining thread; callers must not mutate state directly
        return outputBuffer;
    }

    // Async variants for composition where needed

    public CompletableFuture<Void> setViewAndPrimaryAsync(long newView) {
        return runAsync(() -> {
            setViewAndPrimary(newView);
        });
    }

    public CompletableFuture<Long> nextSeqAsync() {
        return runAsync(this::nextSeq);
    }

    // Reset everything between test sets
    public void reset() {
        runSync(() -> {
            viewNumber = INITIAL_VIEW;
            primaryServerId = computePrimaryServerId(INITIAL_VIEW);
            isPrimary = primaryServerId.equals(serverId);
            isFaulty = false;
            seqNum = 0L;
            lastExecutedSeqNum = 0L;
            stateMachine.reset();
            replyTimestamps.clear();
            replyCache.clear();
            checkpoints.clear();
            serverMessageTracker.clear();
            outputBuffer.clear();
            return null;
        });
    }
}
