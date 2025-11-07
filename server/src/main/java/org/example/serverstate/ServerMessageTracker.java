package org.example.serverstate;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.consensus.ServerConsensusMessageTracker;
import org.example.messaging.ServerMessage;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * Tracks server-to-server PBFT messages with efficient lookup using message indices.
 * Uses ServerMessage.getMessageIndexWithSender() for unique indexing (including sender ID)
 * and ServerMessage.getMessageIndex() for quorum tracking (without sender ID).
 * <p>
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
    private final ServerConsensusMessageTracker consensusTracker = new ServerConsensusMessageTracker();


    /**
     * Append a server message to the tracker.
     * Indexes it using the message's getMessageIndexWithSender() method for unique identification.
     * Uses getMessageIndex() (without sender) for quorum counting.
     * If a message with the same index (including sender) already exists, it will not be added again.
     *
     * @param message The server message to append
     * @return true if the message was successfully appended, false if it was a duplicate and skipped
     */
    public boolean append(ServerMessage message, int required) {
        String messageIndexWithSender = message.getMessageIndexWithSender();
        String messageIndex = message.getMessageIndex();

        // Check for duplicate before adding (using index with sender) - only for aggregated messages
        if (index.containsKey(messageIndexWithSender) && (index.get(messageIndexWithSender).isAggregated() || !message.isAggregated())) {
            logger.info("Duplicate message detected: index={}. Skipping addition.", messageIndexWithSender);
            return false;
        }

        // Add to list and index
        allMessages.add(message);
        index.put(messageIndexWithSender, message);

        // Record message in consensus tracker (using messageIndex without sender)
        if (required > 0) consensusTracker.recordMessage(messageIndex, message, required);

        logger.info("Appended and indexed message: {}, messageIndex={}",
                messageIndexWithSender, messageIndex);
        return true;
    }

    // for client requests
    public boolean appendWithoutConsensus(ServerMessage message, String withIndex) {
        // Add to list and index

        if (index.containsKey(withIndex)) {
            logger.info("Duplicate message detected: index={}. Skipping addition.", withIndex);
            return false;
        }

        allMessages.add(message);
        index.put(withIndex, message);

        logger.info("Appended and indexed message without consensus: {}, messageIndex={}",
                message.getMessageIndexWithSender(), withIndex);
        return true;
    }

    public boolean appendWithId(ServerMessage message, String withIndex, int required) {
        // Add to list and index

        if (index.containsKey(withIndex)) {
            logger.info("Duplicate message detected: index={}. Skipping addition.", withIndex);
            return false;
        }

        allMessages.add(message);
        index.put(message.getMessageIndexWithSender(), message);

        // Record message in consensus tracker (using messageIndex without sender)
        if (required > 0) {
            consensusTracker.recordMessage(withIndex, message, required);
            logger.info("Appended and indexed message with custom index: {}, messageIndex={}",
                    withIndex, message.getMessageIndexWithSender());
        }

        return true;
    }

    /**
     * Check if a quorum of messages with the same index exists.
     * Uses the ServerConsensusMessageTracker to check quorum.
     *
     * @param serverMessage The server message to check
     * @return true if quorum is met, false otherwise
     */
    public boolean checkMessageQuorum(ServerMessage serverMessage) {
        String messageIndex = serverMessage.getMessageIndex();
        return checkMessageQuorum(messageIndex);
    }

    public boolean checkMessageQuorum(String messageType, long viewNumber, long sequenceNumber) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        return checkMessageQuorum(messageIndex);
    }

    public boolean checkMessageQuorum(String messageIndex) {
        // Use consensusTracker to check if quorum is reached
        boolean met = consensusTracker.checkMessageQuorum(messageIndex);
        logger.info("Quorum check for {} : required={}, met={}",
                messageIndex, consensusTracker.getQuorumRequired(messageIndex), met);
        return met;
    }

    public Message appendAndAwaitConsensus(Message msg, Duration timeout, int required) throws TimeoutException, InterruptedException {
        ServerMessage serverMsg = ServerMessage.wrap(msg);

        append(serverMsg, required);
        ServerMessage result = consensusTracker.awaitConsensusAsServerMessage(serverMsg.getMessageIndex(), timeout, required);
        return result.getMessage();
    }

    /**
     * Get the signatures of messages that contributed to reaching quorum for a given message index.
     * Uses the consensus tracker to identify which messages (with their sender IDs) were part of the consensus,
     * then retrieves their signatures from the tracked messages.
     *
     * @param messageIndex The message index (e.g., "PrepareMessage:1:10")
     * @return Map of sender ID to signature ByteString from messages that contributed to quorum, or empty map if none
     */
    public Map<String, ByteString> getQuorumSignatures(String messageIndex) {
        // Get the set of messageIndexWithSender from the consensus tracker
        Set<String> messageIndicesWithSender = consensusTracker.getMessageIndicesWithSender(messageIndex);

        logger.info("Getting quorum signatures for {}: found {} messages in consensus",
                messageIndex, messageIndicesWithSender.size());

        // Fetch the actual messages and extract their sender IDs and signatures
        Map<String, ByteString> signaturesBySender = new HashMap<>();
        for (String messageIndexWithSender : messageIndicesWithSender) {
            ServerMessage msg = index.get(messageIndexWithSender);
            if (msg != null) {
                Optional<String> senderId = msg.getSenderId();
                Optional<ByteString> signature = msg.getSignature();

                if (senderId.isPresent() && signature.isPresent()) {
                    signaturesBySender.put(senderId.get(), signature.get());
                } else {
                    logger.warn("Message with index {} missing sender ID or signature", messageIndexWithSender);
                }
            } else {
                logger.warn("Message with index {} not found in tracker, but was in consensus", messageIndexWithSender);
            }
        }

//        logger.info("Retrieved {} signatures for message index {}", signaturesBySender.size(), messageIndex);
        return signaturesBySender;
    }

    public List<ServerMessage> getQuorumMessages(String messageType, long viewNumber, long sequenceNumber) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        return getQuorumMessages(messageIndex);
    }

    public List<ServerMessage> getQuorumMessages(String messageIndex) {
        Set<String> messageIndicesWithSender = consensusTracker.getMessageIndicesWithSender(messageIndex);

        List<ServerMessage> quorumMessages = new ArrayList<>();
        for (String messageIndexWithSender : messageIndicesWithSender) {
            ServerMessage msg = index.get(messageIndexWithSender);
            if (msg != null) {
                quorumMessages.add(msg);
            } else {
                logger.warn("Message with index {} not found in tracker, but was in consensus", messageIndexWithSender);
            }
        }
        return quorumMessages;
    }

    /**
     * Get the signatures of messages that contributed to reaching quorum for a specific message type/view/sequence.
     *
     * @param messageType    The message type (e.g., "PrepareMessage", "CommitMessage")
     * @param viewNumber     The view number
     * @param sequenceNumber The sequence number
     * @return Map of sender ID to signature ByteString from messages that contributed to quorum, or empty map if none
     */
    public Map<String, ByteString> getQuorumSignatures(String messageType, long viewNumber, long sequenceNumber) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        return getQuorumSignatures(messageIndex);
    }

    public Map<String, ByteString> checkQuorumAndGetSignatures(String messageType, long viewNumber, long sequenceNumber, int quorumSize) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        if (checkMessageQuorum(messageIndex)) {
            return getQuorumSignatures(messageIndex);
        } else {
            return Collections.emptyMap();
        }
    }

    public Map<String, ByteString> checkQuorumAndGetSignatures(ServerMessage serverMessage, int quorumSize) {
        String messageIndex = serverMessage.getMessageIndex();
        if (checkMessageQuorum(messageIndex)) {
            return getQuorumSignatures(messageIndex);
        } else {
            return Collections.emptyMap();
        }
    }

    public ByteString getQuorumValue(String messageType, long viewNumber, long sequenceNumber) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        return consensusTracker.getQuorumValue(messageIndex);
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

    public void removeFromConsensusTrackerByIndex(String messageIndex) {
        consensusTracker.cancel(messageIndex);
    }

    /**
     * Find a message by message type, view number, sequence number, and sender ID.
     * Constructs the index string and looks up the message.
     *
     * @param messageType    The message type (e.g., "PrePrepareRequest", "PrepareMessage", "CommitMessage")
     * @param viewNumber     The view number
     * @param sequenceNumber The sequence number
     * @param senderId       The sender ID
     * @return The message if found, null otherwise
     */
    public ServerMessage findMessage(String messageType, long viewNumber, long sequenceNumber, String senderId) {
        String messageIndex = String.format("%s:%d:%d:%s", messageType, viewNumber, sequenceNumber, senderId);
        return index.get(messageIndex);
    }

    public ServerMessage findMessage(String messageType, long viewNumber, String senderId) {
        String messageIndex = String.format("%s:%d:%s", messageType, viewNumber, senderId);
        return index.get(messageIndex);
    }

    public boolean hasMessage(String messageType, long viewNumber, long sequenceNumber) {
        String messageIndex = String.format("%s:%d:%d", messageType, viewNumber, sequenceNumber);
        // First, check if we have any indexed message matching this messageIndex (without sender).
        for (ServerMessage msg : allMessages) {
            if (messageIndex.equals(msg.getMessageIndex())) {
                return true;
            }
        }
        // Fall back to consensus tracker status (for messages recorded with quorum tracking)
        return consensusTracker.getStatus(messageIndex, 1).isPresent();
    }

    /**
     * Get all messages in insertion order.
     */
    public List<ServerMessage> getAllMessages() {
        return new ArrayList<>(allMessages);
    }

    public Map<String, ServerMessage> getIndexedMessages() {
        return new HashMap<>(index);
    }

    public String printIndexedMessages() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ServerMessage> entry : index.entrySet()) {
            sb.append("Index: ").append(entry.getKey())
                    .append(", Message: ").append(entry.getValue().toDetailedString())
                    .append("\n");
        }
        return sb.toString();
    }

    public List<ServerMessage> getMessagesByType(String messageType) {
        return getMessagesByType(messageType, 0);
    }

    public List<ServerMessage> getMessagesByType(String messageType, long viewNumber) {
        List<ServerMessage> filteredMessages = new ArrayList<>();
        for (ServerMessage msg : allMessages) {
            if (msg.getMessageType().equals(messageType) && msg.getViewNumber().orElse(0L) == viewNumber) {
                filteredMessages.add(msg);
            }
        }
        return filteredMessages;
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

