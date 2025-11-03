package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.messaging.MessageUtil;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;
import org.example.testutil.MockMessageAuthenticator;
import org.example.testutil.MockPrepareSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PrePrepareHandlerTest {

    private static ExecutorService stateExec;
    private ServerState state;
    private PrePrepareHandler handler;
    private MockPrepareSender mockPrepareSender;

    @BeforeAll
    static void setup() {
        Config.initialize("src/test/resources/config.properties");
        stateExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("-state-manager-0");  // Must start with "-state-manager" for onStateThread() check
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
        state = new ServerState("n1", false, stateExec);
        // Use a mock authenticator that always returns true
        MockMessageAuthenticator mockAuth = new MockMessageAuthenticator();
        // Create a mock PrepareSender that doesn't actually send messages
        mockPrepareSender = new MockPrepareSender("n1", state);
        handler = new PrePrepareHandler(state, mockAuth, mockPrepareSender);
    }

    @AfterEach
    void tearDownTest() {
        if (mockPrepareSender != null) {
            mockPrepareSender.shutdown();
        }
    }

    private byte[] computeDigest(MessageServiceOuterClass.ClientRequest request) {
        try {
            return MessageUtil.generateDigest(request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MessageServiceOuterClass.ClientRequest createClientRequest(String clientId, long timestamp) {
        return MessageServiceOuterClass.ClientRequest.newBuilder()
                .setClientId(clientId)
                .setTimestamp(timestamp)
                .setOperation(MessageServiceOuterClass.Operation.newBuilder()
                        .setTransfer(MessageServiceOuterClass.Transfer.newBuilder()
                                .setSender("A")
                                .setReceiver("B")
                                .setAmount(10.0)
                                .build())
                        .build())
                .setSignerId("client1")
                .setSignature(ByteString.copyFromUtf8("valid-signature"))
                .build();
    }

    private MessageServiceOuterClass.PrePrepareRequest createPrePrepareRequest(
            long view, long seq, MessageServiceOuterClass.ClientRequest clientRequest) {
        byte[] digest = computeDigest(clientRequest);

        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg =
                MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                        .setViewNumber(view)
                        .setSequenceNumber(seq)
                        .setDigest(ByteString.copyFrom(digest))
                        .setSignerId("n1")
                        .setSignature(ByteString.copyFromUtf8("valid-sig"))
                        .build();

        return MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMsg)
                .setRequest(clientRequest)
                .build();
    }

    @Test
    void testHandle_validPrePrepare_addsMessageToState() {
        // Setup: Create a valid PrePrepare message
        MessageServiceOuterClass.ClientRequest clientRequest = createClientRequest("client1", 1000L);
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest =
                createPrePrepareRequest(1L, 1L, clientRequest);

        // Initially, state should not have this PrePrepare
        assertFalse(state.hasPrePrepare(1L, 1L), "State should not have PrePrepare before handling");

        // Act: Handle the PrePrepare
        handler.handle(prePrepareRequest);

        // Assert: PrePrepare should be added to state (as PrePrepareMessage, not PrePrepareRequest)
        ServerMessage foundMessage = state.findPrePrepare(1L, 1L, "n1");
        assertNotNull(foundMessage, "PrePrepare should be added to state");
        assertEquals("PrePrepareMessage", foundMessage.getMessageType());
        assertEquals(1L, foundMessage.getViewNumber().orElse(-1L));
        assertEquals(1L, foundMessage.getSequenceNumber().orElse(-1L));
    }

    @Test
    void testHandle_wrongView_doesNotAddToState() {
        // Setup: State is at view 0, but PrePrepare is for view 1
        state.setViewAndPrimary(0L);
        MessageServiceOuterClass.ClientRequest clientRequest = createClientRequest("client1", 1000L);
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest =
                createPrePrepareRequest(1L, 1L, clientRequest);

        // Act: Handle the PrePrepare with wrong view
        handler.handle(prePrepareRequest);

        // Assert: PrePrepare should NOT be added to state
        assertFalse(state.hasPrePrepare(1L, 1L), "PrePrepare with wrong view should not be added");
    }

    @Test
    void testHandle_sequenceOutOfWatermarks_doesNotAddToState() {
        // Setup: Sequence number is outside watermark range (0, 100]
        state.setViewAndPrimary(0L);
        MessageServiceOuterClass.ClientRequest clientRequest = createClientRequest("client1", 1000L);

        // Test with seq = 0 (at low watermark, exclusive)
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest1 =
                createPrePrepareRequest(1L, 0L, clientRequest);
        handler.handle(prePrepareRequest1);
        assertFalse(state.hasPrePrepare(1L, 0L), "PrePrepare with seq at low watermark should not be added");

        // Test with seq = 101 (above high watermark)
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest2 =
                createPrePrepareRequest(1L, 101L, clientRequest);
        handler.handle(prePrepareRequest2);
        assertFalse(state.hasPrePrepare(1L, 101L), "PrePrepare with seq above high watermark should not be added");
    }

    @Test
    void testHandle_sequenceAtBoundaries_correctBehavior() {
        // Test seq = 1 (just above low watermark, should be accepted)
        MessageServiceOuterClass.ClientRequest clientRequest1 = createClientRequest("client1", 1000L);
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest1 =
                createPrePrepareRequest(1L, 1L, clientRequest1);
        handler.handle(prePrepareRequest1);
        assertTrue(state.hasPrePrepare(1L, 1L), "PrePrepare with seq=1 should be added");

        // Test seq = 100 (at high watermark, inclusive, should be accepted)
        MessageServiceOuterClass.ClientRequest clientRequest2 = createClientRequest("client2", 2000L);
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest2 =
                createPrePrepareRequest(1L, 100L, clientRequest2);
        handler.handle(prePrepareRequest2);
        assertTrue(state.hasPrePrepare(1L, 100L), "PrePrepare with seq=100 should be added");
    }

    @Test
    void testHandle_duplicateWithSameDigest_doesNotAddAgain() {
        // Setup: Add a PrePrepare first time
        MessageServiceOuterClass.ClientRequest clientRequest = createClientRequest("client1", 1000L);
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest =
                createPrePrepareRequest(1L, 1L, clientRequest);

        handler.handle(prePrepareRequest);
        ServerMessage first = state.findPrePrepare(1L, 1L, "n1");
        assertNotNull(first, "First PrePrepare should be added");

        // Get the current size of messages
        int sizeAfterFirst = state.getServerMessageTracker().size();

        // Act: Try to add the same PrePrepare again
        handler.handle(prePrepareRequest);

        // Assert: Size should not increase (duplicate not added)
        int sizeAfterSecond = state.getServerMessageTracker().size();
        assertEquals(sizeAfterFirst, sizeAfterSecond,
                "Duplicate PrePrepare should not be added again");
    }

    @Test
    void testHandle_duplicateWithDifferentDigest_rejected() {
        // Setup: Add a PrePrepare with one digest
        MessageServiceOuterClass.ClientRequest clientRequest1 = createClientRequest("client1", 1000L);
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest1 =
                createPrePrepareRequest(1L, 1L, clientRequest1);
        handler.handle(prePrepareRequest1);
        assertTrue(state.hasPrePrepare(1L, 1L));

        int sizeAfterFirst = state.getServerMessageTracker().size();

        // Act: Try to add another PrePrepare with same view/seq but different digest
        MessageServiceOuterClass.ClientRequest clientRequest2 = createClientRequest("client2", 2000L);
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest2 =
                createPrePrepareRequest(1L, 1L, clientRequest2);
        handler.handle(prePrepareRequest2);

        // Assert: Second PrePrepare should be rejected (different digest for same view/seq)
        int sizeAfterSecond = state.getServerMessageTracker().size();
        assertEquals(sizeAfterFirst, sizeAfterSecond,
                "PrePrepare with different digest for same view/seq should be rejected");
    }

    @Test
    void testHandle_invalidClientRequestSignature_doesNotAddToState() {
        // Use a mock authenticator that rejects signatures
        MockMessageAuthenticator rejectingAuth = new MockMessageAuthenticator(false);
        MockPrepareSender localMockSender = new MockPrepareSender("n1", state);
        PrePrepareHandler rejectingHandler = new PrePrepareHandler(state, rejectingAuth, localMockSender);

        try {
            MessageServiceOuterClass.ClientRequest clientRequest = createClientRequest("client1", 1000L);
            MessageServiceOuterClass.PrePrepareRequest prePrepareRequest =
                    createPrePrepareRequest(1L, 1L, clientRequest);

            // Act: Handle with invalid client request signature
            rejectingHandler.handle(prePrepareRequest);

            // Assert: PrePrepare should NOT be added due to invalid signature
            assertFalse(state.hasPrePrepare(1L, 1L),
                    "PrePrepare with invalid client signature should not be added");
        } finally {
            // Clean up local mock sender
            localMockSender.shutdown();
        }
    }

    @Test
    void testHandle_multipleValidPrePrepares_allAdded() {
        // Add multiple valid PrePrepares with different view/seq combinations
        for (int seq = 1; seq <= 5; seq++) {
            MessageServiceOuterClass.ClientRequest clientRequest =
                    createClientRequest("client" + seq, 1000L + seq);
            MessageServiceOuterClass.PrePrepareRequest prePrepareRequest =
                    createPrePrepareRequest(1L, (long) seq, clientRequest);
            handler.handle(prePrepareRequest);
        }

        // Assert: All 5 should be added
        for (int seq = 1; seq <= 5; seq++) {
            assertTrue(state.hasPrePrepare(1L, (long) seq),
                    "PrePrepare with seq=" + seq + " should be added");
        }
    }
}

