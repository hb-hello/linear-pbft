package org.example;

import org.example.crypto.MessageAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ServerNode {

    private static final Logger logger = LoggerFactory.getLogger(ServerNode.class);

    private final int MAJORITY_COUNT = 4;
    private final int OTHER_SERVER_COUNT = 6;
    private final long REQUEST_TIMEOUT_MILLIS = 1000;

    private final String nodeId;

    private final CommunicationLogger commLogger;

    private final MessageAuthenticator auth;
    private final ServerMessageSender sender;
    private final MessageReceiver receiver;

    private final ExecutorService listenerExecutor;
    private final ExecutorManager executorManager;

    public ServerNode(String nodeId) {
        this.nodeId = nodeId;
        this.commLogger = new CommunicationLogger();

        this.auth = new MessageAuthenticator(nodeId);
        this.sender = new ServerMessageSender(nodeId, commLogger, auth);
        this.receiver = new ServerMessageReceiver(this, commLogger, auth);

        this.listenerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        this.executorManager = new ExecutorManager(OTHER_SERVER_COUNT);
    }

    public void setActive(boolean active) {
        sender.setActive(active);
        receiver.setActive(active);
    }

    public String getNodeId() {
        return nodeId;
    }

    public void start() {
        Future<?> listenerFuture = listenerExecutor.submit(receiver::startListening);

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

        // Shutdown listener executor
        listenerExecutor.shutdown();
        try {
            if (!listenerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                listenerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            listenerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

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
