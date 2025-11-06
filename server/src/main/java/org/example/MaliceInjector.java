package org.example;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.*;
import java.util.concurrent.*;

public class MaliceInjector {

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

    // Prevent instantiation
    private MaliceInjector() {}

    /**
     * Initialize the static malice set and derive flags/targets from it.
     */
    public static void init(Set<MessageServiceOuterClass.Malice> initialMalices) {
        malices.clear();
        darkTargets.clear();
        equivocationTargets.clear();
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
        if (malice == null) return;
        String type = malice.getMaliceType();
        if (type.isEmpty()) return;
        switch (type.toLowerCase()) {
            case "crash":
                crash = true;
                break;
            case "time":
                time = true;
                break;
            case "dark":
                dark = true;
                // collect target server ids for dark malice
                List<String> dt = malice.getTargetServerIdList();
                if (!dt.isEmpty()) darkTargets.addAll(dt);
                break;
            case "equivocation":
                equivocation = true;
                // collect target server ids for equivocation malice
                List<String> et = malice.getTargetServerIdList();
                if (!et.isEmpty()) equivocationTargets.addAll(et);
                break;
            case "sign":
                sign = true;
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
    public static void injectTimingAttack() {
        if (!isTime()) return;
        long delay = org.example.config.Config.getMaliceTimeDelayMillis();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // Restore interrupted status and return
            Thread.currentThread().interrupt();
        }
    }

    /**
     * If dark malice is enabled, remove the servers listed in darkTargets from the provided set.
     * Returns an unmodifiable set (preserves iteration order of the input).
     */
    public static Set<String> injectInDarkAttack(Set<String> serverIds) {
        if (!isDark()) {
            return serverIds == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(serverIds));
        }

        if (serverIds == null || serverIds.isEmpty()) {
            return Collections.emptySet();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String sid : serverIds) {
            if (!darkTargets.contains(sid)) {
                result.add(sid);
            }
        }
        return Collections.unmodifiableSet(result);
    }
    /**
     * Sign utility: if the given protobuf message has a "signature" BYTES field and that field is non-empty,
     * replace it with a fixed incorrect bytestring and return the modified message. If no signature field or
     * signature is empty, return the original message.
     */
    public static Message injectSignAttack(Message message) {
        if (!isSign()) return message;
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
    public static Message injectEquivocationAttack(String serverId, Message message) {
        if (!isEquivocation()) return message;
        if (serverId == null || message == null) return message;

        // Only operate on PrePrepareMessage types
        String typeName = message.getDescriptorForType().getName();
        if (!typeName.equals(org.example.messaging.ServerMessage.PRE_PREPARE)) return message;

        // Only affect servers listed in equivocationTargets
        if (!equivocationTargets.contains(serverId)) return message;

        Descriptors.Descriptor desc = message.getDescriptorForType();
        Descriptors.FieldDescriptor fd = desc.findFieldByName("sequence_number");
        if (fd == null) return message;

        // Ensure it's an integer/long field
        if (fd.getType() != Descriptors.FieldDescriptor.Type.INT64
                && fd.getType() != Descriptors.FieldDescriptor.Type.INT32) {
            return message;
        }

        Object valObj = message.getField(fd);
        long current = 0L;
        if (valObj instanceof Number) {
            current = ((Number) valObj).longValue();
        }

        long updated = current + 1L;

        Message.Builder builder = message.toBuilder();
        // setField expects the boxed type matching the field (Long for INT64, Integer for INT32)
        if (fd.getType() == Descriptors.FieldDescriptor.Type.INT64) {
            builder.setField(fd, Long.valueOf(updated));
        } else {
            // INT32
            builder.setField(fd, Integer.valueOf((int) updated));
        }
        return builder.build();
    }

}
