package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.messaging.ServerMessageReceiver;
import org.example.messaging.ServerMessageSender;
import org.example.serverstate.ServerState;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ServerNode extends Node {

    private static final Logger logger = LogManager.getLogger(ServerNode.class);

    private final int MAJORITY_COUNT = 4;
    private final int OTHER_SERVER_COUNT = 6;
    private final long REQUEST_TIMEOUT_MILLIS = 1000;

    private final ServerMessageSender sender;
    private final ServerMessageReceiver receiver;

    private final ServerState state;

    public ServerNode(String nodeId) {
        super(nodeId);
        this.sender = new ServerMessageSender(nodeId, commLogger, auth);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);
        this.state = new ServerState(nodeId, false, executorManager.getStateExecutor());
    }

    public void setActive(boolean active) {
        sender.setActive(active);
        receiver.setActive(active);
    }

    private byte[] generateRequestDigest(MessageServiceOuterClass.ClientRequest request) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(request.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            logger.error("MD5 algorithm not available", e);
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    public void handleClientRequest(MessageServiceOuterClass.ClientRequest request) {
        String clientId = request.getClientId();
        long timestamp = request.getTimestamp();
        MessageServiceOuterClass.Operation operation = request.getOperation();

        executorManager.submitStateTransition(() -> {
            if (timestamp <= state.lastReplyTimestamp(clientId)) {
                logger.info("Ignoring stale ClientRequest from client {}: timestamp {}", clientId, timestamp);
                // TODO: resend cached reply
                return;
            }

            state.appendServerMessage(request);
            // refresh liveness timer?

            if (!state.isPrimary()) {
                String primaryServerId = state.getPrimaryServerId();
                logger.info("Forwarding ClientRequest from client {} to primary server {}", clientId, primaryServerId);
                sender.forwardClientRequest(primaryServerId, request);
                return;
            }

            attemptPrePrepare(request);
        });

        // initiate PBFT protocol
        // await consensus
        // execute operation in request
        // send ClientReply

        int serverNumber = Integer.parseInt(nodeId.substring(1));
        MessageServiceOuterClass.ClientReply reply = MessageServiceOuterClass.ClientReply.newBuilder()
                .setViewNumber(1L)
                .setTimestamp(timestamp)
                .setClientId(clientId)
                .setServerId(nodeId)
//                .setResult(serverNumber % 2 == 1)
                .build();
        sender.sendClientReply(clientId, reply);
    }

    private void attemptPrePrepare(MessageServiceOuterClass.ClientRequest request) {

        // Compute digest of the client request
        byte[] digest = generateRequestDigest(request);

        // Create PrePrepare message with digest
        MessageServiceOuterClass.PrePrepareMessage prePrepareMessage = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(state.getViewNumber())
                .setSequenceNumber(1L)
                .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                .build();

        // Include raw request bytes in the PrePrepareRequest
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMessage)
                .setRequest(com.google.protobuf.ByteString.copyFrom(request.toByteArray()))
                .build();

        // Broadcast PrePrepare to other servers
        for (int i = 1; i <= OTHER_SERVER_COUNT; i++) {
            String targetServerId = "S" + i;
            if (!targetServerId.equals(nodeId)) {
                sender.sendPrePrepare(targetServerId, prePrepareRequest);
            }
        }
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
