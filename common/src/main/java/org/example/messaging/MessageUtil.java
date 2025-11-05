package org.example.messaging;

import com.google.protobuf.Message;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MessageUtil {

    public static byte[] generateDigest(Message message) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(message.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    public static boolean verifyDigest(Message message, byte[] digestToCheck) {
        byte[] actualDigest = generateDigest(message);
        if (actualDigest.length != digestToCheck.length) {
            return false;
        }
        for (int i = 0; i < actualDigest.length; i++) {
            if (actualDigest[i] != digestToCheck[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generate a digest for a generic Object.
     * Uses the object's string representation for digest generation.
     */
    public static byte[] generateDigest(Object object) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(object.toString().getBytes());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * Verify a digest for a generic Object.
     * Uses the object's string representation for digest verification.
     */
    public static boolean verifyDigest(Object object, byte[] digestToCheck) {
        byte[] actualDigest = generateDigest(object);
        if (actualDigest.length != digestToCheck.length) {
            return false;
        }
        for (int i = 0; i < actualDigest.length; i++) {
            if (actualDigest[i] != digestToCheck[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convert a digest byte array to a readable hexadecimal string.
     * This conversion is deterministic - the same digest will always produce the same string.
     */
    public static String digestToString(byte[] digest) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Compose a stable request id from client id and timestamp.
     * Kept generic so both client and server code can rely on identical formatting.
     */
    public static String requestIdFor(String clientId, long timestamp) {
        return clientId + ":" + timestamp;
    }


}
