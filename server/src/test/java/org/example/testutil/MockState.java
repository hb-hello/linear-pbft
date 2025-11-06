package org.example.testutil;

import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.serverstate.ServerState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Mock ServerState for testing.
 * Provides a convenient factory for creating ServerState instances with sensible defaults
 * and manages the executor lifecycle.
 */
public class MockState {

    private static ExecutorService sharedStateExec;

    /**
     * Initialize the shared state executor.
     * Should be called once in @BeforeAll.
     */
    public static void initializeExecutor() {
        if (sharedStateExec == null) {
            Config.initialize("src/test/resources/config.properties");
            sharedStateExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("-state-manager-0");  // Must start with "-state-manager" for onStateThread() check
                return t;
            });
        }
    }

    /**
     * Shutdown the shared state executor.
     * Should be called once in @AfterAll.
     */
    public static void shutdownExecutor() {
        if (sharedStateExec != null) {
            sharedStateExec.shutdownNow();
            sharedStateExec = null;
        }
    }

    /**
     * Create a new ServerState with default test configuration.
     * Uses no-op callbacks for reply and checkpoint.
     *
     * @param nodeId The server node ID (e.g., "n1")
     * @return A new ServerState instance
     */
    public static ServerState create(String nodeId) {
        return create(nodeId, false);
    }

    /**
     * Create a new ServerState with specified node ID and primary flag.
     * Uses no-op callbacks for reply and checkpoint.
     *
     * @param nodeId The server node ID (e.g., "n1")
     * @param isFaulty Whether this node is faulty
     * @return A new ServerState instance
     */
    public static ServerState create(String nodeId, boolean isFaulty) {
        if (sharedStateExec == null) {
            throw new IllegalStateException("Executor not initialized. Call initializeExecutor() first.");
        }
        BiConsumer<MessageServiceOuterClass.ClientRequest, MessageServiceOuterClass.ClientReply> noOpReply =
                (request, reply) -> {};
        BiConsumer<ServerState, Long> noOpCheckpoint = (s, seqNum) -> {};

        return new ServerState(nodeId, isFaulty, sharedStateExec, null, noOpReply, noOpCheckpoint);
    }
}

