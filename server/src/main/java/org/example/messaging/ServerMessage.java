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
     * Get the message type as a string for logging/debugging.
     */
    default String getMessageType() {
        return getMessage().getDescriptorForType().getName();
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
            // For proto3 primitive fields, hasField() doesn't work reliably
            // Instead, just check if the field exists and get its value
            Object value = msg.getField(field);
            if (value != null) {
                return Optional.of((Long) value);
            }
        }

        // For PrePrepareRequest, check the nested pre_prepare_message field
        // Note: For MESSAGE type fields, hasField() DOES work correctly in proto3
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
        // Note: For MESSAGE type fields, hasField() DOES work correctly in proto3
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

    // Factory method for creating ServerMessage instances

    /**
     * Wrap any protobuf message in a ServerMessage.
     * Uses a simple implementation that delegates to the default interface methods.
     */
    static ServerMessage wrap(Message message) {
        return () -> message;
    }
}

