package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;
import org.example.testutil.MockPrepareSender;
import org.junit.jupiter.api.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PrepareSenderTest {

    private static ExecutorService stateExec;
    private ServerState state;
    private MockPrepareSender sender;

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
        sender = new MockPrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
    }

    @AfterEach
    void tearDownTest() {
        if (sender != null) {
            sender.shutdown();
        }
    }

    @Test
    void testSendPrepare_buildsCorrectMessage() {
        // Arrange
        long viewNumber = 1L;
        long sequenceNumber = 10L;
        byte[] digest = "test-digest".getBytes();

        // Act
        sender.sendPrepare(viewNumber, sequenceNumber, digest);

        // Assert
        MessageServiceOuterClass.PrepareMessage captured = sender.getCapturedPrepare();
        assertNotNull(captured, "PrepareMessage should be captured");
        assertEquals(viewNumber, captured.getViewNumber(), "View number should match");
        assertEquals(sequenceNumber, captured.getSequenceNumber(), "Sequence number should match");
        assertArrayEquals(digest, captured.getDigest().toByteArray(), "Digest should match");
    }

    @Test
    void testSendPrepare_appendsToServerState() {
        // Arrange
        long viewNumber = 2L;
        long sequenceNumber = 20L;
        byte[] digest = "state-test-digest".getBytes();

        // Act
        sender.sendPrepare(viewNumber, sequenceNumber, digest);

        // Assert - verify message was added to state
        ServerMessage foundPrepare = state.findPrepare(viewNumber, sequenceNumber, "n1");
        assertNotNull(foundPrepare, "Prepare should be added to ServerState");
        assertEquals(viewNumber, foundPrepare.getViewNumber().orElse(-1L), "View number in state should match");
        assertEquals(sequenceNumber, foundPrepare.getSequenceNumber().orElse(-1L), "Sequence number in state should match");
        assertArrayEquals(digest, foundPrepare.getDigest().orElse(ByteString.EMPTY).toByteArray(), "Digest in state should match");
    }

    @Test
    void testSendPrepare_sendsToCollector() {
        // Arrange
        long viewNumber = 3L;
        long sequenceNumber = 30L;
        byte[] digest = "collector-test-digest".getBytes();
        String expectedCollectorId = state.getCollectorServerId();

        // Act
        sender.sendPrepare(viewNumber, sequenceNumber, digest);

        // Assert
        assertEquals(expectedCollectorId, sender.getCapturedTargetNodeId(),
                "Prepare should be sent to collector server");
        assertEquals(1, sender.getSendCount(), "Send should be called once");
    }

    @Test
    void testSendPrepare_multipleCalls_eachAppendedToState() {
        // Arrange
        byte[] digest1 = "digest-1".getBytes();
        byte[] digest2 = "digest-2".getBytes();
        byte[] digest3 = "digest-3".getBytes();

        // Act - send multiple prepare messages
        sender.sendPrepare(1L, 10L, digest1);
        sender.sendPrepare(1L, 11L, digest2);
        sender.sendPrepare(2L, 12L, digest3);

        // Assert - all should be in state
        assertNotNull(state.findPrepare(1L, 10L, "n1"), "First Prepare should be in state");
        assertNotNull(state.findPrepare(1L, 11L, "n1"), "Second Prepare should be in state");
        assertNotNull(state.findPrepare(2L, 12L, "n1"), "Third Prepare should be in state");
        assertEquals(3, sender.getSendCount(), "Three sends should have occurred");
    }

    @Test
    void testSendPrepare_withEmptyDigest() {
        // Arrange
        long viewNumber = 4L;
        long sequenceNumber = 40L;
        byte[] emptyDigest = new byte[0];

        // Act
        sender.sendPrepare(viewNumber, sequenceNumber, emptyDigest);

        // Assert
        MessageServiceOuterClass.PrepareMessage captured = sender.getCapturedPrepare();
        assertNotNull(captured);
        assertEquals(0, captured.getDigest().size(), "Empty digest should be preserved");

        ServerMessage foundPrepare = state.findPrepare(viewNumber, sequenceNumber, "n1");
        assertNotNull(foundPrepare);
        assertEquals(0, foundPrepare.getDigest().orElse(ByteString.EMPTY).size(),
                "Empty digest should be in state");
    }

    @Test
    void testSendPrepare_withLargeDigest() {
        // Arrange
        long viewNumber = 5L;
        long sequenceNumber = 50L;
        byte[] largeDigest = new byte[256]; // SHA-256 size
        for (int i = 0; i < largeDigest.length; i++) {
            largeDigest[i] = (byte) (i % 256);
        }

        // Act
        sender.sendPrepare(viewNumber, sequenceNumber, largeDigest);

        // Assert
        MessageServiceOuterClass.PrepareMessage captured = sender.getCapturedPrepare();
        assertNotNull(captured);
        assertArrayEquals(largeDigest, captured.getDigest().toByteArray(),
                "Large digest should be preserved exactly");
    }

    @Test
    void testSendPrepare_withZeroViewAndSequence() {
        // Arrange
        long viewNumber = 0L;
        long sequenceNumber = 0L;
        byte[] digest = "zero-test".getBytes();

        // Act
        sender.sendPrepare(viewNumber, sequenceNumber, digest);

        // Assert
        MessageServiceOuterClass.PrepareMessage captured = sender.getCapturedPrepare();
        assertNotNull(captured);
        assertEquals(0L, captured.getViewNumber(), "Zero view number should be preserved");
        assertEquals(0L, captured.getSequenceNumber(), "Zero sequence number should be preserved");

        ServerMessage foundPrepare = state.findPrepare(0L, 0L, "n1");
        assertNotNull(foundPrepare, "Prepare with zero view/seq should be findable");
    }

    @Test
    void testSendPrepare_withHighViewAndSequenceNumbers() {
        // Arrange
        long highViewNumber = Long.MAX_VALUE - 1;
        long highSequenceNumber = Long.MAX_VALUE - 1;
        byte[] digest = "high-numbers-test".getBytes();

        // Act
        sender.sendPrepare(highViewNumber, highSequenceNumber, digest);

        // Assert
        MessageServiceOuterClass.PrepareMessage captured = sender.getCapturedPrepare();
        assertNotNull(captured);
        assertEquals(highViewNumber, captured.getViewNumber(), "High view number should be preserved");
        assertEquals(highSequenceNumber, captured.getSequenceNumber(), "High sequence number should be preserved");
    }

    @Test
    void testSendPrepare_consecutiveCalls_latestCaptured() {
        // Arrange
        byte[] digest1 = "first-digest".getBytes();
        byte[] digest2 = "second-digest".getBytes();

        // Act
        sender.sendPrepare(1L, 1L, digest1);
        sender.sendPrepare(1L, 2L, digest2);

        // Assert - latest call should be captured
        MessageServiceOuterClass.PrepareMessage captured = sender.getCapturedPrepare();
        assertEquals(2L, captured.getSequenceNumber(), "Latest sequence number should be captured");
        assertArrayEquals(digest2, captured.getDigest().toByteArray(), "Latest digest should be captured");
    }
}

