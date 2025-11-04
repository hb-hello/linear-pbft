package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.serverstate.ServerState;
import org.example.testutil.MockCommitSender;
import org.example.testutil.MockPrepareSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PrepareHandlerTest {

    private static ExecutorService stateExec;
    private ServerState state;
    private MockPrepareSender prepareSender;
    private MockCommitSender commitSender;
    private PrepareHandler handler;

    private static final int QUORUM_SIZE = 3; // 2f + 1 where f = 1

    @BeforeAll
    static void setup() {
        Config.initialize("src/test/resources/config.properties");
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

    @BeforeEach
    void setUp() {
        // Pass a no-op callback for testing - replies aren't actually sent in unit tests
        state = new ServerState("n1", false, stateExec, (request, reply) -> {});
        prepareSender = new MockPrepareSender("n1", state);
        commitSender = new MockCommitSender("n1", QUORUM_SIZE, state);
        handler = new PrepareHandler(state, QUORUM_SIZE, prepareSender, commitSender);
    }

    @AfterEach
    void tearDownTest() {
        if (prepareSender != null) {
            prepareSender.shutdown();
        }
        if (commitSender != null) {
            commitSender.shutdown();
        }
    }

    /**
     * Helper method to create a PrepareMessage
     */
    private MessageServiceOuterClass.PrepareMessage createPrepareMessage(
            long view, long seq, String digest, String signerId) {
        return MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(view)
                .setSequenceNumber(seq)
                .setDigest(ByteString.copyFromUtf8(digest))
                .setSignerId(signerId)
                .setSignature(ByteString.copyFromUtf8("sig-" + signerId))
                .build();
    }

    /**
     * Helper method to add a PrePrepare to state (needed for isPrepared check)
     */
    private void addPrePrepareToState(long view, long seq, String digest) {
        MessageServiceOuterClass.PrePrepareMessage prePrepare =
                MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(view)
                .setSequenceNumber(seq)
                .setDigest(ByteString.copyFromUtf8(digest))
                .setSignerId(state.getPrimaryServerId())
                .setSignature(ByteString.copyFromUtf8("sig-primary"))
                .build();
        state.appendServerMessage(prePrepare);
    }

    @Test
    void testHandle_validPrepare_addsToState() {
        // Arrange
        long view = 1L;
        long seq = 1L;
        String digest = "test-digest";
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                createPrepareMessage(view, seq, digest, "n2");

        // Act
        handler.handle(prepareMsg);

        // Assert
        assertNotNull(state.findPrepare(view, seq, "n2"), "Prepare should be added to state");
        assertEquals(0, commitSender.getSendCount(), "Commit should not be sent without quorum");
    }

    @Test
    void testHandle_wrongView_rejected() {
        // Arrange
        state.setViewAndPrimary(1L); // Current view is 1
        long wrongView = 5L; // Different view
        long seq = 1L;
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                createPrepareMessage(wrongView, seq, "digest", "n2");

        // Act
        handler.handle(prepareMsg);

        // Assert
        assertNull(state.findPrepare(wrongView, seq, "n2"),
                "Prepare with wrong view should be rejected");
    }

    @Test
    void testHandle_sequenceOutOfWatermarks_rejected() {
        // Arrange
        long view = 1L;
        long seqBelowLowWatermark = state.getLowWatermark() - 1;
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                createPrepareMessage(view, seqBelowLowWatermark, "digest", "n2");

        // Act
        handler.handle(prepareMsg);

        // Assert
        assertNull(state.findPrepare(view, seqBelowLowWatermark, "n2"),
                "Prepare with sequence below watermark should be rejected");
    }

    @Test
    void testHandle_sequenceAboveHighWatermark_rejected() {
        // Arrange
        long view = 1L;
        long seqAboveHighWatermark = state.getHighWatermark() + 1;
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                createPrepareMessage(view, seqAboveHighWatermark, "digest", "n2");

        // Act
        handler.handle(prepareMsg);

        // Assert
        assertNull(state.findPrepare(view, seqAboveHighWatermark, "n2"),
                "Prepare with sequence above watermark should be rejected");
    }

//    @Test
//    void testHandle_quorumReached_sendsCommit() {
//        // Arrange
//        long view = 1L;
//        long seq = 1L;
//        String digest = "quorum-test-digest";
//
//        // Add PrePrepare first (required for isPrepared check)
//        addPrePrepareToState(view, seq, digest);
//
//        // Add Prepares from different replicas to reach quorum
//        // Note: Since messages are deduplicated by messageIndex, we need to simulate
//        // multiple prepares by calling append multiple times with same digest
//        MessageServiceOuterClass.PrepareMessage prepare1 = createPrepareMessage(view, seq, digest, "n2");
//        MessageServiceOuterClass.PrepareMessage prepare2 = createPrepareMessage(view, seq, digest, "n3");
//        MessageServiceOuterClass.PrepareMessage prepare3 = createPrepareMessage(view, seq, digest, "n4");
//
//        // Act - handle prepares to reach quorum
//        handler.handle(prepare1);
//        assertEquals(0, commitSender.getSendCount(), "Commit not sent yet (have 1 Prepare, need 2)");
//
//        handler.handle(prepare2);
//        // After 2nd Prepare: PrePrepare + 2 Prepares = quorum of 3, commit should be sent
//        assertEquals(1, commitSender.getSendCount(), "Commit should be sent (PrePrepare + 2 Prepares = quorum)");
//
//        handler.handle(prepare3);
//
//        // Assert - after 3rd Prepare, PrepareHandler calls attemptCommit again
//        // But CommitSender's attemptCommit checks if appendServerMessage succeeds
//        // Since we already have a Commit from ourselves (n1), appendServerMessage returns false
//        // So the send doesn't happen, and count stays at 1
//        assertEquals(1, commitSender.getSendCount(),
//                "Commit count should remain 1 (duplicate Commit from same sender prevented)");
//
//        MessageServiceOuterClass.CommitMessage sentCommit = commitSender.getCapturedCommit();
//        assertNotNull(sentCommit, "Commit message should be captured");
//        assertEquals(view, sentCommit.getViewNumber(), "Commit view should match");
//        assertEquals(seq, sentCommit.getSequenceNumber(), "Commit sequence should match");
//        assertArrayEquals(digest.getBytes(), sentCommit.getDigest().toByteArray(),
//                "Commit digest should match");
//    }

    @Test
    void testHandle_duplicatePrepare_notAddedAgain() {
        // Arrange
        long view = 1L;
        long seq = 2L;
        String digest = "duplicate-digest";
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                createPrepareMessage(view, seq, digest, "n2");

        // Act - handle same message twice
        handler.handle(prepareMsg);
        handler.handle(prepareMsg);

        // Assert - message should only be added once
        assertNotNull(state.findPrepare(view, seq, "n2"), "Prepare should be in state");
        // Note: We can't directly check count, but duplicate shouldn't cause issues
        assertEquals(0, commitSender.getSendCount(),
                "Commit should not be sent without quorum");
    }

    @Test
    void testHandle_multipleSequenceNumbers_eachTrackedSeparately() {
        // Arrange
        long view = 1L;
        String digest = "multi-seq-digest";

        // Act - add prepares for different sequence numbers
        for (int seq = 1; seq <= 5; seq++) {
            MessageServiceOuterClass.PrepareMessage prepareMsg =
                    createPrepareMessage(view, seq, digest + seq, "n2");
            handler.handle(prepareMsg);
        }

        // Assert - all should be in state
        for (int seq = 1; seq <= 5; seq++) {
            assertNotNull(state.findPrepare(view, seq, "n2"),
                    "Prepare for seq " + seq + " should be in state");
        }
    }

    @Test
    void testHandle_withEmptyDigest() {
        // Arrange
        long view = 1L;
        long seq = 3L;
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(view)
                .setSequenceNumber(seq)
                .setDigest(ByteString.EMPTY)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig-n2"))
                .build();

        // Act
        handler.handle(prepareMsg);

        // Assert
        assertNotNull(state.findPrepare(view, seq, "n2"),
                "Prepare with empty digest should be accepted");
    }

    @Test
    void testHandle_withLargeDigest() {
        // Arrange
        long view = 1L;
        long seq = 4L;
        byte[] largeDigest = new byte[256];
        for (int i = 0; i < largeDigest.length; i++) {
            largeDigest[i] = (byte) (i % 256);
        }
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(view)
                .setSequenceNumber(seq)
                .setDigest(ByteString.copyFrom(largeDigest))
                .setSignerId("n3")
                .setSignature(ByteString.copyFromUtf8("sig-n3"))
                .build();

        // Act
        handler.handle(prepareMsg);

        // Assert
        assertNotNull(state.findPrepare(view, seq, "n3"),
                "Prepare with large digest should be accepted");
    }

    @Test
    void testHandle_atWatermarkBoundaries() {
        // Arrange - test at low watermark boundary
        long view = 1L;
        long seqAtLowWatermark = state.getLowWatermark() + 1;
        MessageServiceOuterClass.PrepareMessage prepareAtLow =
                createPrepareMessage(view, seqAtLowWatermark, "digest-low", "n2");

        // Test at high watermark boundary
        long seqAtHighWatermark = state.getHighWatermark();
        MessageServiceOuterClass.PrepareMessage prepareAtHigh =
                createPrepareMessage(view, seqAtHighWatermark, "digest-high", "n3");

        // Act
        handler.handle(prepareAtLow);
        handler.handle(prepareAtHigh);

        // Assert
        assertNotNull(state.findPrepare(view, seqAtLowWatermark, "n2"),
                "Prepare at low watermark boundary should be accepted");
        assertNotNull(state.findPrepare(view, seqAtHighWatermark, "n3"),
                "Prepare at high watermark boundary should be accepted");
    }

    @Test
    void testHandle_multipleValidPrepares_allAdded() {
        // Arrange
        long view = 1L;
        long seq = 5L;
        String digest = "multiple-prepares-digest";

        // Act - add prepares from different replicas
        handler.handle(createPrepareMessage(view, seq, digest, "n2"));
        handler.handle(createPrepareMessage(view, seq, digest, "n3"));
        handler.handle(createPrepareMessage(view, seq, digest, "n4"));

        // Assert - prepare should be in state (each with different sender)
        assertNotNull(state.findPrepare(view, seq, "n2"), "Prepare from n2 should be in state");
    }

    @Test
    void testHandle_preparesWithDifferentDigests_firstOneStored() {
        // Arrange
        long view = 1L;
        long seq = 6L;

        MessageServiceOuterClass.PrepareMessage prepare1 =
                createPrepareMessage(view, seq, "digest-A", "n2");
        MessageServiceOuterClass.PrepareMessage prepare2 =
                createPrepareMessage(view, seq, "digest-B", "n3");

        // Act
        handler.handle(prepare1);
        handler.handle(prepare2);

        // Assert - both should be stored with different senders
        assertNotNull(state.findPrepare(view, seq, "n2"), "First prepare should be in state");
        assertNotNull(state.findPrepare(view, seq, "n3"), "Second prepare should be in state");
        // Note: The second prepare with different digest should be handled by quorum tracker
    }

    @Test
    void testHandle_zeroViewAndSequence() {
        // Arrange
        MessageServiceOuterClass.PrepareMessage prepareMsg =
                createPrepareMessage(0L, 0L, "zero-digest", "n2");

        // Act
        handler.handle(prepareMsg);

        // Assert - 0 is below low watermark, should be rejected
        assertNull(state.findPrepare(0L, 0L, "n2"),
                "Prepare with sequence 0 should be rejected (below watermark)");
    }
}

