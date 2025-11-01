package org.example.serverstate;

import com.google.protobuf.ByteString;
import org.example.MessageServiceOuterClass;
import org.example.messaging.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerMessageTrackerTest {

    private ServerMessageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ServerMessageTracker();
    }

    @Test
    void testAppendAndFindPrePrepare() {
        // Create a PrePrepareRequest message
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMsg)
                .setRequest(ByteString.copyFromUtf8("request1"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prePrepareRequest);
        tracker.append(serverMsg);

        // Find the message
        ServerMessage found = tracker.findPrePrepare(1, 10);
        assertNotNull(found, "Should find the PrePrepare message");
        assertEquals("PrePrepareRequest", found.getMessageType());
        assertEquals(1L, found.getViewNumber().orElse(-1L));
        assertEquals(10L, found.getSequenceNumber().orElse(-1L));
    }

    @Test
    void testAppendAndFindPrepare() {
        // Create a PrepareMessage
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest2"))
                .setIsAggregated(false)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);
        tracker.append(serverMsg);

        // Find the message
        ServerMessage found = tracker.findPrepare(2, 20);
        assertNotNull(found, "Should find the Prepare message");
        assertEquals("PrepareMessage", found.getMessageType());
        assertEquals(2L, found.getViewNumber().orElse(-1L));
        assertEquals(20L, found.getSequenceNumber().orElse(-1L));
    }

    @Test
    void testFindNonExistentMessage() {
        // Try to find a message that doesn't exist
        ServerMessage found = tracker.findPrePrepare(999, 999);
        assertNull(found, "Should not find non-existent message");
    }

    @Test
    void testMultipleMessagesWithDifferentViewsAndSequences() {
        // Add multiple messages
        for (int view = 0; view < 3; view++) {
            for (int seq = 1; seq <= 5; seq++) {
                MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                        .setViewNumber(view)
                        .setSequenceNumber(seq)
                        .setDigest(ByteString.copyFromUtf8("digest_" + view + "_" + seq))
                        .setIsAggregated(false)
                        .setSignerId("n1")
                        .setSignature(ByteString.copyFromUtf8("sig"))
                        .build();
                tracker.append(ServerMessage.wrap(prepareMsg));
            }
        }

        // Verify we can find specific messages
        ServerMessage found = tracker.findPrepare(1, 3);
        assertNotNull(found);
        assertEquals(1L, found.getViewNumber().orElse(-1L));
        assertEquals(3L, found.getSequenceNumber().orElse(-1L));

        // Verify size
        assertEquals(15, tracker.size());
    }

    @Test
    void testClear() {
        // Add some messages
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        tracker.append(ServerMessage.wrap(prepareMsg));

        assertFalse(tracker.isEmpty());
        assertEquals(1, tracker.size());

        // Clear
        tracker.clear();

        assertTrue(tracker.isEmpty());
        assertEquals(0, tracker.size());
        assertNull(tracker.findPrepare(1, 10));
    }

    @Test
    void testGetAllMessages() {
        // Add messages in order
        for (int i = 1; i <= 3; i++) {
            MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                    .setViewNumber(0)
                    .setSequenceNumber(i)
                    .setDigest(ByteString.copyFromUtf8("digest" + i))
                    .setIsAggregated(false)
                    .setSignerId("n1")
                    .setSignature(ByteString.copyFromUtf8("sig"))
                    .build();
            tracker.append(ServerMessage.wrap(prepareMsg));
        }

        var allMessages = tracker.getAllMessages();
        assertEquals(3, allMessages.size());

        // Verify insertion order
        assertEquals(1L, allMessages.get(0).getSequenceNumber().orElse(-1L));
        assertEquals(2L, allMessages.get(1).getSequenceNumber().orElse(-1L));
        assertEquals(3L, allMessages.get(2).getSequenceNumber().orElse(-1L));
    }

    @Test
    void testDifferentMessageTypes() {
        // Add PrePrepare
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMsg)
                .setRequest(ByteString.copyFromUtf8("request"))
                .build();
        tracker.append(ServerMessage.wrap(prePrepareRequest));

        // Add Prepare with same view/seq
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        tracker.append(ServerMessage.wrap(prepareMsg));

        // Both should be findable independently
        ServerMessage foundPrePrepare = tracker.findPrePrepare(1, 10);
        ServerMessage foundPrepare = tracker.findPrepare(1, 10);

        assertNotNull(foundPrePrepare);
        assertNotNull(foundPrepare);
        assertEquals("PrePrepareRequest", foundPrePrepare.getMessageType());
        assertEquals("PrepareMessage", foundPrepare.getMessageType());
    }

    @Test
    void testMessageWithoutViewOrSequence() {
        // Create a ClientReply (doesn't have view/sequence in our supported list)
        MessageServiceOuterClass.ClientReply clientReply = MessageServiceOuterClass.ClientReply.newBuilder()
                .setViewNumber(1)
                .setTimestamp(12345L)
                .setClientId("c1")
                .setServerId("n1")
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(clientReply);
        tracker.append(serverMsg);

        // Message is added to allMessages but not indexed (view/sequence extraction returns empty)
        assertEquals(1, tracker.size());

        // Should not be findable by view/seq (since it's not a supported message type)
        ServerMessage found = tracker.findMessage("ClientReply", 1, 0);
        assertNull(found, "ClientReply should not be indexed");
    }

    @Test
    void testAppendDuplicateWithSameTypeViewSeq_isIgnored() {
        // Create first PrePrepare message
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg1 = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest1 = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMsg1)
                .setRequest(ByteString.copyFromUtf8("request1"))
                .build();

        // Add first message
        tracker.append(ServerMessage.wrap(prePrepareRequest1));
        assertEquals(1, tracker.size(), "First message should be added");

        // Create second PrePrepare message with same view/seq but different content
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg2 = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest2-different"))
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig2-different"))
                .build();

        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest2 = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMsg2)
                .setRequest(ByteString.copyFromUtf8("request2-different"))
                .build();

        // Try to add second message (duplicate)
        tracker.append(ServerMessage.wrap(prePrepareRequest2));

        // Size should still be 1 (duplicate was ignored)
        assertEquals(1, tracker.size(), "Duplicate message should not be added");

        // Verify the original message is still there (not replaced)
        ServerMessage found = tracker.findPrePrepare(1, 10);
        assertNotNull(found);
        assertEquals(ByteString.copyFromUtf8("digest1"), found.getDigest().orElse(null),
                "Original message should remain (not replaced by duplicate)");
    }

    @Test
    void testAppendDuplicatePrepare_isIgnored() {
        // Create first Prepare message
        MessageServiceOuterClass.PrepareMessage prepareMsg1 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        tracker.append(ServerMessage.wrap(prepareMsg1));
        assertEquals(1, tracker.size());

        // Create second Prepare message with same view/seq
        MessageServiceOuterClass.PrepareMessage prepareMsg2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest2"))
                .setIsAggregated(true)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();

        // Try to add duplicate
        tracker.append(ServerMessage.wrap(prepareMsg2));

        // Size should still be 1
        assertEquals(1, tracker.size(), "Duplicate Prepare should not be added");
    }

    @Test
    void testAppendSameViewSeq_differentMessageTypes_bothAdded() {
        // Add PrePrepare with view=1, seq=10
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        MessageServiceOuterClass.PrePrepareRequest prePrepareRequest = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMsg)
                .setRequest(ByteString.copyFromUtf8("request"))
                .build();

        tracker.append(ServerMessage.wrap(prePrepareRequest));

        // Add Prepare with same view=1, seq=10
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        tracker.append(ServerMessage.wrap(prepareMsg));

        // Both should be added (different message types)
        assertEquals(2, tracker.size(), "Different message types with same view/seq should both be added");
        assertNotNull(tracker.findPrePrepare(1, 10));
        assertNotNull(tracker.findPrepare(1, 10));
    }

    @Test
    void testAppendDuplicateMultipleTimes_onlyOneAdded() {
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(5)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);

        // Try to add same message 5 times
        tracker.append(serverMsg);
        tracker.append(serverMsg);
        tracker.append(serverMsg);
        tracker.append(serverMsg);
        tracker.append(serverMsg);

        // Only the first should be added
        assertEquals(1, tracker.size(), "Message should only be added once despite multiple append attempts");
        assertNotNull(tracker.findPrepare(1, 5));
    }

    @Test
    void testAppendMessagesWithoutViewSeq_noDeduplication() {
        // Create ClientReply messages without indexed view/seq (not in supported types)
        MessageServiceOuterClass.ClientReply reply1 = MessageServiceOuterClass.ClientReply.newBuilder()
                .setViewNumber(1)
                .setTimestamp(1000L)
                .setClientId("c1")
                .setServerId("n1")
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        MessageServiceOuterClass.ClientReply reply2 = MessageServiceOuterClass.ClientReply.newBuilder()
                .setViewNumber(1)
                .setTimestamp(2000L)
                .setClientId("c1")
                .setServerId("n1")
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();

        // Both should be added (no deduplication for unindexed messages)
        tracker.append(ServerMessage.wrap(reply1));
        tracker.append(ServerMessage.wrap(reply2));

        assertEquals(2, tracker.size(),
                "Messages without indexed view/seq should not be deduplicated");
    }
}

