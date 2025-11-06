package org.example.consensus;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.example.messaging.ServerMessage;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Specialized ConsensusMessageTracker for server-to-server communication using ServerMessage.
 * Tracks consensus progress keyed by String request identifiers (from ServerMessage.getMessageIndex()).
 *
 * Simplifies the API by working directly with ServerMessage instead of generic Message,
 * automatically handling request ID and responder ID extraction using ServerMessage methods.
 * Uses digest string as the default value extractor (compares by digest).
 *
 * Overrides the parent's tracked map to use ServerConsensusMessage instances for messageIndexWithSender tracking.
 */
public class ServerConsensusMessageTracker extends ConsensusMessageTracker<String, ByteString> {

    // Override parent's tracked field to use ServerConsensusMessage instances
    // This allows us to access ServerConsensusMessage-specific methods like addMessageIndexWithSender
    @SuppressWarnings("unchecked")
    protected final java.util.concurrent.ConcurrentMap<String, ServerConsensusMessage<ByteString>> tracked =
            (java.util.concurrent.ConcurrentMap<String, ServerConsensusMessage<ByteString>>) (java.util.concurrent.ConcurrentMap<?, ?>) super.tracked;

    /**
     * Creates a ServerConsensusMessageTracker that uses digest comparison as the consensus value.
     * Automatically uses ServerMessage.getMessageIndex() for request ID extraction
     * and extractStringField("signer_id") for responder identification.
     * Uses digest as a hex string for proper equality comparison.
     */
    public ServerConsensusMessageTracker() {
        super(
                // Extract request ID using ServerMessage.getMessageIndex()
                msg -> ServerMessage.wrap(msg).getMessageIndex(),
                // Extract responder ID using signer_id field
                msg -> ServerMessage.wrap(msg).getSenderId().orElse("unknown"),
                // Extract value using digest as hex string for proper equality
                msg -> {
                    return ServerMessage.wrap(msg).getDigest().orElse(ByteString.EMPTY);
                }
        );
    }


    /**
     * Record an incoming ServerMessage response by request id for O(1) lookup.
     * Implicitly creates consensus message if needed.
     * Also tracks the messageIndexWithSender for this consensus.
     *
     * @param requestId the request identifier
     * @param reply the ServerMessage reply
     * @return true (always, as the reply is always recorded)
     */
    public boolean recordMessage(String requestId, ServerMessage reply, int required) {
        // Use parent's recordMessage to handle the consensus message creation and reply tracking
        // Note: Parent creates generic ConsensusMessage, but we need ServerConsensusMessage for messageIndexWithSender
        // So we override the tracked field to store ServerConsensusMessage instances instead

        // First ensure we have a ServerConsensusMessage (not just ConsensusMessage)
        tracked.computeIfAbsent(requestId, id -> {
            System.out.println("Implicitly creating ServerConsensusMessage tracker when recording reply for requestId=" + id);
            return new ServerConsensusMessage<>(id, required, msg -> msg.getDigest().orElse(ByteString.EMPTY));
        });

        // Now use parent's recordMessage to add the reply
//        System.out.println("Recording message for requestId=" + requestId + " using ServerConsensusMessageTracker");
        super.recordMessage(requestId, reply.getMessage(), required);

        // Track the messageIndexWithSender
        ServerConsensusMessage<ByteString> serverConsensusMsg = tracked.get(requestId);
        if (serverConsensusMsg != null) {
            String messageIndexWithSender = reply.getMessageIndexWithSender();
            serverConsensusMsg.addMessageIndexWithSender(messageIndexWithSender);
        }

        return true;
    }

    /**
     * Record an incoming ServerMessage response and check if quorum was reached.
     * Implicitly creates consensus message if needed.
     * Also tracks the messageIndexWithSender for this consensus.
     *
     * @param requestId the request identifier
     * @param reply the ServerMessage reply
     * @param required the number of matching responses required for consensus
     * @return true if quorum was reached after adding this reply, false otherwise
     */
    public boolean recordMessageAndCheckQuorum(String requestId, ServerMessage reply, int required) {
        // Record the message (which also tracks messageIndexWithSender)
        recordMessage(requestId, reply, required);

        // Get the ServerConsensusMessage and check if quorum was reached
        return this.checkMessageQuorum(requestId);
    }

    /**
     * Check if a quorum of messages with the same request ID exists.
     *
     * @param requestId the request identifier
     * @return true if quorum is met, false otherwise
     */
    public boolean checkMessageQuorum(String requestId) {
        ServerConsensusMessage<ByteString> serverConsensusMsg = tracked.get(requestId);
        return serverConsensusMsg != null && serverConsensusMsg.checkQuorum();
    }

    /**
     * Block until N matching replies are received for requestId, or timeout occurs.
     * Returns the reply as a ServerMessage for easier access to fields.
     * Implicitly creates consensus message if needed.
     *
     * @param requestId the request identifier to wait for
     * @param timeout maximum time to wait
     * @param required the number of matching responses required for consensus
     * @return the consensus ServerMessage
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException if the timeout expires before consensus is reached
     */
    public ServerMessage awaitConsensusAsServerMessage(String requestId, Duration timeout, int required)
            throws InterruptedException, TimeoutException {
        Message reply = super.awaitConsensus(requestId, timeout, required);
        return ServerMessage.wrap(reply);
    }

    /**
     * Block until N matching replies are received for requestId, or timeout occurs.
     * Returns the raw protobuf Message.
     * Implicitly creates consensus message if needed.
     *
     * @param requestId the request identifier to wait for
     * @param timeout maximum time to wait
     * @param required the number of matching responses required for consensus
     * @return the consensus Message
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException if the timeout expires before consensus is reached
     */
    @Override
    public Message awaitConsensus(String requestId, Duration timeout, int required)
            throws InterruptedException, TimeoutException {
        return super.awaitConsensus(requestId, timeout, required);
    }

    /**
     * Get the set of messageIndexWithSender for a given requestId.
     *
     * @param requestId the request identifier
     * @return immutable copy of the set of messageIndexWithSender, or empty set if none tracked
     */
    public Set<String> getMessageIndicesWithSender(String requestId) {
        ServerConsensusMessage<ByteString> serverConsensusMsg = tracked.get(requestId);
        return serverConsensusMsg != null ? serverConsensusMsg.getMessageIndicesWithSender() : Set.of();
    }

    /**
     * Get the count of messageIndexWithSender tracked for a given requestId.
     *
     * @param requestId the request identifier
     * @return count of tracked messages, or 0 if none tracked
     */
    public int getMessageIndicesWithSenderCount(String requestId) {
        ServerConsensusMessage<ByteString> serverConsensusMsg = tracked.get(requestId);
        return serverConsensusMsg != null ? serverConsensusMsg.getMessageIndicesWithSender().size() : 0;
    }
}

