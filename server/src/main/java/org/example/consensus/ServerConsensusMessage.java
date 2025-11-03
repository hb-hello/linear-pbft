package org.example.consensus;

import org.example.messaging.ServerMessage;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Specialized ConsensusMessage for server-to-server communication using ServerMessage.
 * Uses ServerMessage's getMessageIndex() for request identification and extractStringField("signer_id")
 * for responder identification.
 *
 * @param <V> value-key type used to group equivalent responses (e.g., Boolean, String, digest)
 */
public class ServerConsensusMessage<V> extends ConsensusMessage<String, V> {

    // Track all message indices with sender IDs for this consensus message
    private final Set<String> messageIndicesWithSender = new HashSet<>();

    /**
     * Creates a consensus message collector for ServerMessage responses.
     *
     * @param requestId      the unique request identifier (typically from ServerMessage.getMessageIndex())
     * @param valueExtractor function to extract the value key from a ServerMessage for grouping responses
     */
    public ServerConsensusMessage(String requestId, Function<ServerMessage, V> valueExtractor) {
        super(requestId,
                // Extract request ID using ServerMessage.getMessageIndex()
                msg -> ServerMessage.wrap(msg).getMessageIndex(),
                // Extract responder ID using signer_id field
                msg -> ServerMessage.wrap(msg).getSenderId().orElse("unknown"),
                // Wrap Message to ServerMessage and apply the provided valueExtractor
                msg -> valueExtractor.apply(ServerMessage.wrap(msg)));
    }

    /**
     * Add a messageIndexWithSender to the tracked set.
     * This should be called when a message is added to this consensus tracker.
     *
     * @param messageIndexWithSender the full message index including sender ID
     */
    public void addMessageIndexWithSender(String messageIndexWithSender) {
        messageIndicesWithSender.add(messageIndexWithSender);
    }

    /**
     * Get an immutable copy of all messageIndexWithSender entries tracked for this consensus.
     *
     * @return set of all message indices with sender IDs
     */
    public Set<String> getMessageIndicesWithSender() {
        return Set.copyOf(messageIndicesWithSender);
    }
}
