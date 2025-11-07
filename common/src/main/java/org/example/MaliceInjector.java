package org.example;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;

public class MaliceInjector {
    private static final Logger logger = LogManager.getLogger(MaliceInjector.class);

    // Make everything static so methods can be called anywhere
    private static final Set<MessageServiceOuterClass.Malice> malices = ConcurrentHashMap.newKeySet();

    // Flags for malice types
    private static volatile boolean crash;
    private static volatile boolean time;
    private static volatile boolean dark;
    private static volatile boolean equivocation;
    private static volatile boolean sign;

    // Target sets for specific malice types
    private static final Set<String> darkTargets = ConcurrentHashMap.newKeySet();
    private static final Set<String> equivocationTargets = ConcurrentHashMap.newKeySet();
    // Set of server IDs marked as malicious (byzantine) in Malice messages
    private static final Set<String> maliciousServerIds = ConcurrentHashMap.newKeySet();

    // Prevent instantiation
    private MaliceInjector() {}

    /**
     * Initialize the static malice set and derive flags/targets from it.
     */
    public static void init(Set<MessageServiceOuterClass.Malice> initialMalices) {
        logger.info("Resetting / Initializing MaliceInjector with initial malices: {}", initialMalices);
        malices.clear();
        darkTargets.clear();
        equivocationTargets.clear();
        maliciousServerIds.clear();
        crash = time = dark = equivocation = sign = false;
        if (initialMalices != null && !initialMalices.isEmpty()) {
            malices.addAll(initialMalices);
            for (MessageServiceOuterClass.Malice m : malices) {
                updateFlagsFromMalice(m);
            }
        }
    }

    public static void addMalice(MessageServiceOuterClass.Malice malice) {
        if (malice == null) return;
        malices.add(malice);
        updateFlagsFromMalice(malice);
    }

    // Update internal boolean flags based on malice type
    private static void updateFlagsFromMalice(MessageServiceOuterClass.Malice malice) {
        if (malice == null) {
            logger.info("Null malice provided, skipping update");
            return;
        }
        String type = malice.getMaliceType();
        if (type.isEmpty()) return;
        // Collect any malicious server ids listed in the malice message
        List<String> malicious = malice.getMaliciousServerIdList();
        if (!malicious.isEmpty()) {
            maliciousServerIds.addAll(malicious);
            logger.info("Added malicious server IDs (byzantine nodes): {}", malicious);
        }
        switch (type.toLowerCase()) {
            case "crash":
                crash = true;
                logger.info("Crash attack enabled");
                break;
            case "time":
                time = true;
                logger.info("Time attack enabled");
                break;
            case "dark":
                dark = true;
                // collect target server ids for dark malice
                List<String> dt = malice.getTargetServerIdList();
                if (!dt.isEmpty()) darkTargets.addAll(dt);
                logger.info("Dark attack enabled, targets: {}", dt);
                break;
            case "equivocation":
                equivocation = true;
                // collect target server ids for equivocation malice
                List<String> et = malice.getTargetServerIdList();
                if (!et.isEmpty()) equivocationTargets.addAll(et);
                logger.info("Equivocation attack enabled, targets: {}", et);
                break;
            case "sign":
                sign = true;
                logger.info("Sign attack enabled");
                break;
            default:
                // unknown malice types are ignored for the boolean flags
                break;
        }
    }

    // Getters for malice flags (static)
    public static boolean isCrash() {
        return crash;
    }

    public static boolean isTime() {
        return time;
    }

    public static boolean isDark() {
        return dark;
    }

    public static boolean isEquivocation() {
        return equivocation;
    }

    public static boolean isSign() {
        return sign;
    }

    // Getters for target sets (return unmodifiable views)
    public static Set<String> getDarkTargets() {
        return Collections.unmodifiableSet(darkTargets);
    }

    public static Set<String> getEquivocationTargets() {
        return Collections.unmodifiableSet(equivocationTargets);
    }

    /**
     * If a time-based malice is enabled, sleep for the configured malice delay.
     * This simulates a timing attack by delaying processing.
     */
    public static void injectTimingAttack(String serverId) {
        // Only apply timing attack for configured malicious server IDs
        if (!isTime() || serverId == null || !maliciousServerIds.contains(serverId)) return;
        long delay = org.example.config.Config.getMaliceTimeDelayMillis();
        try {
            logger.info("Injecting timing attack delay of {} ms for server ID {}", delay, serverId);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // Restore interrupted status and return
            Thread.currentThread().interrupt();
        }
    }

    /**
     * If dark malice is enabled, determine whether a given targetServerId should be considered dark
     * for messages originating from serverId. Returns true when dark attack is enabled, serverId is
     * marked malicious and targetServerId is in the configured darkTargets set.
     */
    public static boolean injectInDarkAttack(String serverId, String targetServerId) {
        if (!isDark()) return false;
        if (serverId == null || targetServerId == null) return false;
        if (!maliciousServerIds.contains(serverId)) return false;
        return darkTargets.contains(targetServerId);
    }
    /**
     * Sign utility: if the given protobuf message has a "signature" BYTES field and that field is non-empty,
     * replace it with a fixed incorrect bytestring and return the modified message. If no signature field or
     * signature is empty, return the original message.
     */
    public static Message injectSignAttack(String serverId, Message message) {
        // Only apply sign attack for configured malicious server IDs
        if (!isSign() || serverId == null || !maliciousServerIds.contains(serverId)) return message;
        if (message == null) return null;
        Descriptors.Descriptor desc = message.getDescriptorForType();
        Descriptors.FieldDescriptor fd = desc.findFieldByName("signature");
        if (fd == null) return message;
        if (fd.getType() != Descriptors.FieldDescriptor.Type.BYTES) return message;

        Object fieldVal = message.getField(fd);
        ByteString current = ByteString.EMPTY;
        if (fieldVal instanceof ByteString) {
            current = (ByteString) fieldVal;
        }

        // If signature is empty, nothing to do
        if (current == null || current.isEmpty()) return message;

        // Replace signature with a fixed incorrect bytestring
        Message.Builder builder = message.toBuilder();
        builder.setField(fd, ByteString.copyFromUtf8("INCORRECT_SIGNATURE"));
        return builder.build();
    }
    /**
     * If equivocation malice is enabled and the provided serverId is among the equivocationTargets,
     * and the message is a PrePrepareMessage, increment its sequence_number by 1 and return the modified message.
     * Otherwise return the original message unchanged.
     */
//    public static Message injectEquivocationAttack(String serverId, String targetServerId, Message message) {
//        if (!isEquivocation()) return message;
//        if (serverId == null || targetServerId == null || message == null) return message;
//        // Only apply if serverId is configured as malicious
//        if (!maliciousServerIds.contains(serverId)) return message;
//
//        // Only operate on PrePrepareMessage types
//        String typeName = message.getDescriptorForType().getName();
//        if (!typeName.equals(org.example.messaging.ServerMessage.PRE_PREPARE)) return message;
//
//        // Only affect servers listed in equivocationTargets
//        if (!equivocationTargets.contains(targetServerId)) return message;
//
//        Descriptors.Descriptor desc = message.getDescriptorForType();
//        Descriptors.FieldDescriptor fd = desc.findFieldByName("sequence_number");
//        if (fd == null) return message;
//
//        // Ensure it's an integer/long field
//        if (fd.getType() != Descriptors.FieldDescriptor.Type.INT64
//                && fd.getType() != Descriptors.FieldDescriptor.Type.INT32) {
//            return message;
//        }
//
//        Object valObj = message.getField(fd);
//        long current = 0L;
//        if (valObj instanceof Number) {
//            current = ((Number) valObj).longValue();
//        }
//
//        long updated = current + 1L;
//
//        Message.Builder builder = message.toBuilder();
//        // setField expects the boxed type matching the field (Long for INT64, Integer for INT32)
//        if (fd.getType() == Descriptors.FieldDescriptor.Type.INT64) {
//            builder.setField(fd, updated);
//        } else {
//            // INT32
//            builder.setField(fd, (int) updated);
//        }
//        return builder.build();
//    }

    public static boolean injectEquivocationAttack(String serverId, String targetServerId) {
        if (!isEquivocation()) return false;
        if (serverId == null || targetServerId == null) return false;
        // Only apply if serverId is configured as malicious
        if (!maliciousServerIds.contains(serverId)) return false;
        return equivocationTargets.contains(targetServerId);
    }

    /**
     * Return an unmodifiable view of the configured malicious server IDs (byzantine nodes).
     */
    public static Set<String> getMaliciousServerIds() {
        return Collections.unmodifiableSet(maliciousServerIds);
    }

    /**
     * Crash injection helper: returns true if crash malice is enabled and the provided serverId
     * is listed among the malicious (byzantine) server IDs.
     */
    public static boolean injectCrashAttack(String serverId) {
        if (!isCrash()) return false;
        if (serverId == null) return false;
        return maliciousServerIds.contains(serverId);
    }

 }
