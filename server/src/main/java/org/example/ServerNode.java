package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.consensus.handlers.*;
import org.example.consensus.senders.*;
import org.example.messaging.ServerMessageReceiver;
import org.example.serverstate.ServerState;

public class ServerNode extends Node {

    private static final Logger logger = LogManager.getLogger(ServerNode.class);

    private final int MAJORITY_COUNT = 5;
    private final int OTHER_SERVER_COUNT = 6;
    private final long REQUEST_TIMEOUT_MILLIS = 1000;

    private final ServerMessageReceiver receiver;

    private final ClientRequestSender clientRequestSender;
    private final ClientReplySender clientReplySender;
    private final PrePrepareSender prePrepareSender;
    private final PrepareSender prepareSender;
    private final CommitSender commitSender;

    private final ClientRequestHandler clientRequestHandler;
    private final PrePrepareHandler prePrepareHandler;
    private final PrepareHandler prepareHandler;
    private final CommitHandler commitHandler;

    private final ServerState state;

    public ServerNode(String serverId) {
        super(serverId);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);

        // Create ClientReplySender first
        this.clientReplySender = new ClientReplySender(serverId, commLogger, auth);

        // Create ServerState with a method reference that both sends reply and remembers it
        // This breaks the circular dependency - StateMachineOperator gets a callback that handles both concerns
        this.state = new ServerState(serverId, false, executorManager.getStateExecutor(),
                                      this::sendAndRememberReply);

        this.clientRequestSender = new ClientRequestSender(serverId, commLogger, auth);
        this.prePrepareSender = new PrePrepareSender(serverId, state, commLogger, auth);
        this.prepareSender = new PrepareSender(serverId, state, commLogger, auth);
        this.commitSender = new CommitSender(serverId, MAJORITY_COUNT, clientReplySender, state, commLogger, auth);


        this.clientRequestHandler = new ClientRequestHandler(state, clientRequestSender, clientReplySender, prePrepareSender);
        this.prePrepareHandler = new PrePrepareHandler(state, auth, prepareSender);
        this.prepareHandler = new PrepareHandler(state, MAJORITY_COUNT, prepareSender, commitSender);
        this.commitHandler = new CommitHandler(state, MAJORITY_COUNT, commitSender, clientReplySender);
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

    public void handleClientRequest(MessageServiceOuterClass.ClientRequest request) {

        executorManager.submitMessageProcessing(() -> clientRequestHandler.handle(request));

//        String clientId = request.getClientId();
//        long timestamp = request.getTimestamp();
//        MessageServiceOuterClass.Operation operation = request.getOperation();
//
//        MessageServiceOuterClass.OperationResult result = state.executeOperation(operation);
//        logger.info("Executed operation for ClientRequest from client {}: timestamp {}, result {}",
//                clientId, timestamp, result);
//
//        executorManager.submitMessageProcessing(() -> {
//            clientReplySender.sendClientReply(request, result);
//        });
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
