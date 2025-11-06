package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.consensus.handlers.*;
import org.example.consensus.senders.*;
import org.example.messaging.ServerMessageReceiver;
import org.example.serverstate.OperationStatus;
import org.example.serverstate.ServerState;

public class ServerNode extends Node {

    private static final Logger logger = LogManager.getLogger(ServerNode.class);

    private final int MAJORITY_COUNT = majorityCount();
    private final int OTHER_SERVER_COUNT = 6;
    private final long REQUEST_TIMEOUT_MILLIS = 1000;

    private final ServerMessageReceiver receiver;

    private final ClientRequestSender clientRequestSender;
    private final ClientReplySender clientReplySender;
    private final PrePrepareSender prePrepareSender;
    private final PrepareSender prepareSender;
    private final CommitSender commitSender;
    private final CheckpointSender checkpointSender;
    private final StateMessageSender stateMessageSender;

    private final ClientRequestHandler clientRequestHandler;
    private final PrePrepareHandler prePrepareHandler;
    private final PrepareHandler prepareHandler;
    private final CommitHandler commitHandler;
    private final CheckpointHandler checkpointHandler;

    private final ServerState state;

    public ServerNode(String serverId) {
        super(serverId);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);

        // Create ClientReplySender first
        this.clientReplySender = new ClientReplySender(serverId, commLogger, auth);

        // Create CheckpointSender
        this.checkpointSender = new CheckpointSender(serverId, commLogger, auth);

        // Create ServerState with method references for sending replies and checkpoints
        // This breaks the circular dependency - StateMachineOperator gets callbacks that handle both concerns
        this.state = new ServerState(serverId, false, executorManager.getStateExecutor(),
                                      this::sendAndRememberReply,
                this.checkpointSender::sendCheckpoint);


        this.clientRequestSender = new ClientRequestSender(serverId, commLogger, auth);
        this.prepareSender = new PrepareSender(serverId, state, commLogger, auth);
        this.prePrepareSender = new PrePrepareSender(serverId, state, commLogger, auth, prepareSender);
        this.commitSender = new CommitSender(serverId, MAJORITY_COUNT, clientReplySender, state, commLogger, auth);
        this.stateMessageSender = new StateMessageSender(serverId, state, commLogger, auth);


        this.clientRequestHandler = new ClientRequestHandler(state, clientRequestSender, clientReplySender, prePrepareSender);
        this.prePrepareHandler = new PrePrepareHandler(state, auth, prepareSender);
        this.prepareHandler = new PrepareHandler(state, MAJORITY_COUNT, prepareSender, commitSender);
        this.commitHandler = new CommitHandler(state, MAJORITY_COUNT, commitSender, clientReplySender);
        this.checkpointHandler = new CheckpointHandler(state, MAJORITY_COUNT, checkpointSender);
    }

    public void setActive(boolean active) {
        // set active for all senders
        clientRequestSender.setActive(active);
        clientReplySender.setActive(active);
        prePrepareSender.setActive(active);
        prepareSender.setActive(active);
        commitSender.setActive(active);
        receiver.setActive(active);
    }

    public void reset() {
        logger.info("Resetting server node {}", nodeId);
        state.reset();
    }

    public static int majorityCount() {
        return 2 * MAX_FAULTY_NODES + 1;
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

    // Helper method used as callback for StateMachineOperator
    // Sends reply to client and remembers it in state
    private void sendAndRememberReply(MessageServiceOuterClass.ClientRequest request,
                                      MessageServiceOuterClass.ClientReply reply) {
        clientReplySender.sendClientReply(request, reply);
        state.rememberReply(reply);
    }

    public void shutdown() {
        logger.info("Shutting down server node {}", nodeId);
        clientRequestSender.shutdown();
        clientReplySender.shutdown();
        prePrepareSender.shutdown();
        prepareSender.shutdown();
        commitSender.shutdown();
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
