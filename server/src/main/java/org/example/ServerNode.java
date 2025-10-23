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

    private final ServerMessageSender sender;
    private final ServerMessageReceiver receiver;

    private final ExecutorService listenerExecutor;

    public ServerNode(String nodeId) {
        super(nodeId);
        this.sender = new ServerMessageSender(nodeId, commLogger, auth);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);

        this.listenerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    }

    public void setActive(boolean active) {
        sender.setActive(active);
        receiver.setActive(active);
    }

    public void start() {
        Future<?> listenerFuture = executorManager.submitListeningTask(receiver::startListening);

        // Block main thread
        try {
            listenerFuture.get(); // Blocks until listener stops
        } catch (InterruptedException e) {
            logger.info("Main thread interrupted - initiating shutdown");
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.error("Listener thread failed: {}", e.getCause().getMessage(), e);
            throw new RuntimeException("Listener failed", e.getCause());
        }
    }

    public void shutdown() {
        logger.info("Shutting down node {}", nodeId);
        receiver.shutdown();
        sender.shutdown();
        executorManager.shutdown();

        logger.info("Node {} shutdown complete", nodeId);
    }

    public static void main(String[] args) {

        if (args.length != 1) {
            System.err.println("Usage: java Node <nodeId>");
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

        serverNode.start();
    }

}
