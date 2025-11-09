package org.example.messaging;

import com.google.protobuf.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public class MessageUtil {

    public static byte[] generateDigest(Message message) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(message.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * Generate a deterministic digest for a Map<String, Long>.
     * The map is sorted by key to ensure two maps with the same key/value
     * pairs produce the same digest regardless of insertion iteration order.
     */
    public static byte[] generateDigest(Map<String, Long> map) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            if (map == null || map.isEmpty()) {
                return md.digest(new byte[0]);
            }

            // Use a TreeMap to force deterministic key ordering
            Map<String, Long> sorted = new TreeMap<>(map);

            // Build a stable string representation: key=value;key=value;...
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : sorted.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
            }

            return md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
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
        // If caller passed a Map<String, Long>, route to the deterministic map digest implementation.
        if (object instanceof Map<?, ?> rawMap) {
            // Try to convert entries to Map<String, Long> deterministically; if any key/value
            // is of the wrong type, fall back to the generic string-based digest below.
            try {
                Map<String, Long> converted = new TreeMap<>();
                for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                    Object k = e.getKey();
                    Object v = e.getValue();
                    if (!(k instanceof String) || !(v instanceof Long)) {
                        // type mismatch -> fall back
                        converted = null;
                        break;
                    }
                    converted.put((String) k, (Long) v);
                }
                if (converted != null) {
                    return generateDigest(converted);
                }
            } catch (ClassCastException ignored) {
                // fall through to generic behavior
            }
        }
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
