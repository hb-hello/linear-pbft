package org.example.serverstate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.messaging.ServerMessage;

import java.util.*;

/**
 * Tracks server-to-server PBFT messages with efficient lookup using message indices.
 * Uses ServerMessage.getMessageIndexWithSender() for unique indexing (including sender ID)
 * and ServerMessage.getMessageIndex() for quorum tracking (without sender ID).
 *
 * Note: This class is NOT thread-safe. It relies on external synchronization provided by ServerState,
 * which serializes all access through a single-threaded executor.
 */
public class ServerMessageTracker {

    private static final Logger logger = LogManager.getLogger(ServerMessageTracker.class);

    // All messages in insertion order
    private final List<ServerMessage> allMessages = new ArrayList<>();

    // Simple index: messageIndexWithSender -> message (using ServerMessage.getMessageIndexWithSender())
    private final Map<String, ServerMessage> index = new HashMap<>();

    // Consensus tracker: tracks messages by messageIndex (without sender) for quorum checking
    private final org.example.consensus.ServerConsensusMessageTracker consensusTracker = new org.example.consensus.ServerConsensusMessageTracker();


    /**
     * Append a server message to the tracker.
     * Indexes it using the message's getMessageIndexWithSender() method for unique identification.
     * Uses getMessageIndex() (without sender) for quorum counting.
     * If a message with the same index (including sender) already exists, it will not be added again.
     *
     * @param message The server message to append
     * @return true if the message was successfully appended, false if it was a duplicate and skipped
     */
    public boolean append(ServerMessage message) {
        String messageIndexWithSender = message.getMessageIndexWithSender();
        String messageIndex = message.getMessageIndex();

        // Check for duplicate before adding (using index with sender)
        if (index.containsKey(messageIndexWithSender)) {
            logger.info("Duplicate message detected: index={}. Skipping addition.", messageIndexWithSender);
            return false;
        }

        // Add to list and index
        allMessages.add(message);
        index.put(messageIndexWithSender, message);

        // Record message in consensus tracker (using messageIndex without sender)
        consensusTracker.recordMessage(messageIndex, message);

        logger.info("Appended and indexed message: {}, messageIndex={}",
                messageIndexWithSender, messageIndex);
        return true;
    }

    /**
     * Check if a quorum of messages with the same index exists.
     * Uses the ServerConsensusMessageTracker to check quorum.
     *
     * @param serverMessage The server message to check
     * @param quorumSize The required quorum size
     * @return true if quorum is met, false otherwise
     */
    public boolean checkMessageQuorum(ServerMessage serverMessage, int quorumSize) {
        String messageIndex = serverMessage.getMessageIndex();
        return checkMessageQuorum(messageIndex, quorumSize);
    }

    public boolean checkMessageQuorum(String messageType, long viewNumber, long sequenceNumber, int quorumSize) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        return checkMessageQuorum(messageIndex, quorumSize);
    }

    public boolean checkMessageQuorum(String messageIndex, int quorumSize) {
        // Use consensusTracker to check if quorum is reached
        boolean met = consensusTracker.checkMessageQuorum(messageIndex, quorumSize);
        logger.info("Quorum check for {}: required={}, met={}",
                messageIndex, quorumSize, met);
        return met;
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
     * Find a message by message type, view number, sequence number, and sender ID.
     * Constructs the index string and looks up the message.
     *
     * @param messageType The message type (e.g., "PrePrepareRequest", "PrepareMessage", "CommitMessage")
     * @param viewNumber The view number
     * @param sequenceNumber The sequence number
     * @param senderId The sender ID
     * @return The message if found, null otherwise
     */
    public ServerMessage findMessage(String messageType, long viewNumber, long sequenceNumber, String senderId) {
        String messageIndex = String.format("%s:%d:%d:%s", messageType, viewNumber, sequenceNumber, senderId);
        return index.get(messageIndex);
    }

    public boolean hasMessage(String messageType, long viewNumber, long sequenceNumber) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        return consensusTracker.getStatus(messageIndex, 1).isPresent();
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
        consensusTracker.clear();
    }

    /**
     * Check if tracker is empty.
     */
    public boolean isEmpty() {
        return allMessages.isEmpty();
    }
}

