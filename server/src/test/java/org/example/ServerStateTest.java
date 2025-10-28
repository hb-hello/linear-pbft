package org.example;

import org.example.config.Config;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ServerStateTest {

    private static ExecutorService stateExec;

    @BeforeAll
    static void setup() {
        // Initialize configuration so Node.computePrimaryServerId() works
        Config.initialize("src/test/resources/config.properties");
        // Use a single-threaded executor whose thread name starts with "state-manager"
        // to satisfy ServerState.onStateThread() and avoid re-entrancy deadlocks in tests.
        stateExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("state-manager-0");
            return t;
        });
    }

    @AfterAll
    static void tearDown() {
        if (stateExec != null) {
            stateExec.shutdownNow();
        }
    }

    private ServerState newState(String serverId) {
        return new ServerState(serverId, false, stateExec);
    }

    @Test
    void testSetViewAndPrimary_updatesHeaderAndRole() {
        ServerState state = newState("n1");

        long newView = 5L;
        state.setViewAndPrimary(newView);

        ServerState.Header header = state.snapshotHeader();
        assertEquals(newView, header.view(), "View number should be updated");
        assertEquals(Node.computePrimaryServerId(newView), header.primary(), "Primary server id should match computed value");
        assertFalse(header.primaryFlag(), "n1 should not be primary for view 5 with default config");
    }

    @Test
    void testSetViewAndPrimary_primaryFlagTrueWhenServerIsPrimary() {
        ServerState state = newState("n1");

        long newView = 1L; // With default mapping, floorMod(1, serverCount)=1 -> primary "n1"
        state.setViewAndPrimary(newView);

        ServerState.Header header = state.snapshotHeader();
        assertEquals("n1", header.primary(), "Primary should be n1 for view 1");
        assertTrue(header.primaryFlag(), "Primary flag should be true when this server is primary");
    }

    @Test
    void testSetViewAndPrimary_handlesNegativeViews() {
        ServerState state = newState("n1");

        long newView = -1L;
        state.setViewAndPrimary(newView);

        ServerState.Header header = state.snapshotHeader();
        assertEquals(newView, header.view(), "Negative view should be stored as-is");
        assertEquals(Node.computePrimaryServerId(newView), header.primary(), "Primary should be computed using floorMod for negative views");
        assertFalse(header.primaryFlag(), "n1 should not be primary for view -1 with default config");
    }

    @Test
    void testSetFaulty_updatesFaultyFlag() {
        ServerState state = newState("n1");

        // Initially not faulty
        assertFalse(state.snapshotHeader().faulty(), "Initial faulty flag should be false");

        // Set to true
        state.setFaulty(true);
        assertTrue(state.snapshotHeader().faulty(), "Faulty flag should be true after setFaulty(true)");

        // Set back to false
        state.setFaulty(false);
        assertFalse(state.snapshotHeader().faulty(), "Faulty flag should be false after setFaulty(false)");
    }

    @Test
    void testReset_restoresInitialState() {
        ServerState state = newState("n1");

        // Mutate various pieces of state
        state.setFaulty(true);
        state.setViewAndPrimary(3L);
        long s1 = state.nextSeq();
        long s2 = state.nextSeq();
        assertTrue(s2 > s1, "Sequence should increase");
        state.markExecutedUpTo(42L);
        state.applyTransfer("A", "B", 5.0);
        state.rememberReply("client1", 100L, "ok");
        state.appendServerMessage("msg");
        state.enqueueOutbound("out");
        assertEquals(1, state.outboundQueue().size(), "Outbound queue should have an item before reset");

        // Reset
        state.reset();

        // Verify header reset
        ServerState.Header header = state.snapshotHeader();
        assertEquals(0L, header.view(), "View should reset to 0");
        assertEquals(Node.computePrimaryServerId(0L), header.primary(), "Primary should be recomputed for view 0");
        assertFalse(header.primaryFlag(), "n1 should not be primary at view 0 with default config");
        assertFalse(header.faulty(), "Faulty flag should reset to false");
        assertEquals(0L, header.seq(), "Sequence should reset to 0");
        assertEquals(0L, header.lastExec(), "Last executed seq should reset to 0");

        // Verify data structures cleared
        assertTrue(state.snapshotBalances().isEmpty(), "Balances should be cleared");
        assertNull(state.lastReplyTimestamp("client1"), "Reply timestamps should be cleared");
        assertTrue(state.outboundQueue().isEmpty(), "Outbound queue should be cleared");
    }
}
