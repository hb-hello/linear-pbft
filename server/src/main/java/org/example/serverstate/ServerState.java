package org.example.serverstate;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.messaging.ServerMessage;
import org.example.statemachine.BankStateMachine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import static org.example.Node.computePrimaryServerId;

/**
 * Actor-like state holder: all mutations are serialized on the single-threaded state-manager executor.
 * Default API is blocking (runSync) with optional async variants for composition.
 */
public final class ServerState {

    private static final Logger logger = LogManager.getLogger(ServerState.class);

    // Executor provided by ExecutorManager (named "state-manager-*" thread)
    private final ExecutorService stateExec;

    private static final long INITIAL_VIEW = 1L;

    // Header fields — only accessed/mutated on the stateExec thread
    private String serverId;
    private long viewNumber;
    private String primaryServerId;
    private String collectorServerId;
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
    public record Header(long view, String primary, boolean faulty, long seq, long lastExec) {
    }

    public ServerState(String serverId, boolean isFaulty, ExecutorService stateExec) {
        this.stateExec = stateExec;
        // Initialize header using synchronous entry to ensure serialization early
        runSync(() -> {
            this.serverId = serverId;
            this.viewNumber = INITIAL_VIEW;
            this.primaryServerId = computePrimaryServerId(viewNumber);
            this.collectorServerId = computeCollectorServerId(viewNumber);
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
//        logger.info("Current thread name: {}, on state thread? {}", name, name.startsWith("-state-manager"));
        return name != null && name.startsWith("-state-manager");
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
//                logger.info("Running task synchronously on state thread");
                return task.call();
            } catch (Exception e) {
                throw wrap(e);
            }
        }
        // No timeout: block until completion
        try {
//            logger.info("Submitting task to state executor for synchronous execution as onStateThread was false");
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

    public String computeCollectorServerId(long viewNumber) {
//        return computePrimaryServerId(viewNumber + 1);
        return primaryServerId;
    }

    public void setViewAndPrimary(long newView) {
        runSync(() -> {
            viewNumber = newView;
            primaryServerId = computePrimaryServerId(newView);
            collectorServerId = computeCollectorServerId(newView);
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
        return runSync(() -> primaryServerId.equals(serverId));
    }

    public boolean isFaulty() {
        return runSync(() -> isFaulty);
    }

    public boolean isCollector() {
        return runSync(() -> collectorServerId.equals(serverId));
    }

    public String getPrimaryServerId() {
        return runSync(() -> primaryServerId);
    }

    public String getCollectorServerId() {
        return runSync(() -> collectorServerId);
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

    public void ensureInView(long viewNumber) {
        runSync(() -> {
            if (this.viewNumber != viewNumber) {
                logger.warn("View mismatch: current view {}, expected view {}", this.viewNumber, viewNumber);
                throw new IllegalStateException("Current view " + this.viewNumber + " does not match expected view " + viewNumber);
            }
        });
    }

    public void ensureInWatermarks(long sequenceNumber) {
        runSync(() -> {
            if (!seqNumBetweenWatermarks(sequenceNumber)) {
                logger.warn("Sequence number {} out of watermarks (low: {}, high: {})",
                        sequenceNumber, lowWatermark, highWatermark);
                throw new IllegalStateException("Sequence number " + sequenceNumber +
                        " out of watermarks (low: " + lowWatermark + ", high: " + highWatermark + ")");
            }
        });
    }

    public Header snapshotHeader() {
        return runSync(() -> new Header(viewNumber, primaryServerId, isFaulty, seqNum, lastExecutedSeqNum));
    }

    // State-machine operations — example transfer and read-only balance

    // Generic execute that delegates to the pluggable state machine
    public MessageServiceOuterClass.OperationResult executeOperation(MessageServiceOuterClass.Operation operation) {
        logger.info("Executing operation of type: {}", operation.getOpCase());
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
        logger.info("Fetching last reply timestamp for clientId: {}", clientId);
        return runSync(() -> {
            logger.info("Current reply timestamp {}", replyTimestamps.getOrDefault(clientId, 0L));
            return replyTimestamps.getOrDefault(clientId, 0L);
        });
    }

    public Object cachedReply(String clientId) {
        return runSync(() -> replyCache.get(clientId));
    }

    // Logs and buffers

    public boolean appendServerMessage(Message msg) {
        ServerMessage serverMsg = ServerMessage.wrap(msg);
        logger.info("Appending server message: {}", serverMsg.toDetailedString());
        return runSync(() -> serverMessageTracker.append(serverMsg));
    }

    // every time a pre-prepare is received, check quorum for matching prepares
    // every time a prepare is received, check quorum for matching prepares and commits
    // every time a commit is received, check quorum for matching commits
    public boolean checkMessageQuorum(Message message, int quorumSize) {
        return runSync(() -> serverMessageTracker.checkMessageQuorum(ServerMessage.wrap(message), quorumSize));
    }

    public Map<String, ByteString> getQuorumSignatures(String messageType, long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.getQuorumSignatures(messageType, viewNumber, sequenceNumber));
    }

    public ByteString getQuorumDigest(String messageType, long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.getQuorumValue(messageType, viewNumber, sequenceNumber));
    }

    public ServerMessageTracker getServerMessageTracker() {
        return serverMessageTracker;
    }

    public ServerMessage findPrePrepare(long viewNumber, long sequenceNumber, String senderId) {
        return runSync(() -> serverMessageTracker.findMessage(ServerMessage.PRE_PREPARE, viewNumber, sequenceNumber, senderId));
    }

    public boolean hasPrePrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.hasMessage(ServerMessage.PRE_PREPARE, viewNumber, sequenceNumber));
    }

    public ServerMessage findPrepare(long viewNumber, long sequenceNumber, String senderId) {
        return runSync(() -> serverMessageTracker.findMessage(ServerMessage.PREPARE, viewNumber, sequenceNumber, senderId));
    }

    public boolean hasPrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.hasMessage(ServerMessage.PREPARE, viewNumber, sequenceNumber));
    }

    public boolean isPrepared(long viewNumber, long sequenceNumber, int quorumSize) {
        return runSync(() -> {
            if (!hasPrePrepare(viewNumber, sequenceNumber)) {
                return false;
            }

            int quorumSizeExcludingPrePrepare = quorumSize - 1;

            return serverMessageTracker.checkMessageQuorum(ServerMessage.PREPARE, viewNumber, sequenceNumber, quorumSizeExcludingPrePrepare);
        });
    }

    public ServerMessage findCommit(long viewNumber, long sequenceNumber, String senderId) {
        return runSync(() -> serverMessageTracker.findMessage(ServerMessage.COMMIT, viewNumber, sequenceNumber, senderId));
    }

    public boolean hasCommit(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.hasMessage(ServerMessage.COMMIT, viewNumber, sequenceNumber));
    }

    public boolean isCommitted(long viewNumber, long sequenceNumber, int quorumSize) {
        return runSync(() -> {
            if (!hasPrePrepare(viewNumber, sequenceNumber)) {
                return false;
            }

            if (!isPrepared(viewNumber, sequenceNumber, quorumSize)) {
                return false;
            }

            return serverMessageTracker.checkMessageQuorum(ServerMessage.COMMIT, viewNumber, sequenceNumber, quorumSize);
        });
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
            primaryServerId = computePrimaryServerId(viewNumber);
            collectorServerId = computeCollectorServerId(viewNumber);
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
