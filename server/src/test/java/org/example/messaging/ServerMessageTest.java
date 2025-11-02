package org.example.messaging;

import com.google.protobuf.ByteString;
import org.example.MessageServiceOuterClass.*;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServerMessageTest {

    @Test
    void testClientRequestWrapper() {
        ClientRequest request = ClientRequest.newBuilder()
                .setClientId("client1")
                .setTimestamp(12345L)
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(request);

        assertNotNull(serverMessage.getMessage());
        assertEquals("ClientRequest", serverMessage.getMessageType());
        assertTrue(serverMessage.getViewNumber().isEmpty());
        assertTrue(serverMessage.getSequenceNumber().isEmpty());
        assertTrue(serverMessage.getDigest().isEmpty());
    }

    @Test
    void testPrePrepareRequestWrapper() {
        ByteString digest = ByteString.copyFromUtf8("test-digest");
        PrePrepareMessage prePrepareMessage = PrePrepareMessage.newBuilder()
                .setViewNumber(2L)
                .setSequenceNumber(100L)
                .setDigest(digest)
                .setSignerId("server1")
                .build();

        PrePrepareRequest request = PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMessage)
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(request);

        assertNotNull(serverMessage.getMessage());
        assertEquals("PrePrepareRequest", serverMessage.getMessageType());
        assertEquals(Optional.of(2L), serverMessage.getViewNumber());
        assertEquals(Optional.of(100L), serverMessage.getSequenceNumber());
        assertEquals(Optional.of(digest), serverMessage.getDigest());
    }

    @Test
    void testPrepareMessageWrapper() {
        ByteString digest = ByteString.copyFromUtf8("test-digest");
        PrepareMessage message = PrepareMessage.newBuilder()
                .setViewNumber(3L)
                .setSequenceNumber(200L)
                .setDigest(digest)
                .setIsAggregated(false)
                .setSignerId("server2")
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(message);

        assertNotNull(serverMessage.getMessage());
        assertEquals("PrepareMessage", serverMessage.getMessageType());
        assertEquals(Optional.of(3L), serverMessage.getViewNumber());
        assertEquals(Optional.of(200L), serverMessage.getSequenceNumber());
        assertEquals(Optional.of(digest), serverMessage.getDigest());
    }

    @Test
    void testPrePrepareRequestWithoutPrePrepareMessage() {
        PrePrepareRequest request = PrePrepareRequest.newBuilder().build();

        ServerMessage serverMessage = ServerMessage.wrap(request);

        assertNotNull(serverMessage.getMessage());
        assertTrue(serverMessage.getViewNumber().isEmpty());
        assertTrue(serverMessage.getSequenceNumber().isEmpty());
        assertTrue(serverMessage.getDigest().isEmpty());
    }

    @Test
    void testToDetailedStringForPrepareMessage() {
        // Create a PrepareMessage with all fields
        ByteString digest = ByteString.copyFromUtf8("test-digest");
        PrepareMessage message = PrepareMessage.newBuilder()
                .setViewNumber(3L)
                .setSequenceNumber(200L)
                .setDigest(digest)
                .setIsAggregated(false)
                .setSignerId("server2")
                .setSignature(ByteString.copyFromUtf8("sig"))
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(message);
        String result = serverMessage.toDetailedString();

        // Verify the string contains all expected fields
        assertTrue(result.contains("PrepareMessage"));
        assertTrue(result.contains("viewNumber=3"));
        assertTrue(result.contains("sequenceNumber=200"));
        assertTrue(result.contains("digest=test-digest"));
        assertTrue(result.contains("index=PrepareMessage:3:200"));
    }

    @Test
    void testToDetailedStringForClientRequest() {
        // Create a ClientRequest with client_id and timestamp
        ClientRequest request = ClientRequest.newBuilder()
                .setClientId("A")
                .setTimestamp(123456789L)
                .setOperation(Operation.newBuilder()
                        .setBalanceRequest(BalanceRequest.newBuilder()
                                .setAccountId("A")
                                .build())
                        .build())
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(request);
        String result = serverMessage.toDetailedString();

        // Verify the string contains all expected fields
        assertTrue(result.contains("ClientRequest"));
        assertTrue(result.contains("clientId=A"));
        assertTrue(result.contains("timestamp=123456789"));
        assertTrue(result.contains("index=ClientRequest:A:123456789"));
    }

    @Test
    void testToDetailedStringForClientReply() {
        // Create a ClientReply with view_number but no sequence_number
        ClientReply reply = ClientReply.newBuilder()
                .setViewNumber(7L)
                .setTimestamp(987654321L)
                .setClientId("B")
                .setServerId("n1")
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(reply);
        String result = serverMessage.toDetailedString();

        // Verify the string contains all expected fields
        assertTrue(result.contains("ClientReply"));
        assertTrue(result.contains("viewNumber=7"));
        assertTrue(result.contains("clientId=B"));
        assertTrue(result.contains("timestamp=987654321"));
        assertTrue(result.contains("index=ClientReply:7"));
    }

    @Test
    void testToDetailedStringForPrePrepareRequest() {
        // Create a PrePrepareRequest with nested message
        ByteString digest = ByteString.copyFromUtf8("nested-digest");
        PrePrepareMessage prePrepareMsg = PrePrepareMessage.newBuilder()
                .setViewNumber(5L)
                .setSequenceNumber(150L)
                .setDigest(digest)
                .setSignerId("server1")
                .build();

        PrePrepareRequest request = PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMsg)
                .setRequest(ByteString.copyFromUtf8("client-request"))
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(request);
        String result = serverMessage.toDetailedString();

        // Verify the string contains nested fields
        assertTrue(result.contains("PrePrepareRequest"));
        assertTrue(result.contains("viewNumber=5"));
        assertTrue(result.contains("sequenceNumber=150"));
        assertTrue(result.contains("digest=nested-digest"));
        assertTrue(result.contains("index=PrePrepareRequest:5:150"));
    }

    @Test
    void testGetMessageIndexForPrepareMessage() {
        // Test index for messages with view_number and sequence_number
        PrepareMessage message = PrepareMessage.newBuilder()
                .setViewNumber(3L)
                .setSequenceNumber(200L)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("server1")
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(message);
        String index = serverMessage.getMessageIndex();

        assertEquals("PrepareMessage:3:200", index);
    }

    @Test
    void testGetMessageIndexForPrePrepareRequest() {
        // Test index for nested message with view_number and sequence_number
        PrePrepareMessage prePrepareMessage = PrePrepareMessage.newBuilder()
                .setViewNumber(5L)
                .setSequenceNumber(150L)
                .setDigest(ByteString.copyFromUtf8("digest"))
                .setSignerId("server1")
                .build();

        PrePrepareRequest request = PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(prePrepareMessage)
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(request);
        String index = serverMessage.getMessageIndex();

        assertEquals("PrePrepareRequest:5:150", index);
    }

    @Test
    void testGetMessageIndexForClientRequest() {
        // Test index for messages with client_id and timestamp
        ClientRequest request = ClientRequest.newBuilder()
                .setClientId("A")
                .setTimestamp(123456789L)
                .setOperation(Operation.newBuilder()
                        .setBalanceRequest(BalanceRequest.newBuilder()
                                .setAccountId("A")
                                .build())
                        .build())
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(request);
        String index = serverMessage.getMessageIndex();

        assertEquals("ClientRequest:A:123456789", index);
    }

    @Test
    void testGetMessageIndexForClientReply() {
        // Test index for messages with view_number only (no sequence_number)
        ClientReply reply = ClientReply.newBuilder()
                .setViewNumber(7L)
                .setTimestamp(987654321L)
                .setClientId("B")
                .setServerId("n1")
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(reply);
        String index = serverMessage.getMessageIndex();

        // ClientReply has view_number but no sequence_number, so it should use view_number only
        assertEquals("ClientReply:7", index);
    }

    @Test
    void testGetMessageIndexForUnknownMessage() {
        // Test index for messages without recognized fields
        ActiveFlag flag = ActiveFlag.newBuilder()
                .setActiveFlag(true)
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(flag);
        String index = serverMessage.getMessageIndex();

        assertEquals("ActiveFlag:unknown", index);
    }

    @Test
    void testGetClientIdAndTimestampExtraction() {
        // Test that getClientId() and getTimestamp() work correctly
        ClientRequest request = ClientRequest.newBuilder()
                .setClientId("testClient")
                .setTimestamp(999999L)
                .build();

        ServerMessage serverMessage = ServerMessage.wrap(request);

        assertEquals(Optional.of("testClient"), serverMessage.getClientId());
        assertEquals(Optional.of(999999L), serverMessage.getTimestamp());
    }
}

