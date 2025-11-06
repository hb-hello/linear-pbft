package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageReceiver;
import org.example.config.Config;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.example.config.Config.getServerCount;
import static org.example.config.Config.getServerIdForNumber;

public class Node {

    private static final Logger logger = LogManager.getLogger(Node.class);

    protected static int MAX_FAULTY_NODES = 2;

    protected final String nodeId;

    protected final CommunicationLogger commLogger;

    protected final MessageAuthenticator auth;

    protected final ExecutorManager executorManager;

    private volatile Future<?> listenerFuture;

    protected Node(String nodeId) {
        this.nodeId = nodeId;
        this.commLogger = new CommunicationLogger();
        this.auth = new MessageAuthenticator(nodeId);

        this.executorManager = new ExecutorManager(10);
    }

    public String getNodeId() {
        return nodeId;
    }

    /**
     * Compute primary server id for a given view using a stable ordering of configured servers.
     * Uses floorMod to handle negative views gracefully.
     */
    public static String computePrimaryServerId(long viewNumber) {
        int serverNumber = (int) Math.floorMod(viewNumber, getServerCount());
        serverNumber = serverNumber <= 0 ? serverNumber + getServerCount() : serverNumber;
        return getServerIdForNumber(serverNumber);
    }

    protected int majorityCountForClient() {
        return MAX_FAULTY_NODES + 1;
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

    public void shutdown() {
        executorManager.shutdown();
    }
}
