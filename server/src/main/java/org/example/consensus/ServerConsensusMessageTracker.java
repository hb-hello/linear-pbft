package org.example.consensus;

import com.google.protobuf.Message;
import org.example.messaging.ServerMessage;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Specialized ConsensusMessageTracker for server-to-server communication using ServerMessage.
 * Tracks consensus progress keyed by String request identifiers (from ServerMessage.getMessageIndex()).
 *
 * Simplifies the API by working directly with ServerMessage instead of generic Message,
 * automatically handling request ID and responder ID extraction using ServerMessage methods.
 * Uses digest string as the default value extractor (compares by digest).
 */
public class ServerConsensusMessageTracker extends ConsensusMessageTracker<String, String> {

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
                    com.google.protobuf.ByteString digest = ServerMessage.wrap(msg).getDigest().orElse(com.google.protobuf.ByteString.EMPTY);
                    return digest.toStringUtf8();
                }
        );
    }

    /**
     * Record an incoming ServerMessage response by request id for O(1) lookup.
     * Implicitly creates consensus message if needed.
     *
     * @param requestId the request identifier
     * @param reply the ServerMessage reply
     * @return true (always, as the reply is always recorded)
     */
    public boolean recordMessage(String requestId, ServerMessage reply) {
        return super.recordMessage(requestId, reply.getMessage());
    }

    /**
     * Record an incoming ServerMessage response and check if quorum was reached.
     * Implicitly creates consensus message if needed.
     *
     * @param requestId the request identifier
     * @param reply the ServerMessage reply
     * @param required the number of matching responses required for consensus
     * @return true if quorum was reached after adding this reply, false otherwise
     */
    public boolean recordMessageAndCheckQuorum(String requestId, ServerMessage reply, int required) {
        return super.recordMessageAndCheckQuorum(requestId, reply.getMessage(), required);
    }

    /**
     * Check if a quorum of messages with the same request ID exists.
     *
     * @param requestId the request identifier
     * @param quorumSize the required quorum size
     * @return true if quorum is met, false otherwise
     */
    public boolean checkMessageQuorum(String requestId, int quorumSize) {
        return super.checkMessageQuorum(requestId, quorumSize);
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
}

