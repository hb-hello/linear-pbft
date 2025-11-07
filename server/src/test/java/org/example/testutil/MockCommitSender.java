package org.example.testutil;

import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.CommitSender;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.serverstate.ServerState;

/**
 * Mock CommitSender for testing.
 * Captures sent Commit messages without actually sending them over the network.
 * Note: This mock needs to override sendWithoutSigning to capture messages while preserving
 * the isPrepared() check logic from the parent class.
 */
public class MockCommitSender extends CommitSender {
    private MessageServiceOuterClass.CommitMessage capturedCommit;
    private int sendCount = 0;

    public MockCommitSender(String nodeId, int quorumSize, ServerState state) {
        super(nodeId, quorumSize, null, state, null, new MessageAuthenticator(nodeId), null);
    }

    public MockCommitSender(String nodeId, int quorumSize, ServerState state, CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(nodeId, quorumSize, null, state, commLogger, auth != null ? auth : new MessageAuthenticator(nodeId), null);
    }

    @Override
    protected void send(String targetNodeId, com.google.protobuf.Message signedMessage,
                        java.util.function.BiConsumer<org.example.MessageServiceGrpc.MessageServiceFutureStub, com.google.protobuf.Message> method) {
        // Capture the signed commit message for test verification
        if (signedMessage instanceof MessageServiceOuterClass.CommitMessage) {
            capturedCommit = (MessageServiceOuterClass.CommitMessage) signedMessage;
            sendCount++;
        }
        // No-op: don't actually send anything in tests
    }

    /**
     * Get the last captured commit message (for test assertions).
     */
    public MessageServiceOuterClass.CommitMessage getCapturedCommit() {
        return capturedCommit;
    }

    /**
     * Get the number of times attemptCommit was called.
     */
    public int getSendCount() {
        return sendCount;
    }

    /**
     * Reset captured state for reuse in multiple test scenarios.
     */
    public void reset() {
        capturedCommit = null;
        sendCount = 0;
    }
}

