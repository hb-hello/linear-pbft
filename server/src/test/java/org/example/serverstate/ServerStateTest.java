package org.example.serverstate;

import org.example.MessageServiceOuterClass;
import org.example.Node;
import org.example.config.Config;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ServerStateTest {

    private static ExecutorService stateExec;

    @BeforeAll
    static void setup() {
        // Initialize configuration so Node.computePrimaryServerId() works and balances are loaded
        Config.initialize("src/test/resources/config.properties");
        // Use a single-threaded executor whose thread name starts with "state-manager"
        // to satisfy ServerState.onStateThread() and avoid re-entrancy deadlocks in tests.
        stateExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("-state-manager-0");
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

    private MessageServiceOuterClass.Operation transferOp(String from, String to, double amount) {
        return MessageServiceOuterClass.Operation.newBuilder()
                .setTransfer(MessageServiceOuterClass.Transfer.newBuilder()
                        .setSender(from)
                        .setReceiver(to)
                        .setAmount(amount)
                        .build())
                .build();
    }

    private MessageServiceOuterClass.Operation balanceOp(String account) {
        return MessageServiceOuterClass.Operation.newBuilder()
                .setBalanceRequest(MessageServiceOuterClass.BalanceRequest.newBuilder()
                        .setAccountId(account)
                        .build())
                .build();
    }

    @Test
    void testSetViewAndPrimary_updatesHeaderAndRole() {
        ServerState state = newState("n1");

        long newView = 5L;
        state.setViewAndPrimary(newView);

        ServerState.Header header = state.snapshotHeader();
        assertEquals(newView, header.view(), "View number should be updated");
        Assertions.assertEquals(Node.computePrimaryServerId(newView), header.primary(), "Primary server id should match computed value");
        assertFalse(state.isPrimary(), "n1 should not be primary for view 5 with default config");
    }

    @Test
    void testSetViewAndPrimary_primaryFlagTrueWhenServerIsPrimary() {
        ServerState state = newState("n1");

        long newView = 1L; // With default mapping, floorMod(1, serverCount)=1 -> primary "n1"
        state.setViewAndPrimary(newView);

        ServerState.Header header = state.snapshotHeader();
        assertEquals("n1", header.primary(), "Primary should be n1 for view 1");
        assertTrue(state.isPrimary(), "Primary flag should be true when this server is primary");
    }

    @Test
    void testSetViewAndPrimary_handlesNegativeViews() {
        ServerState state = newState("n1");

        long newView = -1L;
        state.setViewAndPrimary(newView);

        ServerState.Header header = state.snapshotHeader();
        assertEquals(newView, header.view(), "Negative view should be stored as-is");
        assertEquals(Node.computePrimaryServerId(newView), header.primary(), "Primary should be computed using floorMod for negative views");
        assertFalse(state.isPrimary(), "n1 should not be primary for view -1 with default config");
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
        MessageServiceOuterClass.OperationResult t = state.executeOperation(transferOp("A", "B", 2.0));
        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.RESULT, t.getOpCase());
        assertTrue(t.getResult());
        state.rememberReply("client1", 100L, "ok");
        // Create a dummy ClientRequest to wrap
        MessageServiceOuterClass.ClientRequest dummyRequest = MessageServiceOuterClass.ClientRequest.newBuilder()
                .setClientId("test")
                .setTimestamp(123L)
                .build();
        state.appendServerMessage(dummyRequest);
        state.enqueueOutbound("out");
        assertEquals(1, state.outboundQueue().size(), "Outbound queue should have an item before reset");

        // Reset
        state.reset();

        // Verify header reset
        ServerState.Header header = state.snapshotHeader();
        assertEquals(1L, header.view(), "View should reset to 1");
        assertEquals(Node.computePrimaryServerId(1L), header.primary(), "Primary should be recomputed for view 1");
        assertTrue(state.isPrimary(), "n1 should be primary at view 1 with default config");
        assertFalse(header.faulty(), "Faulty flag should reset to false");
        assertEquals(0L, header.seq(), "Sequence should reset to 0");
        assertEquals(0L, header.lastExec(), "Last executed seq should reset to 0");

        // Verify data structures cleared/restored
        @SuppressWarnings("unchecked")
        Map<String, Double> balances = (Map<String, Double>) state.snapshotStateMachine();
        assertFalse(balances.isEmpty(), "Balances should be restored to initial values, not empty");
        assertEquals(Config.getClientBalances().size(), balances.size(), "Should have all initial accounts");
        // Verify balances match initial config
        for (Map.Entry<String, Double> entry : Config.getClientBalances().entrySet()) {
            assertEquals(entry.getValue(), balances.get(entry.getKey()), 1e-9,
                "Account " + entry.getKey() + " should have initial balance");
        }
        assertEquals(0L, state.lastReplyTimestamp("client1"), "Reply timestamps should be cleared");
        assertTrue(state.outboundQueue().isEmpty(), "Outbound queue should be cleared");
    }

    @Test
    void testNextSeq_incrementsMonotonically() {
        ServerState state = newState("n1");
        assertEquals(1L, state.nextSeq(), "First nextSeq should return 1");
        assertEquals(2L, state.nextSeq(), "Second nextSeq should return 2");
        assertEquals(3L, state.nextSeq(), "Third nextSeq should return 3");
        assertEquals(3L, state.snapshotHeader().seq(), "Header seq should reflect latest sequence value");
    }

    @Test
    void testMarkExecutedUpTo_isMonotonicMax() {
        ServerState state = newState("n1");
        assertEquals(0L, state.snapshotHeader().lastExec(), "Initial lastExec is 0");

        state.markExecutedUpTo(10L);
        assertEquals(10L, state.snapshotHeader().lastExec(), "lastExec should become 10");

        state.markExecutedUpTo(7L);
        assertEquals(10L, state.snapshotHeader().lastExec(), "lastExec should not decrease on smaller input");

        state.markExecutedUpTo(15L);
        assertEquals(15L, state.snapshotHeader().lastExec(), "lastExec should increase to 15 on larger input");
    }

    @Test
    void testLastReplyTimestamp_updatesOnlyOnNewer() {
        ServerState state = newState("n1");
        assertEquals(0L, state.lastReplyTimestamp("client1"), "No reply yet for client");

        state.rememberReply("client1", 100L, "ok1");
        assertEquals(100L, state.lastReplyTimestamp("client1"), "Last timestamp should be 100");

        state.rememberReply("client1", 50L, "old");
        assertEquals(100L, state.lastReplyTimestamp("client1"), "Older timestamp should not overwrite");

        state.rememberReply("client1", 150L, "ok2");
        assertEquals(150L, state.lastReplyTimestamp("client1"), "Newer timestamp should overwrite");
    }

    @Test
    void testExecuteOperation_transferAndBalance() {
        ServerState state = newState("n1");

        // Transfer 5 from A to B
        MessageServiceOuterClass.OperationResult res = state.executeOperation(transferOp("A", "B", 5.0));
        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.RESULT, res.getOpCase());
        assertTrue(res.getResult());

        // Compute expected from configured initial balances
        Map<String, Double> init = Config.getClientBalances();
        double a0 = init.get("A");
        double b0 = init.get("B");

        // Check balances via balance requests
        MessageServiceOuterClass.OperationResult balB = state.executeOperation(balanceOp("B"));
        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.BALANCE, balB.getOpCase());
        assertEquals(b0 + 5.0, balB.getBalance(), 1e-9);

        MessageServiceOuterClass.OperationResult balA = state.executeOperation(balanceOp("A"));
        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.BALANCE, balA.getOpCase());
        assertEquals(a0 - 5.0, balA.getBalance(), 1e-9);
    }

    @Test
    void testExecuteOperation_missingAccounts_throw() {
        ServerState state = newState("n1");

        // Unknown sender
        assertThrows(IllegalArgumentException.class, () -> state.executeOperation(transferOp("Z", "B", 1.0)));
        // Unknown receiver
        assertThrows(IllegalArgumentException.class, () -> state.executeOperation(transferOp("A", "Z", 1.0)));
        // Unknown account balance
        assertThrows(IllegalArgumentException.class, () -> state.executeOperation(balanceOp("Z")));
    }

    @Test
    void testGetLowWatermark_returnsInitialValue() {
        ServerState state = newState("n1");
        assertEquals(0L, state.getLowWatermark(), "Low watermark should initially be 0");
    }

    @Test
    void testGetHighWatermark_returnsInitialValue() {
        ServerState state = newState("n1");
        assertEquals(100L, state.getHighWatermark(), "High watermark should initially be 100");
    }

    @Test
    void testSeqNumBetweenWatermarks_withinRange() {
        ServerState state = newState("n1");

        // Test sequences within the watermark range (0, 100]
        assertTrue(state.seqNumBetweenWatermarks(1L), "Sequence 1 should be between watermarks");
        assertTrue(state.seqNumBetweenWatermarks(50L), "Sequence 50 should be between watermarks");
        assertTrue(state.seqNumBetweenWatermarks(100L), "Sequence 100 should be between watermarks (inclusive high)");
    }

    @Test
    void testSeqNumBetweenWatermarks_outsideRange() {
        ServerState state = newState("n1");

        // Test sequences outside the watermark range
        assertFalse(state.seqNumBetweenWatermarks(0L), "Sequence 0 should not be between watermarks (exclusive low)");
        assertFalse(state.seqNumBetweenWatermarks(-1L), "Negative sequence should not be between watermarks");
        assertFalse(state.seqNumBetweenWatermarks(101L), "Sequence 101 should not be between watermarks (above high)");
        assertFalse(state.seqNumBetweenWatermarks(1000L), "Sequence 1000 should not be between watermarks");
    }

    @Test
    void testSeqNumBetweenWatermarks_boundaryConditions() {
        ServerState state = newState("n1");

        // Test exact boundary values
        // Low watermark is exclusive (0 < seq)
        assertFalse(state.seqNumBetweenWatermarks(0L), "Sequence equal to low watermark should be excluded");

        // High watermark is inclusive (seq <= 100)
        assertTrue(state.seqNumBetweenWatermarks(100L), "Sequence equal to high watermark should be included");

        // Just outside boundaries
        assertTrue(state.seqNumBetweenWatermarks(1L), "Sequence 1 (low + 1) should be included");
        assertFalse(state.seqNumBetweenWatermarks(101L), "Sequence 101 (high + 1) should be excluded");
    }

    // ===== isPrepared Tests =====

    @Test
    void testIsPrepared_noPrePrepare_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add some Prepares but no PrePrepare
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");
        addPrepare(state, view, seq, "digest1", "n4");

        // Should return false without PrePrepare
        assertFalse(state.isPrepared(view, seq, quorumSize),
                "isPrepared should return false without PrePrepare");
    }

    @Test
    void testIsPrepared_withPrePrepareButNoPrepares_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add only PrePrepare
        addPrePrepare(state, view, seq, "digest1");

        // Should return false without enough Prepares
        assertFalse(state.isPrepared(view, seq, quorumSize),
                "isPrepared should return false with PrePrepare but no Prepares");
    }

    @Test
    void testIsPrepared_withPrePrepareAndSufficientPrepares_returnsTrue() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add PrePrepare
        addPrePrepare(state, view, seq, "digest1");

        // Add Prepares to reach quorum (need 3, including PrePrepare counts as 1)
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");
        addPrepare(state, view, seq, "digest1", "n4");

        // Should return true with quorum
        assertTrue(state.isPrepared(view, seq, quorumSize),
                "isPrepared should return true with PrePrepare and sufficient Prepares");
    }

    @Test
    void testIsPrepared_insufficientPrepares_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 5; // Need 5 total

        // Add PrePrepare
        addPrePrepare(state, view, seq, "digest1");

        // Add only 2 Prepares (total 3, need 5)
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");

        // Should return false
        assertFalse(state.isPrepared(view, seq, quorumSize),
                "isPrepared should return false with insufficient Prepares");
    }

    @Test
    void testIsPrepared_exactQuorum_returnsTrue() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add PrePrepare
        addPrePrepare(state, view, seq, "digest1");

        // Add exactly enough Prepares to meet quorum
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");

        // Should return true with exact quorum
        assertTrue(state.isPrepared(view, seq, quorumSize),
                "isPrepared should return true with exact quorum");
    }

    @Test
    void testIsPrepared_differentViewNumber_returnsFalse() {
        ServerState state = newState("n1");
        long view1 = 1L;
        long view2 = 2L;
        long seq = 10L;
        int quorumSize = 3;

        // Add PrePrepare and Prepares for view 1
        addPrePrepare(state, view1, seq, "digest1");
        addPrepare(state, view1, seq, "digest1", "n2");
        addPrepare(state, view1, seq, "digest1", "n3");

        // Check isPrepared for view 2 (different view)
        assertFalse(state.isPrepared(view2, seq, quorumSize),
                "isPrepared should return false for different view number");
    }

    @Test
    void testIsPrepared_differentSequenceNumber_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq1 = 10L;
        long seq2 = 20L;
        int quorumSize = 3;

        // Add PrePrepare and Prepares for seq 10
        addPrePrepare(state, view, seq1, "digest1");
        addPrepare(state, view, seq1, "digest1", "n2");
        addPrepare(state, view, seq1, "digest1", "n3");

        // Check isPrepared for seq 20 (different sequence)
        assertFalse(state.isPrepared(view, seq2, quorumSize),
                "isPrepared should return false for different sequence number");
    }

    // ===== isCommitted Tests =====

    @Test
    void testIsCommitted_noPrePrepare_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add Prepares and Commits but no PrePrepare
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");
        addCommit(state, view, seq, "digest1", "n2");
        addCommit(state, view, seq, "digest1", "n3");

        // Should return false without PrePrepare
        assertFalse(state.isCommitted(view, seq, quorumSize),
                "isCommitted should return false without PrePrepare");
    }

    @Test
    void testIsCommitted_notPrepared_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add PrePrepare but not enough Prepares
        addPrePrepare(state, view, seq, "digest1");
        addPrepare(state, view, seq, "digest1", "n2");

        // Add Commits
        addCommit(state, view, seq, "digest1", "n2");
        addCommit(state, view, seq, "digest1", "n3");
        addCommit(state, view, seq, "digest1", "n4");

        // Should return false if not prepared
        assertFalse(state.isCommitted(view, seq, quorumSize),
                "isCommitted should return false if not prepared");
    }

    @Test
    void testIsCommitted_preparedButNoCommits_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add PrePrepare and Prepares (prepared state)
        addPrePrepare(state, view, seq, "digest1");
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");
        addPrepare(state, view, seq, "digest1", "n4");

        // No Commits added

        // Should return false without Commits
        assertFalse(state.isCommitted(view, seq, quorumSize),
                "isCommitted should return false with prepared but no Commits");
    }

    @Test
    void testIsCommitted_preparedWithSufficientCommits_returnsTrue() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add PrePrepare
        addPrePrepare(state, view, seq, "digest1");

        // Add Prepares to reach quorum
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");
        addPrepare(state, view, seq, "digest1", "n4");

        // Add Commits to reach quorum
        addCommit(state, view, seq, "digest1", "n2");
        addCommit(state, view, seq, "digest1", "n3");
        addCommit(state, view, seq, "digest1", "n4");

        // Should return true
        assertTrue(state.isCommitted(view, seq, quorumSize),
                "isCommitted should return true with prepared and sufficient Commits");
    }

    @Test
    void testIsCommitted_insufficientCommits_returnsFalse() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 5;

        // Add PrePrepare and Prepares (prepared)
        addPrePrepare(state, view, seq, "digest1");
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");
        addPrepare(state, view, seq, "digest1", "n4");
        addPrepare(state, view, seq, "digest1", "n5");

        // Add only 2 Commits (need 5)
        addCommit(state, view, seq, "digest1", "n2");
        addCommit(state, view, seq, "digest1", "n3");

        // Should return false
        assertFalse(state.isCommitted(view, seq, quorumSize),
                "isCommitted should return false with insufficient Commits");
    }

    @Test
    void testIsCommitted_exactQuorum_returnsTrue() {
        ServerState state = newState("n1");
        long view = 1L;
        long seq = 10L;
        int quorumSize = 3;

        // Add PrePrepare
        addPrePrepare(state, view, seq, "digest1");

        // Add Prepares to meet quorum
        addPrepare(state, view, seq, "digest1", "n2");
        addPrepare(state, view, seq, "digest1", "n3");

        // Add exact quorum of Commits
        addCommit(state, view, seq, "digest1", "n2");
        addCommit(state, view, seq, "digest1", "n3");
        addCommit(state, view, seq, "digest1", "n1");

        // Should return true
        assertTrue(state.isCommitted(view, seq, quorumSize),
                "isCommitted should return true with exact quorum");
    }

    @Test
    void testIsCommitted_differentViewNumber_returnsFalse() {
        ServerState state = newState("n1");
        long view1 = 1L;
        long view2 = 2L;
        long seq = 10L;
        int quorumSize = 3;

        // Add full consensus for view 1
        addPrePrepare(state, view1, seq, "digest1");
        addPrepare(state, view1, seq, "digest1", "n2");
        addPrepare(state, view1, seq, "digest1", "n3");
        addCommit(state, view1, seq, "digest1", "n2");
        addCommit(state, view1, seq, "digest1", "n3");

        // Check isCommitted for view 2
        assertFalse(state.isCommitted(view2, seq, quorumSize),
                "isCommitted should return false for different view number");
    }

    @Test
    void testIsCommitted_multipleSequences_eachTrackedIndependently() {
        ServerState state = newState("n1");
        long view = 1L;
        int quorumSize = 3;

        // Fully commit seq 10
        addPrePrepare(state, view, 10L, "digest1");
        addPrepare(state, view, 10L, "digest1", "n2");
        addPrepare(state, view, 10L, "digest1", "n3");
        addCommit(state, view, 10L, "digest1", "n1");
        addCommit(state, view, 10L, "digest1", "n2");
        addCommit(state, view, 10L, "digest1", "n3");

        // Only prepare seq 20 (not commit)
        addPrePrepare(state, view, 20L, "digest2");
        addPrepare(state, view, 20L, "digest2", "n2");
        addPrepare(state, view, 20L, "digest2", "n3");

        // Seq 10 should be committed
        assertTrue(state.isCommitted(view, 10L, quorumSize),
                "Seq 10 should be committed");

        // Seq 20 should be prepared but not committed
        assertTrue(state.isPrepared(view, 20L, quorumSize),
                "Seq 20 should be prepared");
        assertFalse(state.isCommitted(view, 20L, quorumSize),
                "Seq 20 should not be committed");
    }

    // ===== Helper Methods =====

    private void addPrePrepare(ServerState state, long view, long seq, String digest) {
        MessageServiceOuterClass.PrePrepareMessage prePrepare =
                MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                        .setViewNumber(view)
                        .setSequenceNumber(seq)
                        .setDigest(com.google.protobuf.ByteString.copyFromUtf8(digest))
                        .setSignerId(state.getPrimaryServerId())
                        .setSignature(com.google.protobuf.ByteString.copyFromUtf8("sig-primary"))
                        .build();
        state.appendServerMessage(prePrepare);
    }

    private void addPrepare(ServerState state, long view, long seq, String digest, String signerId) {
        MessageServiceOuterClass.PrepareMessage prepare =
                MessageServiceOuterClass.PrepareMessage.newBuilder()
                        .setViewNumber(view)
                        .setSequenceNumber(seq)
                        .setDigest(com.google.protobuf.ByteString.copyFromUtf8(digest))
                        .setSignerId(signerId)
                        .setSignature(com.google.protobuf.ByteString.copyFromUtf8("sig-" + signerId))
                        .build();
        state.appendServerMessage(prepare);
    }

    private void addCommit(ServerState state, long view, long seq, String digest, String signerId) {
        MessageServiceOuterClass.CommitMessage commit =
                MessageServiceOuterClass.CommitMessage.newBuilder()
                        .setViewNumber(view)
                        .setSequenceNumber(seq)
                        .setDigest(com.google.protobuf.ByteString.copyFromUtf8(digest))
                        .setSignerId(signerId)
                        .setSignature(com.google.protobuf.ByteString.copyFromUtf8("sig-" + signerId))
                        .build();
        state.appendServerMessage(commit);
    }
}
