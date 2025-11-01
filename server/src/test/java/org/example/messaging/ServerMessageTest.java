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
}

