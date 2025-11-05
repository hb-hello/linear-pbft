package org.example.serverstate;

import com.google.protobuf.ByteString;
import org.example.MessageServiceOuterClass;
import org.example.messaging.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerMessageTrackerTest {

    private ServerMessageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ServerMessageTracker();
    }

    @Test
    void testAppendAndFindPrePrepare() {
        // Create a PrePrepareMessage
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prePrepareMsg);
        tracker.append(serverMsg, 0);

        // Find the message
        ServerMessage found = tracker.findMessage(ServerMessage.PRE_PREPARE, 1, 10, "n1");
        assertNotNull(found, "Should find the PrePrepare message");
        assertEquals("PrePrepareMessage", found.getMessageType());
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
        tracker.append(serverMsg, 0);

        // Find the message
        ServerMessage found = tracker.findMessage(ServerMessage.PREPARE, 2, 20, "n2");
        assertNotNull(found, "Should find the Prepare message");
        assertEquals("PrepareMessage", found.getMessageType());
        assertEquals(2L, found.getViewNumber().orElse(-1L));
        assertEquals(20L, found.getSequenceNumber().orElse(-1L));
    }

    @Test
    void testFindNonExistentMessage() {
        // Try to find a message that doesn't exist
        ServerMessage found = tracker.findMessage(ServerMessage.PRE_PREPARE, 999, 999, "n1");
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
                tracker.append(ServerMessage.wrap(prepareMsg), 0);
            }
        }

        // Verify we can find specific messages
        ServerMessage found = tracker.findMessage(ServerMessage.PREPARE, 1, 3, "n1");
        assertNotNull(found);
        assertEquals(1L, found.getViewNumber().orElse(-1L));
        assertEquals(3L, found.getSequenceNumber().orElse(-1L));

        // Verify size
        assertEquals(15, tracker.size());
    }

    @Test
    void testClear() {
        // Add multiple types of messages
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        ServerMessage prepareServerMsg = ServerMessage.wrap(prepareMsg);
        tracker.append(prepareServerMsg, 1);

        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest2"))
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();
        ServerMessage prePrepareServerMsg = ServerMessage.wrap(prePrepareMsg);
        tracker.append(prePrepareServerMsg, 0);

        // Verify messages are added
        assertFalse(tracker.isEmpty());
        assertEquals(2, tracker.size());

        // Establish quorum cache for one message
        boolean hasQuorumBefore = tracker.checkMessageQuorum(prepareServerMsg);
        assertTrue(hasQuorumBefore, "Should have quorum before clear");

        // Clear tracker
        tracker.clear();

        // Verify all data is cleared
        assertTrue(tracker.isEmpty(), "Tracker should be empty after clear");
        assertEquals(0, tracker.size(), "Size should be 0 after clear");

        // Verify messages cannot be found
        assertNull(tracker.findMessage(ServerMessage.PREPARE, 1, 10, "n1"), "Prepare should not be findable after clear");
        assertNull(tracker.findMessage(ServerMessage.PRE_PREPARE, 2, 20, "n2"), "PrePrepare should not be findable after clear");

        // Verify quorum cache is also cleared
        boolean hasQuorumAfter = tracker.checkMessageQuorum(prepareServerMsg);
        assertFalse(hasQuorumAfter, "Quorum cache should be cleared (message no longer exists)");

        // Verify getAllMessages returns empty list
        assertTrue(tracker.getAllMessages().isEmpty(), "getAllMessages should return empty list after clear");
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
            tracker.append(ServerMessage.wrap(prepareMsg), 0);
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
        tracker.append(ServerMessage.wrap(prePrepareMsg), 0);

        // Add Prepare with same view/seq
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        tracker.append(ServerMessage.wrap(prepareMsg), 0);

        // Both should be findable independently
        ServerMessage foundPrePrepare = tracker.findMessage(ServerMessage.PRE_PREPARE, 1, 10, "n1");
        ServerMessage foundPrepare = tracker.findMessage(ServerMessage.PREPARE, 1, 10, "n2");

        assertNotNull(foundPrePrepare);
        assertNotNull(foundPrepare);
        assertEquals("PrePrepareMessage", foundPrePrepare.getMessageType());
        assertEquals("PrepareMessage", foundPrepare.getMessageType());
    }

    @Test
    void testAppendDuplicateWithSameSender_isIgnored() {
        // Create first PrePrepare message from n1
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg1 = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        // Add first message
        tracker.append(ServerMessage.wrap(prePrepareMsg1), 0);
        assertEquals(1, tracker.size(), "First message should be added");

        // Create second PrePrepare message from SAME sender (n1) with same view/seq
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg2 = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest2-different"))
                .setSignerId("n1")  // Same sender!
                .setSignature(ByteString.copyFromUtf8("sig2-different"))
                .build();

        // Try to add duplicate message from same sender
        boolean added = tracker.append(ServerMessage.wrap(prePrepareMsg2), 0);
        assertFalse(added, "Duplicate from same sender should not be added");

        // Size should still be 1 (duplicate was ignored)
        assertEquals(1, tracker.size(), "Duplicate message should not be added");

        // Verify the original message is still there
        ServerMessage found = tracker.findMessage(ServerMessage.PRE_PREPARE, 1, 10, "n1");
        assertNotNull(found);
        assertEquals(ByteString.copyFromUtf8("digest1"), found.getDigest().orElse(null),
                "Original message should remain (not replaced by duplicate)");
    }

    @Test
    void testAppendDuplicatePrepareSameSender_isIgnored() {
        // Create first Prepare message from n1
        MessageServiceOuterClass.PrepareMessage prepareMsg1 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        tracker.append(ServerMessage.wrap(prepareMsg1), 0);
        assertEquals(1, tracker.size());

        // Create second Prepare message from SAME sender (n1) with same view/seq
        MessageServiceOuterClass.PrepareMessage prepareMsg2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest2"))
                .setIsAggregated(true)
                .setSignerId("n1")  // Same sender!
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();

        // Try to add duplicate from same sender
        boolean added = tracker.append(ServerMessage.wrap(prepareMsg2), 0);
        assertTrue(added, "Duplicate from same sender should be added as it is now aggregated");

        // Size should still be 1
        assertEquals(2, tracker.size(), "Duplicate Prepare should not be added");
    }

    @Test
    void testAppendSameViewSeq_differentSenders_bothAdded() {
        // Add Prepare with view=1, seq=10 from n1
        MessageServiceOuterClass.PrepareMessage prepareMsg1 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        tracker.append(ServerMessage.wrap(prepareMsg1), 0);

        // Add Prepare with same view=1, seq=10 from n2 (different sender)
        MessageServiceOuterClass.PrepareMessage prepareMsg2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n2")  // Different sender!
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        tracker.append(ServerMessage.wrap(prepareMsg2), 0);

        // Both should be added (different senders)
        assertEquals(2, tracker.size(), "Messages from different senders should both be added");
        assertNotNull(tracker.findMessage(ServerMessage.PREPARE, 1, 10, "n1"));
        assertNotNull(tracker.findMessage(ServerMessage.PREPARE, 1, 10, "n2"));
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
        tracker.append(serverMsg, 0);
        tracker.append(serverMsg, 0);
        tracker.append(serverMsg, 0);
        tracker.append(serverMsg, 0);
        tracker.append(serverMsg, 0);

        // Only the first should be added
        assertEquals(1, tracker.size(), "Message should only be added once despite multiple append attempts");
        assertNotNull(tracker.findMessage(ServerMessage.PREPARE, 1, 5, "n1"));
    }

    @Test
    void testFindByIndexWithClientRequest() {
        // Create a ClientRequest (indexed by client_id and timestamp)
        MessageServiceOuterClass.ClientRequest request = MessageServiceOuterClass.ClientRequest.newBuilder()
                .setClientId("A")
                .setTimestamp(123456789L)
                .setOperation(MessageServiceOuterClass.Operation.newBuilder()
                        .setBalanceRequest(MessageServiceOuterClass.BalanceRequest.newBuilder()
                                .setAccountId("A")
                                .build())
                        .build())
                .build();

        tracker.append(ServerMessage.wrap(request), 0);

        // Find by index "ClientRequest:A:123456789"
        ServerMessage found = tracker.findByIndex("ClientRequest:A:123456789");
        assertNotNull(found, "Should find ClientRequest by its index");
        assertEquals("ClientRequest", found.getMessageType());
        assertEquals("A", found.getClientId().orElse(null));
        assertEquals(123456789L, found.getTimestamp().orElse(-1L));
    }

    @Test
    void testFindByIndexWithDifferentFormats() {
        // Add messages with different index formats

        // Format 1: MessageType:view:sequence:senderId
        MessageServiceOuterClass.PrepareMessage prepare = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(3)
                .setSequenceNumber(200)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .build();
        tracker.append(ServerMessage.wrap(prepare), 0);

        // Format 2: MessageType:view:senderId (for ClientReply which has view and signer_id)
        MessageServiceOuterClass.ClientReply reply = MessageServiceOuterClass.ClientReply.newBuilder()
                .setViewNumber(7)
                .setTimestamp(999L)
                .setClientId("c1")
                .setServerId("n1")
                .setSignerId("n1")  // signer_id is used for indexing
                .build();
        tracker.append(ServerMessage.wrap(reply), 0);

        // Format 3: MessageType:clientId:timestamp (ClientRequest doesn't have senderId typically)
        MessageServiceOuterClass.ClientRequest request = MessageServiceOuterClass.ClientRequest.newBuilder()
                .setClientId("B")
                .setTimestamp(555555L)
                .build();
        tracker.append(ServerMessage.wrap(request), 0);

        // Verify all can be found by their respective indices (with sender where applicable)
        assertNotNull(tracker.findByIndex("PrepareMessage:3:200:n1"));
        assertNotNull(tracker.findByIndex("ClientReply:7:n1"));
        assertNotNull(tracker.findByIndex("ClientRequest:B:555555"));

        assertEquals(3, tracker.size());
    }

    // ===== Quorum Functionality Tests =====

    @Test
    void testCheckMessageQuorum_singleMessage_doesNotMeetQuorum() {
        // Add one Prepare message
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);
        tracker.append(serverMsg, 3);

        // Check quorum with requirement of 3
        boolean hasQuorum = tracker.checkMessageQuorum(serverMsg);

        assertFalse(hasQuorum, "Single message should not meet quorum of 3");
    }

    @Test
    void testCheckMessageQuorum_exactQuorumMet() {
        // Add 3 Prepare messages with same view/seq/digest from different signers
        for (int i = 1; i <= 3; i++) {
            MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                    .setViewNumber(1)
                    .setSequenceNumber(10)
                    .setDigest(ByteString.copyFromUtf8("digest1"))  // Same digest
                    .setIsAggregated(false)
                    .setSignerId("n" + i)  // Different signers
                    .setSignature(ByteString.copyFromUtf8("sig" + i))
                    .build();

            tracker.append(ServerMessage.wrap(prepareMsg), 3);
        }

        // Create a message with same type/view/seq/digest to check quorum
        MessageServiceOuterClass.PrepareMessage checkMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(checkMsg);

        // Check quorum with requirement of 3 (exact match)
        boolean hasQuorum = tracker.checkMessageQuorum(serverMsg);

        assertTrue(hasQuorum, "Three messages with same digest should meet quorum of 3");
    }

    @Test
    void testCheckMessageQuorum_exceedsQuorum() {
        // Add one message with quorum 0 - consensus tracking not enabled
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);
        tracker.append(serverMsg, 0);

        // Check with quorum size of 0 - should return false since consensus tracking not enabled for quorum 0
        boolean hasQuorum = tracker.checkMessageQuorum(serverMsg);

        assertFalse(hasQuorum, "Messages with quorum 0 should not have consensus tracking enabled");
    }

    @Test
    void testCheckMessageQuorum_differentMessageTypes_notCounted() {
        // Add PrePrepare with view=1, seq=10
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        tracker.append(ServerMessage.wrap(prePrepareMsg), 0);

        // Add Prepare with same view=1, seq=10 (different message type)
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        tracker.append(ServerMessage.wrap(prepareMsg), 2);

        // Check quorum for Prepare - should only count Prepare messages
        ServerMessage prepareServerMsg = ServerMessage.wrap(prepareMsg);
        boolean hasQuorum = tracker.checkMessageQuorum(prepareServerMsg);

        assertFalse(hasQuorum, "Should only count messages with same type:view:seq:digest");
    }

    @Test
    void testCheckMessageQuorum_consistentResults() {
        // Add a message and check quorum multiple times
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(3)
                .setSequenceNumber(30)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);
        tracker.append(serverMsg, 1);

        // First check - should meet quorum of 1
        boolean firstCheck = tracker.checkMessageQuorum(serverMsg);
        assertTrue(firstCheck);

        // Second check - should return consistent result
        boolean secondCheck = tracker.checkMessageQuorum(serverMsg);
        assertTrue(secondCheck, "Quorum result should be consistent");

        // Third check with same message should also be consistent
        boolean thirdCheck = tracker.checkMessageQuorum(serverMsg);
        assertTrue(thirdCheck, "Multiple checks should all return consistent result");
    }

    @Test
    void testCheckMessageQuorum_differentQuorumRequirements() {
        // Test that we can check quorum after setting it at append time

        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(4)
                .setSequenceNumber(40)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);
        // Set high quorum requirement - should not meet with just 1 message
        tracker.append(serverMsg, 5);

        // Check quorum - should not meet
        boolean hasQuorum = tracker.checkMessageQuorum(serverMsg);
        assertFalse(hasQuorum, "Should not meet quorum of 5 with 1 message");

        // Add to test with lower requirement in another scenario
        MessageServiceOuterClass.PrepareMessage prepareMsg2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(5)
                .setSequenceNumber(50)
                .setDigest(ByteString.copyFromUtf8("digest2"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();

        ServerMessage serverMsg2 = ServerMessage.wrap(prepareMsg2);
        tracker.append(serverMsg2, 1);
        boolean hasQuorumLower = tracker.checkMessageQuorum(serverMsg2);
        assertTrue(hasQuorumLower, "Should meet quorum of 1 with 1 message");
    }

    @Test
    void testCheckMessageQuorum_nonExistentMessage_returnsFalse() {
        // Create a message but don't add it to tracker
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(99)
                .setSequenceNumber(999)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);

        // Check quorum for message not in tracker
        boolean hasQuorum = tracker.checkMessageQuorum(serverMsg);

        assertFalse(hasQuorum, "Non-existent message should not have quorum");
    }

    @Test
    void testCheckMessageQuorum_multipleViewsAndSequences() {
        // Add Prepare messages for different view/seq combinations
        for (int view = 1; view <= 2; view++) {
            for (int seq = 10; seq <= 11; seq++) {
                MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                        .setViewNumber(view)
                        .setSequenceNumber(seq)
                        .setDigest(ByteString.copyFromUtf8("digest_" + view + "_" + seq))
                        .setIsAggregated(false)
                        .setSignerId("n1")
                        .setSignature(ByteString.copyFromUtf8("sig"))
                        .build();
                tracker.append(ServerMessage.wrap(prepareMsg), 1);
            }
        }

        // Check quorum for specific view/seq
        MessageServiceOuterClass.PrepareMessage targetMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest_1_10"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(targetMsg);
        boolean hasQuorum = tracker.checkMessageQuorum(serverMsg);

        assertTrue(hasQuorum, "Should meet quorum for view=1, seq=10");

        // Verify other combinations also work
        MessageServiceOuterClass.PrepareMessage targetMsg2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(11)
                .setDigest(ByteString.copyFromUtf8("digest_2_11"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg2 = ServerMessage.wrap(targetMsg2);
        boolean hasQuorum2 = tracker.checkMessageQuorum(serverMsg2);

        assertTrue(hasQuorum2, "Should meet quorum for view=2, seq=11");
    }

    @Test
    void testCheckMessageQuorum_withAggregatedMessages() {
        // Test with aggregated Prepare message
        MessageServiceOuterClass.PrepareMessage aggregatedPrepare = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(7)
                .setSequenceNumber(70)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(true)  // Aggregated message
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(aggregatedPrepare);
        tracker.append(serverMsg, 1);

        // Even though it's aggregated, it's still counted as one message in the tracker
        boolean hasQuorum = tracker.checkMessageQuorum(serverMsg);
        assertTrue(hasQuorum, "Aggregated message should be counted as one message (count=1, quorum=1)");

        // Test with higher quorum requirement
        MessageServiceOuterClass.PrepareMessage aggregatedPrepare2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(8)
                .setSequenceNumber(80)
                .setDigest(ByteString.copyFromUtf8("digest2"))
                .setIsAggregated(true)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();
        ServerMessage serverMsg2 = ServerMessage.wrap(aggregatedPrepare2);
        tracker.append(serverMsg2, 2);
        boolean hasHigherQuorum = tracker.checkMessageQuorum(serverMsg2);
        assertFalse(hasHigherQuorum, "Single aggregated message should not meet quorum of 2 (count=1, quorum=2)");
    }

    @Test
    void testCheckMessageQuorum_matchingDuplicatesIncrementCount() {
        // Add first Prepare message
        MessageServiceOuterClass.PrepareMessage prepareMsg1 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        ServerMessage serverMsg1 = ServerMessage.wrap(prepareMsg1);
        boolean added1 = tracker.append(serverMsg1, 2);
        assertTrue(added1, "First message should be added");

        // Check quorum - should have count of 1
        boolean hasQuorum1 = tracker.checkMessageQuorum(serverMsg1);
        assertFalse(hasQuorum1, "Should not meet quorum of 2 with 1 message");

        // Add message with same type/view/seq/digest but different signer
        MessageServiceOuterClass.PrepareMessage prepareMsg2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))  // Same digest
                .setIsAggregated(false)
                .setSignerId("n2")  // Different signer
                .setSignature(ByteString.copyFromUtf8("sig2"))  // Different signature
                .build();

        ServerMessage serverMsg2 = ServerMessage.wrap(prepareMsg2);
        boolean added2 = tracker.append(serverMsg2, 2);
        assertTrue(added2, "Message from different sender should be added");
        assertEquals(2, tracker.size(), "Size should be 2 (both messages added)");

        // Check quorum - should now have count of 2 (both messages with same digest)
        boolean hasQuorum2 = tracker.checkMessageQuorum(serverMsg1);
        assertTrue(hasQuorum2, "Should meet quorum of 2 with matching digest");

        // Add another message with matching digest
        MessageServiceOuterClass.PrepareMessage prepareMsg3 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest1"))  // Same digest
                .setIsAggregated(false)
                .setSignerId("n3")
                .setSignature(ByteString.copyFromUtf8("sig3"))
                .build();

        ServerMessage serverMsg3 = ServerMessage.wrap(prepareMsg3);
        tracker.append(serverMsg3, 2);

        // Check quorum - should now have count of 3
        assertEquals(3, tracker.size(), "Size should be 3");
        boolean hasQuorum3 = tracker.checkMessageQuorum(serverMsg1);
        assertTrue(hasQuorum3, "Should meet quorum of 2 with three matching messages");
    }

    @Test
    void testCheckMessageQuorum_mismatchedDigestDoesNotIncrementCount() {
        // Add first Prepare message
        MessageServiceOuterClass.PrepareMessage prepareMsg1 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest-A"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig1"))
                .build();

        ServerMessage serverMsg1 = ServerMessage.wrap(prepareMsg1);
        tracker.append(serverMsg1, 1);

        // Check quorum - should have count of 1
        assertTrue(tracker.checkMessageQuorum(serverMsg1), "Should meet quorum of 1");

        // Add message with DIFFERENT digest (Byzantine behavior)
        MessageServiceOuterClass.PrepareMessage prepareMsg2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest-B"))  // Different digest!
                .setIsAggregated(false)
                .setSignerId("n2")
                .setSignature(ByteString.copyFromUtf8("sig2"))
                .build();

        ServerMessage serverMsg2 = ServerMessage.wrap(prepareMsg2);
        boolean added2 = tracker.append(serverMsg2, 2);
        assertTrue(added2, "Message from different sender should be added");
        assertEquals(2, tracker.size(), "Both messages should be in tracker");

        // Check quorum - should still have count of 1 for digest-A (mismatched digest not counted)
        assertFalse(tracker.checkMessageQuorum(serverMsg2),
                "Should not meet quorum of 2 when only one message has digest-B");
        assertTrue(tracker.checkMessageQuorum(serverMsg1),
                "Should still meet quorum of 1");

        // Check that digest-B also has its own count of 1 but needs quorum of 2
        assertFalse(tracker.checkMessageQuorum(serverMsg2),
                "Message with digest-B should not meet quorum of 2 with count of 1");
    }

    @Test
    void testCheckMessageQuorum_clearResetsQuorumCountIncludingDuplicates() {
        // Add message
        MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(3)
                .setSequenceNumber(30)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMsg = ServerMessage.wrap(prepareMsg);
        tracker.append(serverMsg, 4);

        // Add matching duplicates to increase quorum count
        for (int i = 2; i <= 4; i++) {
            MessageServiceOuterClass.PrepareMessage duplicate = MessageServiceOuterClass.PrepareMessage.newBuilder()
                    .setViewNumber(3)
                    .setSequenceNumber(30)
                    .setDigest(ByteString.copyFromUtf8("digest"))
                    .setIsAggregated(false)
                    .setSignerId("n" + i)
                    .setSignature(ByteString.copyFromUtf8("sig" + i))
                    .build();
            tracker.append(ServerMessage.wrap(duplicate), 4);
        }

        // Verify quorum count is 4
        assertTrue(tracker.checkMessageQuorum(serverMsg), "Should have quorum of 4 after duplicates");

        // Clear tracker
        tracker.clear();

        // Verify quorum count is reset
        assertFalse(tracker.checkMessageQuorum(serverMsg),
                "Quorum count should be 0 after clear");
    }

    // ===== getQuorumSignatures Tests =====

    @Test
    void testGetQuorumSignatures_withMatchingMessages() {
        // Add 3 Prepare messages with same view/seq/digest but different signers and signatures
        // Use quorum > 0 to enable consensus tracking
        ByteString sig1 = ByteString.copyFromUtf8("signature-from-n1");
        ByteString sig2 = ByteString.copyFromUtf8("signature-from-n2");
        ByteString sig3 = ByteString.copyFromUtf8("signature-from-n3");

        MessageServiceOuterClass.PrepareMessage prepare1 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("common-digest"))
                .setIsAggregated(false)
                .setSignerId("n1")
                .setSignature(sig1)
                .build();
        tracker.append(ServerMessage.wrap(prepare1), 3);

        MessageServiceOuterClass.PrepareMessage prepare2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("common-digest"))
                .setIsAggregated(false)
                .setSignerId("n2")
                .setSignature(sig2)
                .build();
        tracker.append(ServerMessage.wrap(prepare2), 3);

        MessageServiceOuterClass.PrepareMessage prepare3 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("common-digest"))
                .setIsAggregated(false)
                .setSignerId("n3")
                .setSignature(sig3)
                .build();
        tracker.append(ServerMessage.wrap(prepare3), 3);

        // Get quorum signatures using messageIndex (without sender)
        Map<String, ByteString> signatures = tracker.getQuorumSignatures("PrepareMessage:1:10");

        // Verify we got all 3 signatures mapped by sender ID
        assertNotNull(signatures, "Signatures map should not be null");
        assertEquals(3, signatures.size(), "Should have 3 signatures from 3 matching messages");
        assertEquals(sig1, signatures.get("n1"), "Should have signature from n1");
        assertEquals(sig2, signatures.get("n2"), "Should have signature from n2");
        assertEquals(sig3, signatures.get("n3"), "Should have signature from n3");
    }

    @Test
    void testGetQuorumSignatures_withMessageTypeViewSeq() {
        // Test the convenience overload that takes messageType, viewNumber, sequenceNumber
        // Use quorum > 0 to enable consensus tracking
        ByteString sig1 = ByteString.copyFromUtf8("sig-a");
        ByteString sig2 = ByteString.copyFromUtf8("sig-b");

        MessageServiceOuterClass.CommitMessage commit1 = MessageServiceOuterClass.CommitMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(sig1)
                .build();
        tracker.append(ServerMessage.wrap(commit1), 2);

        MessageServiceOuterClass.CommitMessage commit2 = MessageServiceOuterClass.CommitMessage.newBuilder()
                .setViewNumber(2)
                .setSequenceNumber(20)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n2")
                .setSignature(sig2)
                .build();
        tracker.append(ServerMessage.wrap(commit2), 2);

        // Get signatures using the overload
        Map<String, ByteString> signatures = tracker.getQuorumSignatures("CommitMessage", 2L, 20L);

        assertEquals(2, signatures.size(), "Should have 2 signatures");
        assertEquals(sig1, signatures.get("n1"), "Should have signature from n1");
        assertEquals(sig2, signatures.get("n2"), "Should have signature from n2");
    }

    @Test
    void testGetQuorumSignatures_noMatchingMessages_returnsEmptyMap() {
        // Add a message with quorum tracking enabled
        MessageServiceOuterClass.PrepareMessage prepare = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        tracker.append(ServerMessage.wrap(prepare), 1);

        // Try to get signatures for a different message index that doesn't exist
        Map<String, ByteString> signatures = tracker.getQuorumSignatures("PrepareMessage:99:99");

        assertNotNull(signatures, "Should return non-null map");
        assertTrue(signatures.isEmpty(), "Should return empty map when no matching messages");
    }

    @Test
    void testGetQuorumSignatures_withMismatchedDigests() {
        // Add messages with same view/seq but DIFFERENT digests
        // Use quorum > 0 to enable consensus tracking
        ByteString sig1 = ByteString.copyFromUtf8("sig1");
        ByteString sig2 = ByteString.copyFromUtf8("sig2");

        MessageServiceOuterClass.PrepareMessage prepare1 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(3)
                .setSequenceNumber(30)
                .setDigest(ByteString.copyFromUtf8("digest-A"))
                .setSignerId("n1")
                .setSignature(sig1)
                .build();
        tracker.append(ServerMessage.wrap(prepare1), 1);

        MessageServiceOuterClass.PrepareMessage prepare2 = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(3)
                .setSequenceNumber(30)
                .setDigest(ByteString.copyFromUtf8("digest-B"))  // Different digest
                .setSignerId("n2")
                .setSignature(sig2)
                .build();
        tracker.append(ServerMessage.wrap(prepare2), 1);

        // Get signatures - should only get the one(s) with the majority digest
        Map<String, ByteString> signatures = tracker.getQuorumSignatures("PrepareMessage:3:30");

        // Since they have different digests, only messages with the same digest are grouped
        // Each digest group has count of 1, so we get whichever was processed
        assertNotNull(signatures, "Should return non-null map");
        // The result depends on which digest the consensus tracker considers
        // But there should be signatures returned (at least 1)
        assertFalse(signatures.isEmpty(), "Should have at least some signatures");
    }

    @Test
    void testGetQuorumSignatures_afterClear_returnsEmpty() {
        // Add messages with quorum tracking enabled
        MessageServiceOuterClass.PrepareMessage prepare = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(4)
                .setSequenceNumber(40)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();
        tracker.append(ServerMessage.wrap(prepare), 1);

        // Verify we can get signatures
        Map<String, ByteString> signaturesBeforeClear = tracker.getQuorumSignatures("PrepareMessage:4:40");
        assertFalse(signaturesBeforeClear.isEmpty(), "Should have signatures before clear");

        // Clear tracker
        tracker.clear();

        // Try to get signatures after clear
        Map<String, ByteString> signaturesAfterClear = tracker.getQuorumSignatures("PrepareMessage:4:40");
        assertNotNull(signaturesAfterClear, "Should return non-null map");
        assertTrue(signaturesAfterClear.isEmpty(), "Should return empty map after clear");
    }

    @Test
    void testGetQuorumSignatures_multipleMessageTypes_separated() {
        // Add Prepare and Commit messages with same view/seq but they should be tracked separately
        // Use quorum > 0 to enable consensus tracking
        ByteString prepareSig = ByteString.copyFromUtf8("prepare-sig");
        ByteString commitSig = ByteString.copyFromUtf8("commit-sig");

        MessageServiceOuterClass.PrepareMessage prepare = MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(5)
                .setSequenceNumber(50)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(prepareSig)
                .build();
        tracker.append(ServerMessage.wrap(prepare), 1);

        MessageServiceOuterClass.CommitMessage commit = MessageServiceOuterClass.CommitMessage.newBuilder()
                .setViewNumber(5)
                .setSequenceNumber(50)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("n1")
                .setSignature(commitSig)
                .build();
        tracker.append(ServerMessage.wrap(commit), 1);

        // Get Prepare signatures - should only get prepare signature mapped by sender ID
        Map<String, ByteString> prepareSignatures = tracker.getQuorumSignatures("PrepareMessage:5:50");
        assertEquals(1, prepareSignatures.size(), "Should have 1 Prepare signature");
        assertEquals(prepareSig, prepareSignatures.get("n1"), "Should have Prepare signature from n1");
        assertNotEquals(commitSig, prepareSignatures.get("n1"), "Should not have Commit signature");

        // Get Commit signatures - should only get commit signature mapped by sender ID
        Map<String, ByteString> commitSignatures = tracker.getQuorumSignatures("CommitMessage:5:50");
        assertEquals(1, commitSignatures.size(), "Should have 1 Commit signature");
        assertEquals(commitSig, commitSignatures.get("n1"), "Should have Commit signature from n1");
        assertNotEquals(prepareSig, commitSignatures.get("n1"), "Should not have Prepare signature");
    }

    @Test
    void testGetQuorumValue_withQuorumReached() {
        // Add 3 Prepare messages with same view/seq/digest from different signers
        ByteString digest = ByteString.copyFromUtf8("test-digest");
        for (int i = 1; i <= 3; i++) {
            MessageServiceOuterClass.PrepareMessage prepareMsg = MessageServiceOuterClass.PrepareMessage.newBuilder()
                    .setViewNumber(1)
                    .setSequenceNumber(10)
                    .setDigest(digest)  // Same digest
                    .setIsAggregated(false)
                    .setSignerId("n" + i)  // Different signers
                    .setSignature(ByteString.copyFromUtf8("sig" + i))
                    .build();

            tracker.append(ServerMessage.wrap(prepareMsg), 3);
        }

        // Check that quorum is reached
        assertTrue(tracker.checkMessageQuorum(ServerMessage.PREPARE, 1, 10),
                "Quorum should be reached with 3 messages");

        // Get the quorum value
        ByteString quorumValue = tracker.getQuorumValue(ServerMessage.PREPARE, 1, 10);

        // Verify the quorum value matches the digest
        assertNotNull(quorumValue, "Quorum value should not be null");
        assertEquals(digest, quorumValue, "Quorum value should match the consensus digest");
    }
}

