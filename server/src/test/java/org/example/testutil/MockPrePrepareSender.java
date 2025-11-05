package org.example.testutil;

import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.PrePrepareSender;
import org.example.consensus.senders.PrepareSender;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageUtil;
import org.example.serverstate.ServerState;


/**
 * Mock PrePrepareSender for testing.
 * Captures broadcast messages without actually sending them over the network.
 */
public class MockPrePrepareSender extends PrePrepareSender {
    private final ServerState mockState; // Store our own reference since parent's is private
    private MessageServiceOuterClass.PrePrepareRequest capturedRequest;
    private int attemptCount = 0;

    public MockPrePrepareSender(String nodeId, ServerState state, CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(nodeId, state, commLogger, auth, new MockPrepareSender(nodeId, state));
        this.mockState = state;
    }

    @Override
    public void attemptPrePrepare(MessageServiceOuterClass.ClientRequest clientRequest) {
        attemptCount++;

        // Only build the PrePrepare if conditions are met (same checks as parent)
        if (!isActive() || !mockState.isPrimary() || mockState.isFaulty()) {
            capturedRequest = null;
            return;
        }

        try {
            // Build PrePrepare message (same logic as parent but without broadcasting)
            long viewNumber = mockState.getViewNumber();
            long sequenceNumber = mockState.nextSeq();
            byte[] digest = MessageUtil.generateDigest(clientRequest);

            MessageServiceOuterClass.PrePrepareMessage prePrepareMessage =
                    MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                            .setViewNumber(viewNumber)
                            .setSequenceNumber(sequenceNumber)
                            .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                            .build();

            capturedRequest = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                    .setPrePrepareMessage(prePrepareMessage)
                    .setRequest(clientRequest)
                    .build();

            // Note: We don't call super.attemptPrePrepare to avoid actual networking
        } catch (Exception e) {
            throw new RuntimeException("Failed to build PrePrepare", e);
        }
    }

    /**
     * Get the last captured PrePrepareRequest (for test assertions).
     */
    public MessageServiceOuterClass.PrePrepareRequest getCapturedRequest() {
        return capturedRequest;
    }

    /**
     * Get the number of times attemptPrePrepare was called.
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Reset captured state for reuse in multiple test scenarios.
     */
    public void reset() {
        capturedRequest = null;
        attemptCount = 0;
    }
}

