package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.consensus.handlers.PrePrepareHandler;
import org.example.consensus.senders.PrePrepareSender;
import org.example.messaging.ServerMessageReceiver;
import org.example.messaging.ServerMessageSender;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

public class ServerNode extends Node {

    private static final Logger logger = LogManager.getLogger(ServerNode.class);

    private final int MAJORITY_COUNT = 4;
    private final int OTHER_SERVER_COUNT = 6;
    private final long REQUEST_TIMEOUT_MILLIS = 1000;

    private final ServerMessageSender sender;
    private final ServerMessageReceiver receiver;

    private final PrePrepareSender prePrepareSender;

    private final PrePrepareHandler prePrepareHandler;

    private final ServerState state;

    public ServerNode(String serverId) {
        super(serverId);
        this.sender = new ServerMessageSender(serverId, commLogger, auth);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);
        this.state = new ServerState(serverId, false, executorManager.getStateExecutor());

        this.prePrepareSender = new PrePrepareSender(serverId, state, commLogger, auth);

        this.prePrepareHandler = new PrePrepareHandler(state, auth);
    }

    public void setActive(boolean active) {
        sender.setActive(active);
        receiver.setActive(active);
    }

    public void handleClientRequest(MessageServiceOuterClass.ClientRequest request) {

        executorManager.submitMessageProcessing(() -> {
            String clientId = request.getClientId();
            long timestamp = request.getTimestamp();
            MessageServiceOuterClass.Operation operation = request.getOperation();
            logger.info("Handling ClientRequest from client {}: timestamp {}, operation {}",
                    clientId, timestamp, operation.getOpCase());

            executorManager.submitStateTransition(() -> {
                logger.info("Entering state transition for ClientRequest from client {}: timestamp {}",
                        clientId, timestamp);
                if (timestamp <= state.lastReplyTimestamp(clientId)) {
                    logger.info("Ignoring stale ClientRequest from client {}: timestamp {}", clientId, timestamp);
                    // TODO: resend cached reply
                    return;
                }

                if (!state.appendServerMessage(ServerMessage.wrap(request))) {
                    logger.info("Duplicate ClientRequest from client {}: timestamp {}, ignoring",
                            clientId, timestamp);
                    return;
                }
                // TODO: refresh liveness timer

                if (!state.isPrimary()) {
                    String primaryServerId = state.getPrimaryServerId();
                    logger.info("Forwarding ClientRequest from client {} to primary server {}", clientId, primaryServerId);
                    sender.forwardClientRequest(primaryServerId, request);
                    return;
                }

                // initiate PBFT protocol
                prePrepareSender.attemptPrePrepare(request);
            });


            // await consensus
            // execute operation in request
            // send ClientReply

//            int serverNumber = Integer.parseInt(nodeId.substring(1));
//            logger.info("Moved out of state transition for ClientRequest from client {}: timestamp {}",
//                    clientId, timestamp);
            MessageServiceOuterClass.ClientReply reply = MessageServiceOuterClass.ClientReply.newBuilder()
                    .setViewNumber(1L)
                    .setTimestamp(timestamp)
                    .setClientId(clientId)
                    .setServerId(nodeId)
                    .setResult(MessageServiceOuterClass.OperationResult.newBuilder().setResult(true).build())
                    .build();
            sender.sendClientReply(clientId, reply);
        });


    }

    public void handlePrePrepare(MessageServiceOuterClass.PrePrepareRequest prePrepareRequest) {
        executorManager.submitMessageProcessing(() -> {
            prePrepareHandler.handle(prePrepareRequest);
        });
    }

    public static void main(String[] args) {

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
            serverNode.shutdown(serverNode.sender, serverNode.receiver);
        }, nodeId + "-shutdown-hook"));

        serverNode.start(serverNode.receiver);
    }

}
