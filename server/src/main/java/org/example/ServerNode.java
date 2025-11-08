package org.example;

import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.consensus.LivenessTimer;
import org.example.consensus.handlers.*;
import org.example.consensus.senders.*;
import org.example.messaging.ServerMessage;
import org.example.messaging.ServerMessageReceiver;
import org.example.serverstate.OperationStatus;
import org.example.serverstate.ServerState;

import java.util.List;
import java.util.concurrent.ExecutorService;

public class ServerNode extends Node {

    private static final Logger logger = LogManager.getLogger(ServerNode.class);

    private final ServerMessageReceiver receiver;

    private final LivenessTimer livenessTimer;

    private final ClientRequestSender clientRequestSender;
    private final ClientReplySender clientReplySender;
    private final PrePrepareSender prePrepareSender;
    private final PrepareSender prepareSender;
    private final CommitSender commitSender;
    private final CheckpointSender checkpointSender;
    private final StateMessageSender stateMessageSender;
    private final ViewChangeSender viewChangeSender;
    private final NewViewSender newViewSender;

    private final ClientRequestHandler clientRequestHandler;
    private final PrePrepareHandler prePrepareHandler;
    private final PrepareHandler prepareHandler;
    private final CommitHandler commitHandler;
    private final CheckpointHandler checkpointHandler;
    private final ViewChangeHandler viewChangeHandler;
    private final StateMessageHandler stateMessageHandler;
    private final NewViewHandler newViewHandler;

    private final ServerState state;

    public ServerNode(String serverId) {
        super(serverId);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);

        this.livenessTimer = new LivenessTimer(Config.getServerTimeoutMillis(), this::onLivenessTimeout);

        ExecutorService networkExecutor = executorManager.getNetworkExecutor();

        // Create ClientReplySender first
        this.clientReplySender = new ClientReplySender(serverId, commLogger, auth, networkExecutor);

        // Create CheckpointSender
        this.checkpointSender = new CheckpointSender(serverId, commLogger, auth, networkExecutor);

        // Create ServerState with method references for sending replies and checkpoints
        // This breaks the circular dependency - StateMachineOperator gets callbacks that handle both concerns
        this.state = new ServerState(serverId, false, executorManager.getStateExecutor(), livenessTimer,
                                      this.clientReplySender::sendClientReply, this.checkpointSender::sendCheckpoint);

        this.clientRequestSender = new ClientRequestSender(serverId, commLogger, auth, networkExecutor);
        this.prepareSender = new PrepareSender(serverId, state, commLogger, auth, networkExecutor);
        this.prePrepareSender = new PrePrepareSender(serverId, state, commLogger, auth, prepareSender, networkExecutor);
        this.commitSender = new CommitSender(serverId, majorityCount(), clientReplySender, state, commLogger, auth, networkExecutor);
        this.stateMessageSender = new StateMessageSender(serverId, state, commLogger, auth, networkExecutor);
        this.viewChangeSender = new ViewChangeSender(serverId, majorityCount(), commLogger, auth, networkExecutor);

        this.clientRequestHandler = new ClientRequestHandler(state, clientRequestSender, clientReplySender, prePrepareSender);
        this.prePrepareHandler = new PrePrepareHandler(state, auth, prepareSender);
        this.prepareHandler = new PrepareHandler(state, majorityCount(), prepareSender, commitSender);
        this.commitHandler = new CommitHandler(state, majorityCount(), commitSender, clientReplySender);
        this.checkpointHandler = new CheckpointHandler(state, majorityCount(), checkpointSender);
        this.stateMessageHandler = new StateMessageHandler(state);
        this.newViewHandler = new NewViewHandler(state, auth, livenessTimer, prepareSender);

        this.newViewSender = new NewViewSender(serverId, majorityCount(), commLogger, auth, checkpointHandler, networkExecutor);
        this.viewChangeHandler = new ViewChangeHandler(state, auth, majorityCount(), livenessTimer, viewChangeSender, newViewSender);
    }

    public void setActive(boolean active) {
        // set active for all senders
        clientRequestSender.setActive(active);
        clientReplySender.setActive(active);
        prePrepareSender.setActive(active);
        prepareSender.setActive(active);
        commitSender.setActive(active);
        checkpointSender.setActive(active);
        stateMessageSender.setActive(active);
        viewChangeSender.setActive(active);
        newViewSender.setActive(active);
        receiver.setActive(active);
    }

    // Timer callbacks

    public void onLivenessTimeout() {
        logger.warn("View change timeout occurred in view {}, re-broadcasting view change message", state.getViewNumber());

        livenessTimer.stop();

        if (state.getLastExecutedView() < state.getViewNumber()) {
            logger.info("Extending liveness timeout for view {}", state.getViewNumber() + 1);
            livenessTimer.addToTimeoutMillis(Config.getServerTimeoutMillis());
        } else livenessTimer.setTimeoutMillis(Config.getServerTimeoutMillis());

        state.setViewChangeInProgress(true);
        viewChangeSender.broadcastViewChange(state);
    }

    public void reset() {
        logger.info("Resetting server node {}", nodeId);
        state.reset();
        livenessTimer.stop();
        MaliceInjector.init(null);
        commLogger.reset();
    }

    public static int majorityCount() {
        return 2 * MAX_FAULTY_NODES + 1;
    }

    public static int majorityCountForViewChange() {
        return MAX_FAULTY_NODES + 1;
    }

    public void handleClientRequest(MessageServiceOuterClass.ClientRequest request) {
        executorManager.submitMessageProcessing(() -> clientRequestHandler.handle(request));
    }

    public void handlePrePrepare(MessageServiceOuterClass.PrePrepareRequest prePrepareRequest) {
        executorManager.submitMessageProcessing(() -> {
            prePrepareHandler.handle(prePrepareRequest);
        });
    }

    public void handlePrepare(MessageServiceOuterClass.PrepareMessage prepareMessage) {
        executorManager.submitMessageProcessing(() -> {
            prepareHandler.handle(prepareMessage);
        });
    }

    public void handleCommit(MessageServiceOuterClass.CommitMessage commitMessage) {
        executorManager.submitMessageProcessing(() -> {
            commitHandler.handle(commitMessage);
        });
    }

    public void handleCheckpoint(MessageServiceOuterClass.CheckpointMessage checkpointMessage) {
        executorManager.submitMessageProcessing(() -> {
            checkpointHandler.handle(checkpointMessage);
        });
    }

    public void handleStateRequest(String targetServerId) {
        executorManager.submitMessageProcessing(() -> {
            stateMessageSender.sendStateMessage(targetServerId);
        });
    }

    public void handleStateMessage(MessageServiceOuterClass.StateMessage stateMessage) {
        executorManager.submitMessageProcessing(() -> {
            stateMessageHandler.handle(stateMessage);
        });
    }

    public void handleViewChange(MessageServiceOuterClass.ViewChangeMessage viewChangeMessage) {
        executorManager.submitMessageProcessing(() -> {
            viewChangeHandler.handle(viewChangeMessage);
        });
    }

    public void handleNewView(MessageServiceOuterClass.NewViewMessage newViewMessage) {
        executorManager.submitMessageProcessing(() -> {
            newViewHandler.handle(newViewMessage);
        });
    }

    public String getOperation(long sequenceNumber) {
        return state.getOperation(sequenceNumber).toString();
    }

    public String printOperationLog() {
        return state.getOperationLog().toString() + "\n" + state.printIndexedServerMessages();
    }

    public OperationStatus getOperationStatus(long sequenceNumber) {
        return state.getOperationStatus(sequenceNumber);
    }

    public String getDB() {
        return state.printSnapshotStateMachine();
    }

    public List<Message> getNewViews() {
        return state.getMessagesForType(ServerMessage.NEW_VIEW);
    }

    public void shutdown() {
        logger.info("Shutting down server node {}", nodeId);
        clientRequestSender.shutdown();
        clientReplySender.shutdown();
        prePrepareSender.shutdown();
        prepareSender.shutdown();
        commitSender.shutdown();
        checkpointSender.shutdown();
        stateMessageSender.shutdown();
        viewChangeSender.shutdown();
        newViewSender.shutdown();
        livenessTimer.shutdown();
        receiver.shutdown();
        super.shutdown();
        logger.info("Server node {} shut down complete", nodeId);
    }

    static void main(String[] args) {

        if (args.length != 1) {
            System.err.println("Node ID argument required");
            System.exit(1);
        }

        Config.initialize();
        MaliceInjector.init(null);

        String nodeId = args[0];
        ServerNode serverNode = new ServerNode(nodeId);

        // Register shutdown hook BEFORE starting
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook triggered");
            serverNode.shutdown();
        }, nodeId + "-shutdown-hook"));

        serverNode.start(serverNode.receiver);
    }
}
