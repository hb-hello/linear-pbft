package org.example.testutil;

import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.PrepareSender;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.serverstate.ServerState;

/**
 * Mock PrepareSender for testing.
 * Provides both no-op behavior and message capturing capability.
 * Does not actually send messages over the network.
 */
public class MockPrepareSender extends PrepareSender {
    private final ServerState mockState; // Store reference to access state methods
    private MessageServiceOuterClass.PrepareMessage capturedPrepare;
    private String capturedTargetNodeId;
    private int sendCount = 0;

    public MockPrepareSender(String nodeId, ServerState state) {
        super(nodeId, state, null, null);
        this.mockState = state;
    }

    public MockPrepareSender(String nodeId, ServerState state, CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(nodeId, state, commLogger, auth);
        this.mockState = state;
    }

    @Override
    public void sendPrepare(long viewNumber, long sequenceNumber, byte[] digest) {
        // Build the prepare message (unsigned)
        org.example.MessageServiceOuterClass.PrepareMessage unsignedPrepare =
                org.example.MessageServiceOuterClass.PrepareMessage.newBuilder()
                        .setViewNumber(viewNumber)
                        .setSequenceNumber(sequenceNumber)
                        .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                        .build();

        // Sign the message first (if auth is available) so that signer_id is set
        if (auth != null) {
            capturedPrepare = (MessageServiceOuterClass.PrepareMessage) auth.sign(unsignedPrepare);
        } else {
            // For tests without auth, manually set signer_id
            capturedPrepare = unsignedPrepare.toBuilder()
                    .setSignerId(nodeId)
                    .setSignature(com.google.protobuf.ByteString.copyFromUtf8("mock-signature"))
                    .build();
        }

        // Append the signed message to state (same behavior as real PrepareSender)
        mockState.appendServerMessage(capturedPrepare);

        // Capture the collector ID (same as real PrepareSender)
        capturedTargetNodeId = mockState.getCollectorServerId();

        sendCount++;
        // No-op: don't actually send anything in tests
    }

    /**
     * Get the last captured prepare message (for test assertions).
     */
    public MessageServiceOuterClass.PrepareMessage getCapturedPrepare() {
        return capturedPrepare;
    }

    /**
     * Get the number of times sendPrepare was called.
     */
    public int getSendCount() {
        return sendCount;
    }

    /**
     * Get the captured target node ID (collector server ID).
     */
    public String getCapturedTargetNodeId() {
        return capturedTargetNodeId;
    }

    /**
     * Reset captured state for reuse in multiple test scenarios.
     */
    public void reset() {
        capturedPrepare = null;
        capturedTargetNodeId = null;
        sendCount = 0;
    }
}

