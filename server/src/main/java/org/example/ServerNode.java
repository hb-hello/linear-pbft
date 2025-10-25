package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageReceiver;
import org.example.messaging.ServerMessageReceiver;
import org.example.messaging.ServerMessageSender;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ServerNode extends Node {

    private static final Logger logger = LogManager.getLogger(ServerNode.class);

    private final int MAJORITY_COUNT = 4;
    private final int OTHER_SERVER_COUNT = 6;
    private final long REQUEST_TIMEOUT_MILLIS = 1000;

    private boolean isPrimary;

    private final ServerMessageSender sender;
    private final ServerMessageReceiver receiver;

    public ServerNode(String nodeId) {
        super(nodeId);
        this.sender = new ServerMessageSender(nodeId, commLogger, auth);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);
    }

    public void setActive(boolean active) {
        sender.setActive(active);
        receiver.setActive(active);
    }

    public void handleClientRequest(MessageServiceOuterClass.ClientRequest request) {
        String clientId = request.getClientId();
        long timestamp = request.getTimestamp();

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
                .setResult(serverNumber % 2 == 1)
                .build();
        sender.sendClientReply(clientId, reply);
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
