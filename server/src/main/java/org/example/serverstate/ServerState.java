package org.example.serverstate;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.consensus.LivenessTimer;
import org.example.messaging.MessageUtil;
import org.example.messaging.ServerMessage;

import java.time.Duration;
import java.util.*;
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
    private boolean viewChangeInProgress;
    private long seqNum;
    private final long checkPointInterval = Config.getCheckpointInterval();
    private long latestStableCheckpointSeqNum = 0L;
    private long highWatermark = latestStableCheckpointSeqNum + Config.getWatermarkWindow();

    // State machine: balances
    private StateMachineOperator stateMachineOperator;
    private final OperationLog operationLog = new OperationLog();

    // Liveness timer
    private LivenessTimer livenessTimer;

    // Reply tracking and caches
    private final ConcurrentHashMap<String, Long> replyTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MessageServiceOuterClass.ClientReply> replyCache = new ConcurrentHashMap<>();

    // Checkpoints and message history
    private final ConcurrentHashMap<Long, MessageServiceOuterClass.CheckpointMessage> stableCheckpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> stableCheckpointSnapshots = new ConcurrentHashMap<>();
    private final ServerMessageTracker serverMessageTracker = new ServerMessageTracker();

    // Output buffer drained by networking; enqueue from actor for ordering with state updates
    private final BlockingQueue<Object> outputBuffer = new LinkedBlockingQueue<>();

    // DTO for safe read snapshots
    public record Header(long view, String primary, boolean faulty, long seq) {
    }

    public ServerState(String serverId, boolean isFaulty, ExecutorService stateExec, LivenessTimer livenessTimer,
                       java.util.function.BiConsumer<MessageServiceOuterClass.ClientRequest,
                                                      MessageServiceOuterClass.ClientReply> replySender,
                       java.util.function.BiConsumer<ServerState, Long> checkpointSender) {
        this.stateExec = stateExec;
        // Initialize header using synchronous entry to ensure serialization early
        runSync(() -> {
            this.serverId = serverId;
            this.viewNumber = INITIAL_VIEW;
            this.primaryServerId = computePrimaryServerId(viewNumber);
            this.collectorServerId = computeCollectorServerId(viewNumber);
            this.isFaulty = isFaulty;
            this.viewChangeInProgress = false;
            this.seqNum = 0L;
            this.stateMachineOperator = new StateMachineOperator(this, operationLog, replySender, checkpointSender);
            this.livenessTimer = livenessTimer;
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

    private <T> CompletableFuture<T> runAsync(Callable<T> task) {
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
    private CompletableFuture<Void> runAsync(Runnable task) {
        return runAsync(() -> {
            task.run();
            return null;
        });
    }

    private <T> T runSync(Callable<T> task) {
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
    private void runSync(Runnable task) {
        runSync(() -> {
            task.run();
            return null;
        });
    }

    private RuntimeException wrap(Throwable t) {
        return (t instanceof RuntimeException re) ? re : new RuntimeException(t);
    }

    // Header operations — blocking by default

    public String getServerId() {
        return runSync(() -> serverId);
    }

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

    public boolean isPrimary() {
        return runSync(() -> primaryServerId.equals(serverId));
    }

    public boolean isFaulty() {
        return runSync(() -> isFaulty);
    }

    public boolean isCollector() {
        return runSync(() -> collectorServerId.equals(serverId));
    }

    public boolean atCheckpointInterval(long sequenceNumber) {
        return runSync(() -> sequenceNumber % checkPointInterval == 0);
    }

    public void addStableCheckpoint(MessageServiceOuterClass.CheckpointMessage checkpointMessage) {
        runSync(() -> {
            logger.info("Adding stable checkpoint for seq {}", checkpointMessage.getSequenceNumber());
            stableCheckpoints.put(checkpointMessage.getSequenceNumber(), checkpointMessage);
            stableCheckpointSnapshots.put(checkpointMessage.getSequenceNumber(),
                    stateMachineOperator.snapshot());

            // update watermarks
            long seqNum = checkpointMessage.getSequenceNumber();
            if (seqNum > latestStableCheckpointSeqNum) {
                latestStableCheckpointSeqNum = seqNum;
                highWatermark = latestStableCheckpointSeqNum + Config.getWatermarkWindow();
                logger.info("Updated watermarks: low {}, high {}", latestStableCheckpointSeqNum, highWatermark);
            }
        });
    }

    public boolean hasStableCheckpoint(long sequenceNumber) {
        return runSync(() -> stableCheckpoints.containsKey(sequenceNumber));
    }

    public MessageServiceOuterClass.CheckpointMessage getLatestStableCheckpoint() {
        return runSync(() -> stableCheckpoints.get(latestStableCheckpointSeqNum));
    }

    public Object getLatestStableCheckpointSnapshot() {
        return runSync(() -> stableCheckpointSnapshots.get(latestStableCheckpointSeqNum));
    }
    
    public long getLatestStableCheckpointSeqNum() {
        return runSync(() -> latestStableCheckpointSeqNum);
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

    public long nextView() {
        return runSync(() -> ++viewNumber);
    }

    public long nextViewAndUpdatePrimary() {
        return runSync(() -> {
            long newView = ++viewNumber;
            primaryServerId = computePrimaryServerId(newView);
            collectorServerId = computeCollectorServerId(newView);
            return newView;
        });
    }

    public boolean seqNumBetweenWatermarks(long sequenceNumber) {
        return runSync(() -> sequenceNumber > latestStableCheckpointSeqNum && sequenceNumber <= highWatermark);
    }

    public long getLowWatermark() {
        return latestStableCheckpointSeqNum;
    }

    public long getHighWatermark() {
        return highWatermark;
    }

    public Iterator<Long> getSeqNumsBetweenWatermarks() {
        return runSync(() -> {
            return new Iterator<>() {
                private long current = latestStableCheckpointSeqNum + 1;

                @Override
                public boolean hasNext() {
                    return current <= highWatermark;
                }

                @Override
                public Long next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return current++;
                }
            };
        });
    }

    public boolean isViewChangeInProgress() {
        return viewChangeInProgress;
    }

    public void setViewChangeInProgress(boolean viewChangeInProgress) {
        this.viewChangeInProgress = viewChangeInProgress;
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
                        sequenceNumber, latestStableCheckpointSeqNum, highWatermark);
                throw new IllegalStateException("Sequence number " + sequenceNumber +
                        " out of watermarks (low: " + latestStableCheckpointSeqNum + ", high: " + highWatermark + ")");
            }
        });
    }

    public void ensureViewChangeNotInProgress() {
        runSync(() -> {
            if (viewChangeInProgress) {
                logger.warn("View change in progress, cannot proceed with operation");
                throw new IllegalStateException("View change in progress");
            }
        });
    }

    public Header snapshotHeader() {
        return runSync(() -> new Header(viewNumber, primaryServerId, isFaulty, seqNum));
    }

    public Map<String, Long> getClientReplyTimestamps() {
        return runSync(() -> Map.copyOf(replyTimestamps));
    }

    public Map<String, MessageServiceOuterClass.ClientReply> getClientReplyCache() {
        return runSync(() -> Map.copyOf(replyCache));
    }

    // State-machine operations — example transfer and read-only balance

    // Generic execute that delegates to the pluggable state machine
    public CompletableFuture<MessageServiceOuterClass.ClientReply> executeRequest(MessageServiceOuterClass.ClientRequest request, ByteString digest, long seqNum) {

        // Handle null request (no-op) case
        if (request == null) {
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
                .build();
            }
        }

        logger.info("Attempting to execute operation of type: {}", request.getOperation().getOpCase());
        return stateMachineOperator.executeOperation(request, seqNum)
            .thenApply(reply -> {
                // After operation completes, update timer based on pending operations
                runSync(() -> {

                    if (livenessTimer == null) {
                        logger.warn("Liveness timer is null, cannot update after operation");
                        return;
                    }

                    boolean hasPending = stateMachineOperator.areOperationsPending();
                    logger.info("Operation completed for seqNum {}. Pending operations: {}", seqNum, hasPending);
                    if(hasPending) {
                        logger.info("Restarting liveness timer - operations still pending");
                        livenessTimer.restart();
                    } else {
                        logger.info("Stopping liveness timer - no pending operations");
                        livenessTimer.stop();
                    }
                });
                return reply;
            });
    }

    public Object snapshotStateMachine() {
        return runSync(() -> stateMachineOperator.snapshot());
    }

    public String printSnapshotStateMachine() {
        return runSync(() -> stateMachineOperator.snapshotToString());
    }
    
    public boolean isExecuted(long sequenceNumber) {
        return runSync(() -> stateMachineOperator.isExecuted(sequenceNumber));
    }

    public boolean applySnapshotToStateMachine(Object snapshot, long seqNum) {
        return runSync(() -> stateMachineOperator.applySnapshot(snapshot, seqNum));
    }

    public List<MessageServiceOuterClass.ClientRequest> getPendingOperations() {
        return runSync(() -> stateMachineOperator.getPendingOperations());
    }

    public long getLastExecutedView() {
        return runSync(() -> stateMachineOperator.getLastExecutedView());
    }

    // Reply tracking — store the highest timestamp per client and a reply object

    public void rememberReply(MessageServiceOuterClass.ClientReply reply) {
        runSync(() -> {
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
        });
    }

    public Long lastReplyTimestamp(String clientId) {
        logger.info("Fetching last reply timestamp for clientId: {}", clientId);
        return runSync(() -> {
            logger.info("Current reply timestamp {}", replyTimestamps.getOrDefault(clientId, 0L));
            return replyTimestamps.getOrDefault(clientId, 0L);
        });
    }

    public MessageServiceOuterClass.ClientReply cachedReply(String clientId, long timestamp) {
        logger.info("Fetching cached reply for clientId: {} timestamp: {}", clientId, timestamp);
        return runSync(() -> replyCache.get(MessageUtil.requestIdFor(clientId, timestamp)));
    }

    // Logs and buffers

    public boolean appendServerMessage(Message msg, int required) {
        return appendServerMessage(msg, null, required);
    }

    public boolean appendServerMessage(Message msg, MessageServiceOuterClass.ClientRequest clientRequest, int required) {
        ServerMessage serverMsg = ServerMessage.wrap(msg);
        logger.info("Appending server message: {}", serverMsg.toDetailedString());
        return runSync(() -> {
            addToOperationLog(serverMsg, clientRequest);
            return serverMessageTracker.append(serverMsg, required);
        });
    }

    public OperationStatus getOperationStatus(long sequenceNumber) {
        return runSync(() -> operationLog.getOperationStatus(sequenceNumber));
    }

    public OperationLogEntry getOperation(long sequenceNumber) {
        return runSync(() -> operationLog.getOperation(sequenceNumber));
    }

    public OperationLog getOperationLog() {
        return runSync(() -> operationLog);
    }

    public String printIndexedServerMessages() {
        return runSync(serverMessageTracker::printIndexedMessages);
    }

    public void addToOperationLog(ServerMessage serverMsg, MessageServiceOuterClass.ClientRequest clientRequest) {
        runSync(() -> {
            switch(serverMsg.getMessageType()) {
                case ServerMessage.PRE_PREPARE:
                    operationLog.addOperation(serverMsg.getSequenceNumber().orElse(-1L), clientRequest, OperationStatus.PREPREPARED);
                    break;
                case ServerMessage.PREPARE:
                    MessageServiceOuterClass.ClientRequest clientRequestPrepare = clientRequest;
                    if (clientRequestPrepare == null) clientRequestPrepare = findClientRequest(serverMsg.getDigest().orElse(null));
                    operationLog.addOperationOrUpdateStatus(serverMsg.getSequenceNumber().orElse(-1L), clientRequestPrepare, OperationStatus.PREPARED);
                    break;
                case ServerMessage.COMMIT:
                    MessageServiceOuterClass.ClientRequest clientRequestCommit = clientRequest;
                    if (clientRequestCommit == null) clientRequestCommit = findClientRequest(serverMsg.getDigest().orElse(null));
                    operationLog.addOperationOrUpdateStatus(serverMsg.getSequenceNumber().orElse(-1L), clientRequestCommit, OperationStatus.COMMITTED);
                    break;
                case ServerMessage.CHECKPOINT:
                    operationLog.updateStatusForAllBefore(serverMsg.getSequenceNumber().orElse(-1L), OperationStatus.CHECKPOINTED);
                    break;
                default:
                    operationLog.addOperationOrUpdateStatus(serverMsg.getSequenceNumber().orElse(-1L), null, OperationStatus.NONE);
                    break;
            }
        });
    }

    public boolean appendClientRequest(Message msg) {
        return runSync(() -> {
            String digestString = MessageUtil.digestToString(MessageUtil.generateDigest(msg));
            if (livenessTimer != null) livenessTimer.startIfNotRunning();
            return serverMessageTracker.appendWithoutConsensus(ServerMessage.wrap(msg), digestString);
        });
    }

    public boolean appendClientRequest(Message msg, long seqNum) {
        return runSync(() -> {
            if ((msg instanceof MessageServiceOuterClass.ClientRequest clientRequest)) {
                logger.warn("Message is not a ClientRequest, cannot append to operation log");
                operationLog.setRequest(seqNum, clientRequest);
            }
            return appendClientRequest(msg);
        });
    }

    public Message appendAndAwaitConsensus(Message msg, Duration timeout, int required) throws TimeoutException, InterruptedException {
        return runSync(() -> serverMessageTracker.appendAndAwaitConsensus(msg, timeout, required));
    }

    public void appendViewChangeForConsensusByType(MessageServiceOuterClass.ViewChangeMessage viewChange, int required) {
        runSync(() -> {
            String messageIndex = viewChange.getDescriptorForType().getName();
            serverMessageTracker.appendWithId(ServerMessage.wrap(viewChange), messageIndex, required);
        });
    }

    public boolean checkQuorumForViewChangeByType(MessageServiceOuterClass.ViewChangeMessage viewChange) {
        return runSync(() -> {
            String messageIndex = viewChange.getDescriptorForType().getName();
            return serverMessageTracker.checkMessageQuorum(messageIndex);
        });
    }

    public boolean appendAndCheckMinQuorumForViewChange(MessageServiceOuterClass.ViewChangeMessage viewChange, int required) {
        return runSync(() -> {
            appendViewChangeForConsensusByType(viewChange, required);
            return checkQuorumForViewChangeByType(viewChange);
        });
    }

    public long getViewChangeQuorumMinView() {
        return runSync(() -> {
            List<ServerMessage> messages = serverMessageTracker.getQuorumMessages(
                    MessageServiceOuterClass.ViewChangeMessage.getDescriptor().getName());

            return messages.stream()
                    .map(msg -> (MessageServiceOuterClass.ViewChangeMessage) msg.getMessage())
                    .mapToLong(MessageServiceOuterClass.ViewChangeMessage::getViewNumber)
                    .min()
                    .orElse(0L);
        });
    }

    // every time a pre-prepare is received, check quorum for matching prepares
    // every time a prepare is received, check quorum for matching prepares and commits
    // every time a commit is received, check quorum for matching commits
    public boolean checkMessageQuorum(Message message) {
        return runSync(() -> serverMessageTracker.checkMessageQuorum(ServerMessage.wrap(message)));
    }

    public Map<String, ByteString> getQuorumSignatures(String messageType, long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.getQuorumSignatures(messageType, viewNumber, sequenceNumber));
    }

    public ByteString getQuorumDigest(String messageType, long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.getQuorumValue(messageType, viewNumber, sequenceNumber));
    }

    public List<ServerMessage> getQuorumMessages(String messageType, long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.getQuorumMessages(messageType, viewNumber, sequenceNumber));
    }

    public List<ServerMessage> getQuorumMessages(String messageIndex) {
        return runSync(() -> serverMessageTracker.getQuorumMessages(messageIndex));
    }

    public List<Message> getMessagesForType(String messageType) {
        return runSync(() -> {
            return serverMessageTracker.getMessagesByType(messageType, viewNumber).stream()
                    .map(ServerMessage::getMessage)
                    .toList();
        });
    }

    public ServerMessageTracker getServerMessageTracker() {
        return serverMessageTracker;
    }

    public MessageServiceOuterClass.ClientRequest findClientRequest(ByteString digest) {
        return runSync(() -> {
            if (digest == null) return null;
            ServerMessage message = serverMessageTracker.findByIndex(MessageUtil.digestToString(digest.toByteArray()));
            if (message == null) {
                logger.debug("No message found for digest: {}", digest);
                return null;
            }
            if (!(message.getMessage() instanceof MessageServiceOuterClass.ClientRequest clientRequest)) {
                logger.warn("Message for digest is not a ClientRequest: {}", digest);
                return null;
            }
            return clientRequest;
        });
    }

    public ServerMessage findPrePrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.findMessage(ServerMessage.PRE_PREPARE, viewNumber, sequenceNumber, primaryServerId));
    }

    public ByteString getPrePrepareDigest(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
//            logger.info("Getting PrePrepare digest for view {} seq {}", viewNumber, sequenceNumber);
            ServerMessage prePrepareMsg = findPrePrepare(viewNumber, sequenceNumber);
            if (prePrepareMsg != null) {
//                logger.info("Found PrePrepare message: {}", prePrepareMsg.toDetailedString());
                MessageServiceOuterClass.PrePrepareMessage prePrepareMessage =
                        (MessageServiceOuterClass.PrePrepareMessage) prePrepareMsg.getMessage();
//                logger.info("PrePrepare digest found: {}", prePrepareMessage.getDigest());
                return prePrepareMessage.getDigest();
            } else {
                logger.info("No PrePrepare message found for view {} seq {}", viewNumber, sequenceNumber);
                return null;
            }
        });
    }

    public boolean hasPrePrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.hasMessage(ServerMessage.PRE_PREPARE, viewNumber, sequenceNumber));
    }

    public ServerMessage findPrepare(long viewNumber, long sequenceNumber, String senderId) {
        return runSync(() -> serverMessageTracker.findMessage(ServerMessage.PREPARE, viewNumber, sequenceNumber, senderId));
    }

    public ServerMessage findAggregatedPrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
            if (hasAggregatedPrepare(viewNumber, sequenceNumber)) {
                ;
                return serverMessageTracker.findMessage(ServerMessage.PREPARE, viewNumber, sequenceNumber, collectorServerId);
            }
            return null;
        });
    }

    public boolean hasPrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.hasMessage(ServerMessage.PREPARE, viewNumber, sequenceNumber));
    }

    public boolean hasAggregatedPrepare(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
            ServerMessage message = serverMessageTracker.findMessage(ServerMessage.PREPARE, viewNumber, sequenceNumber, collectorServerId);
            if (message == null) {
                return false;
            }
            return message.isAggregated();
        });
    }

    public boolean isPrepared(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
//            logger.info("Checking if prepared for view {} seq {}", viewNumber, sequenceNumber);

            if (!hasPrePrepare(viewNumber, sequenceNumber)) {
                logger.info("No PrePrepare for view {} seq {}, cannot be prepared", viewNumber, sequenceNumber);
                return false;
            }

            boolean quorumCheck = serverMessageTracker.checkMessageQuorum(ServerMessage.PREPARE, viewNumber, sequenceNumber);
            boolean hasAggregatedPrepare = hasAggregatedPrepare(viewNumber, sequenceNumber);

            if (quorumCheck || hasAggregatedPrepare) {
                // check if pre-prepare digest matches the prepare digests
                ByteString digest = hasAggregatedPrepare ? findAggregatedPrepare(viewNumber, sequenceNumber).getDigest().orElse(null) : getQuorumDigest(ServerMessage.PREPARE, viewNumber, sequenceNumber);
//                logger.info("For view {} seq {}, prepare digest is {} and pre-prepare digest is {}", viewNumber, sequenceNumber, digest, getPrePrepareDigest(viewNumber, sequenceNumber));
//                logger.info("Are they the same? {}", Objects.equals(digest, getPrePrepareDigest(viewNumber, sequenceNumber)));
                if (digest == null) {
                    logger.info("Digest from prepares is null for view {} seq {}, cannot be prepared", viewNumber, sequenceNumber);
                    return false;
                }
                return Objects.equals(digest, getPrePrepareDigest(viewNumber, sequenceNumber));
            } else {
                logger.info("Not enough Prepare messages for view {} seq {}, cannot be prepared", viewNumber, sequenceNumber);
                return false;
            }
        });
    }

    public MessageServiceOuterClass.PreparedCertificate getPreparedCertificate(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
            MessageServiceOuterClass.PreparedCertificate.Builder certBuilder = MessageServiceOuterClass.PreparedCertificate.newBuilder();

            ServerMessage prePrepareMsg = findPrePrepare(viewNumber, sequenceNumber);
            if (prePrepareMsg == null) {
                logger.warn("No PrePrepare message found for view {} seq {}, cannot build PreparedCertificate", viewNumber, sequenceNumber);
                return null;
            }

            if (!(prePrepareMsg.getMessage() instanceof MessageServiceOuterClass.PrePrepareMessage prePrepareMessage)) {
                logger.warn("PrePrepare message is not of expected type for view {} seq {}, cannot build PreparedCertificate", viewNumber, sequenceNumber);
                return null;
            }

            certBuilder.setPrePrepareMessage(prePrepareMessage);

            if (hasAggregatedPrepare(viewNumber, sequenceNumber)) {
                ServerMessage aggregatedPrepareMsg = findAggregatedPrepare(viewNumber, sequenceNumber);
                if (aggregatedPrepareMsg == null) {
                    logger.warn("No aggregated Prepare message found for view {} seq {}, cannot build PreparedCertificate", viewNumber, sequenceNumber);
                    return null;
                }
                if (aggregatedPrepareMsg.getMessage() instanceof MessageServiceOuterClass.PrepareMessage aggregatedPrepareMessage) {
                    certBuilder.setPrepareMessage(aggregatedPrepareMessage);
                }

                return certBuilder.build();
            }

            logger.info("No aggregated Prepare found for view {} seq {}, cannot build PreparedCertificate", viewNumber, sequenceNumber);
            return null;

        });
    }

    public ServerMessage findCommit(long viewNumber, long sequenceNumber, String senderId) {
        return runSync(() -> serverMessageTracker.findMessage(ServerMessage.COMMIT, viewNumber, sequenceNumber, senderId));
    }

    public ServerMessage findAggregatedCommit(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
            if (hasAggregatedCommit(viewNumber, sequenceNumber)) {
                ;
                return serverMessageTracker.findMessage(ServerMessage.COMMIT, viewNumber, sequenceNumber, collectorServerId);
            }
            return null;
        });
    }

    public boolean hasCommit(long viewNumber, long sequenceNumber) {
        return runSync(() -> serverMessageTracker.hasMessage(ServerMessage.COMMIT, viewNumber, sequenceNumber));
    }

    public boolean hasAggregatedCommit(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
            ServerMessage message = serverMessageTracker.findMessage(ServerMessage.COMMIT, viewNumber, sequenceNumber, collectorServerId);
            if (message == null) {
                return false;
            }
            return message.isAggregated();
        });
    }

    public boolean isCommitted(long viewNumber, long sequenceNumber) {
        return runSync(() -> {
            if (!hasPrePrepare(viewNumber, sequenceNumber)) {
                return false;
            }

            if (!isPrepared(viewNumber, sequenceNumber)) {
                return false;
            }

            boolean quorumCheck = serverMessageTracker.checkMessageQuorum(ServerMessage.COMMIT, viewNumber, sequenceNumber);
            boolean hasAggregatedCommit = hasAggregatedCommit(viewNumber, sequenceNumber);

            if (quorumCheck || hasAggregatedCommit) {
                // check if pre-prepare digest matches the commit digests
                ByteString digest = hasAggregatedCommit ? findAggregatedCommit(viewNumber, sequenceNumber).getDigest().orElse(null) : getQuorumDigest(ServerMessage.COMMIT, viewNumber, sequenceNumber);
                if (digest == null) {
                    logger.info("Digest from commits is null for view {} seq {}, cannot be committed", viewNumber, sequenceNumber);
                    return false;
                }
                return Objects.equals(digest, getPrePrepareDigest(viewNumber, sequenceNumber));
            } else {
                return false;
            }
        });
    }

    public MessageServiceOuterClass.ViewChangeMessage findViewChange(long viewNumber, String senderId) {
        return runSync(() -> {
            ServerMessage message = serverMessageTracker.findMessage(
                    ServerMessage.VIEW_CHANGE,
                    viewNumber,
                    senderId);
            if (message == null) {
                return null;
            }
            return (MessageServiceOuterClass.ViewChangeMessage) message.getMessage();
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

    private void resetWatermarks() {
        latestStableCheckpointSeqNum = 0L;
        highWatermark = latestStableCheckpointSeqNum + Config.getWatermarkWindow();
    }

    // Reset everything between test sets
    public void reset() {
        runSync(() -> {
            viewNumber = INITIAL_VIEW;
            primaryServerId = computePrimaryServerId(viewNumber);
            collectorServerId = computeCollectorServerId(viewNumber);
            isFaulty = false;
            seqNum = 0L;
            stateMachineOperator.reset();
            operationLog.clear();
            replyTimestamps.clear();
            replyCache.clear();
            stableCheckpoints.clear();
            stableCheckpointSnapshots.clear();
            serverMessageTracker.clear();
            outputBuffer.clear();
            resetWatermarks();
            return null;
        });
    }
}
