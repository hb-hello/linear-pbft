package org.example.serverstate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.messaging.ServerMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks server-to-server PBFT messages with efficient lookup using message indices.
 * Uses ServerMessage.getMessageIndex() for simplified indexing based on message type and relevant fields.
 *
 * Note: This class is NOT thread-safe. It relies on external synchronization provided by ServerState,
 * which serializes all access through a single-threaded executor.
 */
public class ServerMessageTracker {

    private static final Logger logger = LogManager.getLogger(ServerMessageTracker.class);

    // All messages in insertion order
    private final List<ServerMessage> allMessages = new ArrayList<>();

    // Simple index: messageIndex -> message (using ServerMessage.getMessageIndex())
    private final Map<String, ServerMessage> index = new HashMap<>();

    /**
     * Append a server message to the tracker.
     * Indexes it using the message's getMessageIndex() method.
     * If a message with the same index already exists, it will not be added again.
     *
     * @param message The server message to append
     * @return true if the message was successfully appended, false if it was a duplicate and skipped
     */
    public boolean append(ServerMessage message) {
        String messageIndex = message.getMessageIndex();

        // Check for duplicate before adding
        if (index.containsKey(messageIndex)) {
            logger.debug("Duplicate message detected: index={}. Skipping addition.", messageIndex);
            return false;
        }

        // Add to list and index
        allMessages.add(message);
        index.put(messageIndex, message);

        logger.debug("Appended and indexed message: {}", messageIndex);
        return true;
    }

    /**
     * Find a message by its index string.
     *
     * @param messageIndex The message index (e.g., "PrepareMessage:3:200")
     * @return The message if found, null otherwise
     */
    public ServerMessage findByIndex(String messageIndex) {
        return index.get(messageIndex);
    }

    /**
     * Find a message by message type, view number, and sequence number.
     * Constructs the index string and looks up the message.
     *
     * @param messageType The message type (e.g., "PrePrepareRequest", "PrepareMessage", "CommitMessage")
     * @param viewNumber The view number
     * @param sequenceNumber The sequence number
     * @return The message if found, null otherwise
     */
    public ServerMessage findMessage(String messageType, long viewNumber, long sequenceNumber) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        return index.get(messageIndex);
    }

    /**
     * Find a PrePrepare message by view and sequence number.
     * @return The PrePrepare message if found, null otherwise
     */
    public ServerMessage findPrePrepare(long viewNumber, long sequenceNumber) {
        return findMessage("PrePrepareMessage", viewNumber, sequenceNumber);
    }

    /**
     * Find a Prepare message by view and sequence number.
     * @return The Prepare message if found, null otherwise
     */
    public ServerMessage findPrepare(long viewNumber, long sequenceNumber) {
        return findMessage("PrepareMessage", viewNumber, sequenceNumber);
    }

    /**
     * Find a Commit message by view and sequence number.
     * @return The Commit message if found, null otherwise
     */
    public ServerMessage findCommit(long viewNumber, long sequenceNumber) {
        return findMessage("CommitMessage", viewNumber, sequenceNumber);
    }

    /**
     * Get all messages in insertion order.
     */
    public List<ServerMessage> getAllMessages() {
        return new ArrayList<>(allMessages);
    }

    /**
     * Get the count of tracked messages.
     */
    public int size() {
        return allMessages.size();
    }

    /**
     * Clear all tracked messages and indices.
     */
    public void clear() {
        allMessages.clear();
        index.clear();
    }

    /**
     * Check if tracker is empty.
     */
    public boolean isEmpty() {
        return allMessages.isEmpty();
    }
}

