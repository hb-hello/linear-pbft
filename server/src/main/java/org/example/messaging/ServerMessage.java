package org.example.messaging;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;

import java.util.Optional;

/**
 * Wrapper interface for server-to-server PBFT protocol messages.
 * Provides unified access to common fields (viewNumber, sequenceNumber, digest)
 * regardless of the underlying message type.
 *
 * Uses protobuf reflection API to dynamically extract fields without custom wrapper classes.
 */
public interface ServerMessage {

    // Message type constants
    String PRE_PREPARE = "PrePrepareMessage";
    String PREPARE = "PrepareMessage";
    String COMMIT = "CommitMessage";
    String CHECKPOINT = "CheckpointMessage";
    String VIEW_CHANGE = "ViewChangeMessage";
    String NEW_VIEW = "NewViewMessage";
    String CLIENT_REQUEST = "ClientRequest";
    String CLIENT_REPLY = "ClientReply";

    /**
     * Get the underlying protobuf message.
     */
    Message getMessage();

    /**
     * Extract view number if present in the message.
     * Handles both direct fields and nested fields (e.g., PrePrepareRequest.pre_prepare_message.view_number).
     */
    default Optional<Long> getViewNumber() {
        return extractLongField("view_number");
    }

    /**
     * Extract sequence number if present in the message.
     * Handles both direct fields and nested fields (e.g., PrePrepareRequest.pre_prepare_message.sequence_number).
     */
    default Optional<Long> getSequenceNumber() {
        return extractLongField("sequence_number");
    }

    /**
     * Extract digest if present in the message.
     * Handles both direct fields and nested fields (e.g., PrePrepareRequest.pre_prepare_message.digest).
     */
    default Optional<ByteString> getDigest() {
        return extractBytesField("digest");
    }

    /**
     * Extract sender id if present in the message.
     * Handles both direct fields and nested fields (e.g., PrePrepareRequest.pre_prepare_message.digest).
     */
    default Optional<String> getSenderId() {
        return extractStringField("signer_id");
    }

    /**
     * Extract signature if present in the message.
     * Handles both direct fields and nested fields (e.g., PrePrepareRequest.pre_prepare_message.signature).
     */
    default Optional<ByteString> getSignature() {
        return extractBytesField("signature");
    }

    /**
     * Extract client ID if present in the message (for ClientRequest).
     */
    default Optional<String> getClientId() {
        return extractStringField("client_id");
    }

    /**
     * Extract timestamp if present in the message (for ClientRequest).
     */
    default Optional<Long> getTimestamp() {
        return extractLongField("timestamp");
    }

    /**
     * Get the message type as a string for logging/debugging.
     */
    default String getMessageType() {
        return getMessage().getDescriptorForType().getName();
    }

    /**
     * Extract a value suitable for comparing messages to determine consensus.
     * Returns the digest as a byte array if present in the message, otherwise returns an empty array.
     * This is useful for grouping messages by their digest value in consensus tracking.
     *
     * @return byte array containing the digest, or empty array if no digest is present
     */
    default byte[] getValueForComparison() {
        return getDigest()
                .map(ByteString::toByteArray)
                .orElse(new byte[0]);
    }

    /**
     * Calculate a unique index for this message based on its type and relevant fields (without sender ID).
     * <ul>
     *   <li>For messages with view_number and sequence_number (PrePrepare, Prepare, Commit, Checkpoint, ViewChange):
     *       returns "MessageType:viewNumber:sequenceNumber"</li>
     *   <li>For messages with only view_number (NewView):
     *       returns "MessageType:viewNumber"</li>
     *   <li>For messages with client_id and timestamp (ClientRequest):
     *       returns "MessageType:clientId:timestamp"</li>
     *   <li>For other messages:
     *       returns "MessageType:unknown"</li>
     * </ul>
     *
     * @return A string index identifying this message type and content (without sender)
     */
    default String getMessageIndex() {
        String messageType = getMessageType();

        // Check for view_number and sequence_number (PrePrepare, Prepare, Commit, etc.)
        Optional<Long> viewNumber = getViewNumber();
        Optional<Long> sequenceNumber = getSequenceNumber();

        if (viewNumber.isPresent() && sequenceNumber.isPresent()) {
            return String.format("%s:%d:%d", messageType, viewNumber.get(), sequenceNumber.get());
        }

        // Check for view_number only (NewView)
        if (viewNumber.isPresent()) {
            return String.format("%s:%d", messageType, viewNumber.get());
        }

        // Check for client_id and timestamp (ClientRequest)
        Optional<String> clientId = getClientId();
        Optional<Long> timestamp = getTimestamp();

        if (clientId.isPresent() && timestamp.isPresent()) {
            return String.format("%s:%s:%d", messageType, clientId.get(), timestamp.get());
        }

        // Fallback for unknown message types
        return String.format("%s:unknown", messageType);
    }

    /**
     * Calculate a unique index for this message based on its type, relevant fields, and sender ID.
     *
     * @return A string index uniquely identifying this message including sender
     */
    default String getMessageIndexWithSender() {
        String baseIndex = getMessageIndex();
        Optional<String> senderId = getSenderId();

        return senderId.map(s -> baseIndex + ":" + s).orElse(baseIndex);
    }

    /**
     * Returns a detailed string representation of the message with type and all available fields.
     * Includes view_number, sequence_number, digest, client_id, and timestamp if present.
     *
     * @return A formatted string showing message type and all populated fields
     */
    default String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessageType()).append("{");

        boolean hasFields = false;

        // Add view number if present
        Optional<Long> viewNumber = getViewNumber();
        if (viewNumber.isPresent()) {
            sb.append("viewNumber=").append(viewNumber.get());
            hasFields = true;
        }

        // Add sequence number if present
        Optional<Long> sequenceNumber = getSequenceNumber();
        if (sequenceNumber.isPresent()) {
            if (hasFields) sb.append(", ");
            sb.append("sequenceNumber=").append(sequenceNumber.get());
            hasFields = true;
        }

        // Add digest if present
        Optional<ByteString> digest = getDigest();
        if (digest.isPresent()) {
            if (hasFields) sb.append(", ");
            sb.append("digest=").append(digest.get().toStringUtf8());
            hasFields = true;
        }

        // Add client ID if present
        Optional<String> clientId = getClientId();
        if (clientId.isPresent()) {
            if (hasFields) sb.append(", ");
            sb.append("clientId=").append(clientId.get());
            hasFields = true;
        }

        // Add timestamp if present
        Optional<Long> timestamp = getTimestamp();
        if (timestamp.isPresent()) {
            if (hasFields) sb.append(", ");
            sb.append("timestamp=").append(timestamp.get());
            hasFields = true;
        }

        // Add message index
        if (hasFields) sb.append(", ");
        sb.append("index=").append(getMessageIndex());

        sb.append("}");
        return sb.toString();
    }

    /**
     * Helper method to extract a long field from the message using reflection.
     * Checks both direct fields and nested message fields (e.g., pre_prepare_message).
     */
    default Optional<Long> extractLongField(String fieldName) {
        Message msg = getMessage();
        Descriptors.Descriptor descriptor = msg.getDescriptorForType();

        // First, try to find the field directly in the message
        Descriptors.FieldDescriptor field = descriptor.findFieldByName(fieldName);
        if (field != null) {
            Object value = msg.getField(field);
            if (value != null) {
                return Optional.of((Long) value);
            }
        }

        // For PrePrepareRequest, check the nested pre_prepare_message field
        Descriptors.FieldDescriptor nestedField = descriptor.findFieldByName("pre_prepare_message");
        if (nestedField != null && nestedField.getType() == Descriptors.FieldDescriptor.Type.MESSAGE
                && msg.hasField(nestedField)) {
            Object nestedObj = msg.getField(nestedField);
            if (nestedObj instanceof Message) {
                Message nestedMsg = (Message) nestedObj;
                Descriptors.FieldDescriptor nestedTargetField = nestedMsg.getDescriptorForType().findFieldByName(fieldName);
                if (nestedTargetField != null) {
                    Object value = nestedMsg.getField(nestedTargetField);
                    if (value != null) {
                        return Optional.of((Long) value);
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Helper method to extract a ByteString field from the message using reflection.
     * Checks both direct fields and nested message fields (e.g., pre_prepare_message).
     */
    default Optional<ByteString> extractBytesField(String fieldName) {
        Message msg = getMessage();
        Descriptors.Descriptor descriptor = msg.getDescriptorForType();

        // First, try to find the field directly in the message
        Descriptors.FieldDescriptor field = descriptor.findFieldByName(fieldName);
        if (field != null) {
            Object value = msg.getField(field);
            if (value instanceof ByteString && !((ByteString) value).isEmpty()) {
                return Optional.of((ByteString) value);
            }
        }

        // For PrePrepareRequest, check the nested pre_prepare_message field
        Descriptors.FieldDescriptor nestedField = descriptor.findFieldByName("pre_prepare_message");
        if (nestedField != null && nestedField.getType() == Descriptors.FieldDescriptor.Type.MESSAGE
                && msg.hasField(nestedField)) {
            Object nestedObj = msg.getField(nestedField);
            if (nestedObj instanceof Message) {
                Message nestedMsg = (Message) nestedObj;
                Descriptors.FieldDescriptor nestedTargetField = nestedMsg.getDescriptorForType().findFieldByName(fieldName);
                if (nestedTargetField != null) {
                    Object value = nestedMsg.getField(nestedTargetField);
                    if (value instanceof ByteString && !((ByteString) value).isEmpty()) {
                        return Optional.of((ByteString) value);
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Helper method to extract a String field from the message using reflection.
     * Checks both direct fields and nested message fields (e.g., pre_prepare_message).
     */
    default Optional<String> extractStringField(String fieldName) {
        Message msg = getMessage();
        Descriptors.Descriptor descriptor = msg.getDescriptorForType();

        // First, try to find the field directly in the message
        Descriptors.FieldDescriptor field = descriptor.findFieldByName(fieldName);
        if (field != null) {
            Object value = msg.getField(field);
            if (value instanceof String && !((String) value).isEmpty()) {
                return Optional.of((String) value);
            }
        }

        // For PrePrepareRequest, check the nested pre_prepare_message field
        Descriptors.FieldDescriptor nestedField = descriptor.findFieldByName("pre_prepare_message");
        if (nestedField != null && nestedField.getType() == Descriptors.FieldDescriptor.Type.MESSAGE
                && msg.hasField(nestedField)) {
            Object nestedObj = msg.getField(nestedField);
            if (nestedObj instanceof Message) {
                Message nestedMsg = (Message) nestedObj;
                Descriptors.FieldDescriptor nestedTargetField = nestedMsg.getDescriptorForType().findFieldByName(fieldName);
                if (nestedTargetField != null) {
                    Object value = nestedMsg.getField(nestedTargetField);
                    if (value instanceof String && !((String) value).isEmpty()) {
                        return Optional.of((String) value);
                    }
                }
            }
        }

        return Optional.empty();
    }

    // Factory method for creating ServerMessage instances

    /**
     * Wrap any protobuf message in a ServerMessage.
     * Uses a simple implementation that delegates to the default interface methods.
     */
    static ServerMessage wrap(Message message) {
        return () -> message;
    }
}

