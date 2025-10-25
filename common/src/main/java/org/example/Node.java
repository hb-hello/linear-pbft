package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.consensus.ConsensusMessageTracker;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageReceiver;
import org.example.messaging.MessageSender;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Node {

    private static final Logger logger = LogManager.getLogger(Node.class);

    protected final String nodeId;

    protected final CommunicationLogger commLogger;

    protected final MessageAuthenticator auth;
    protected MessageSender sender;
    protected MessageReceiver receiver;

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
     * Starts the gRPC listener without blocking. Returns a Future that completes when the
     * listener stops. Safe to call multiple times; subsequent calls return the same Future.
     */
    public Future<?> startAsync() {
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
    public void start() {
        Future<?> future = startAsync();
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

    public void shutdown() {
        logger.info("Shutting down node {}", nodeId);
        receiver.shutdown();
        sender.shutdown();
        executorManager.shutdown();

        logger.info("Node {} shutdown complete", nodeId);
    }
}
