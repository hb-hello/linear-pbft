package org.example.consensus;

import org.example.messaging.ServerMessage;

/**
 * Specialized ConsensusMessage for server-to-server communication using ServerMessage.
 * Uses ServerMessage's getMessageIndex() for request identification and extractStringField("signer_id")
 * for responder identification.
 *
 * @param <V> value-key type used to group equivalent responses (e.g., Boolean, String, digest)
 */
public class ServerConsensusMessage<V> extends ConsensusMessage<String, V> {

    /**
     * Creates a consensus message collector for ServerMessage responses.
     *
     * @param requestId the unique request identifier (typically from ServerMessage.getMessageIndex())
     * @param valueExtractor function to extract the value key from a ServerMessage for grouping responses
     */
    public ServerConsensusMessage(String requestId,
                                  java.util.function.Function<ServerMessage, V> valueExtractor) {
        super(
                requestId,
                // Extract request ID using ServerMessage.getMessageIndex()
                msg -> ServerMessage.wrap(msg).getMessageIndex(),
                // Extract responder ID using signer_id field
                msg -> ServerMessage.wrap(msg).getSenderId().orElse("unknown"),
                // Wrap Message to ServerMessage and apply the provided valueExtractor
                msg -> valueExtractor.apply(ServerMessage.wrap(msg))
        );
    }
}
