package org.example.serverstate;

import org.example.messaging.ServerMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks server-to-server PBFT messages with efficient lookup by message type, view, and sequence number.
 *
 * Note: This class is NOT thread-safe. It relies on external synchronization provided by ServerState,
 * which serializes all access through a single-threaded executor.
 */
public class ServerMessageTracker {

    // All messages in insertion order
    private final List<ServerMessage> allMessages = new ArrayList<>();

    // Index: messageType -> view -> sequence -> message
    private final Map<String, Map<Long, Map<Long, ServerMessage>>> index = new HashMap<>();

    /**
     * Append a server message to the tracker.
     * Indexes it by message type, view number, and sequence number if available.
     * If a message with the same type, view, and sequence already exists, it will not be added again.
     */
    public void append(ServerMessage message) {
        String messageType = message.getMessageType();
        Long view = message.getViewNumber().orElse(null);
        Long seq = message.getSequenceNumber().orElse(null);

        // Check for duplicate before adding
        if (view != null && seq != null) {
            ServerMessage existing = findMessage(messageType, view, seq);
            if (existing != null) {
                // Duplicate found, do not add
                return;
            }
        }

        // Add to list and index
        allMessages.add(message);

        // Only index if we have both view and sequence number
        if (view != null && seq != null) {
            index.computeIfAbsent(messageType, k -> new HashMap<>())
                 .computeIfAbsent(view, k -> new HashMap<>())
                 .put(seq, message);
        }
    }

    /**
     * Find a message by message type, view number, and sequence number.
     *
     * @param messageType The message type (e.g., "PrePrepareRequest", "PrepareMessage", "CommitMessage")
     * @param viewNumber The view number
     * @param sequenceNumber The sequence number
     * @return The message if found, null otherwise
     */
    public ServerMessage findMessage(String messageType, long viewNumber, long sequenceNumber) {
        Map<Long, Map<Long, ServerMessage>> viewMap = index.get(messageType);
        if (viewMap == null) {
            return null;
        }

        Map<Long, ServerMessage> seqMap = viewMap.get(viewNumber);
        if (seqMap == null) {
            return null;
        }

        return seqMap.get(sequenceNumber);
    }

    /**
     * Find a PrePrepare message by view and sequence number.
     * @return The PrePrepare message if found, null otherwise
     */
    public ServerMessage findPrePrepare(long viewNumber, long sequenceNumber) {
        return findMessage("PrePrepareRequest", viewNumber, sequenceNumber);
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

