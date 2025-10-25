package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.consensus.ConsensusMessageTracker;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageReceiver;
import org.example.messaging.MessageSender;
import org.example.config.Config;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Node {

    private static final Logger logger = LogManager.getLogger(Node.class);

    protected final String nodeId;

    protected final CommunicationLogger commLogger;

    protected final MessageAuthenticator auth;

    protected final ExecutorManager executorManager;

    protected final ConsensusMessageTracker<String> messageTracker;

    private volatile Future<?> listenerFuture;

    protected Node(String nodeId) {
        this.nodeId = nodeId;
        this.commLogger = new CommunicationLogger();
        this.auth = new MessageAuthenticator(nodeId);

        this.executorManager = new ExecutorManager(10);
        this.messageTracker = new ConsensusMessageTracker<>();
    }

    public String getNodeId() {
        return nodeId;
    }

    /**
     * Compose a stable request id from client id and timestamp.
     * Kept generic so both client and server code can rely on identical formatting.
     */
    protected static String requestIdFor(String clientId, long timestamp) {
        return clientId + ":" + timestamp;
    }

    /**
     * Current server count from configuration.
     */
    protected int getServerCount() {
        return Config.getServerIds().size();
    }

    /**
     * Simple majority threshold (n/2 + 1).
     */
    protected int majorityCount() {
        int n = getServerCount();
        return (n / 2) + 1;
    }

    /**
     * Client request timeout sourced from configuration.
     */
    protected long getClientRequestTimeoutMillis() {
        return Config.getClientTimeoutMillis();
    }

    /**
     * Starts the gRPC listener without blocking. Returns a Future that completes when the
     * listener stops. Safe to call multiple times; subsequent calls return the same Future.
     */
    public Future<?> startAsync(MessageReceiver receiver) {
        if (listenerFuture == null) {
            synchronized (this) {
                if (listenerFuture == null) {
                    listenerFuture = executorManager.submitListeningTask(receiver::startListening);
                }
            }
        }
        logger.info("Submitted listener start task for node {}", nodeId);
        return listenerFuture;
    }

    /**
     * Starts the gRPC listener and blocks the current thread until it stops.
     */
    public void start(MessageReceiver receiver) {
        Future<?> future = startAsync(receiver);
        try {
            future.get(); // Blocks until listener stops
        } catch (InterruptedException e) {
            logger.info("Main thread interrupted - initiating shutdown");
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.error("Listener thread failed: {}", e.getCause().getMessage(), e);
            throw new RuntimeException("Listener failed", e.getCause());
        }
    }

    public void shutdown(MessageSender sender, MessageReceiver receiver) {
        logger.info("Shutting down node {}", nodeId);
        receiver.shutdown();
        sender.shutdown();
        executorManager.shutdown();

        logger.info("Node {} shutdown complete", nodeId);
    }
}
