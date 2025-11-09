package org.example.serverstate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.messaging.MessageUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.EnumMap;

public record OperationLog(ConcurrentHashMap<Long, OperationLogEntry> entries, Set<Long> seqNumsSeen, ConcurrentHashMap<String, Long> digestToSeqNum) {

    private static final Logger logger = LogManager.getLogger(OperationLog.class);

    // Define an explicit order for OperationStatus so we can compare and avoid downgrades
    private static final EnumMap<OperationStatus, Integer> STATUS_ORDER = new EnumMap<>(OperationStatus.class);
    static {
        // Order: NONE, PREPREPARED, PREPARED, COMMITTED, EXECUTED, CHECKPOINTED
        STATUS_ORDER.put(OperationStatus.NONE, 0);
        STATUS_ORDER.put(OperationStatus.PREPREPARED, 1);
        STATUS_ORDER.put(OperationStatus.PREPARED, 2);
        STATUS_ORDER.put(OperationStatus.COMMITTED, 3);
        STATUS_ORDER.put(OperationStatus.EXECUTED, 4);
        STATUS_ORDER.put(OperationStatus.CHECKPOINTED, 5);
    }

    public OperationLog() {
        this(new ConcurrentHashMap<>(), new HashSet<>(), new ConcurrentHashMap<>());
    }

    public void addOperation(long sequenceNumber, MessageServiceOuterClass.ClientRequest request, OperationStatus status) {
        // only add if status is higher than before
        if (entries.containsKey(sequenceNumber)) {
            OperationLogEntry existingEntry = entries.get(sequenceNumber);
            OperationStatus existingStatus = existingEntry.getStatus();
            logger.info("When adding operation for seq {}, Existing status : {}, new status : {}", sequenceNumber, existingStatus, status);
            if (isNewer(status, existingStatus)) {
                logger.info("Not adding operation for seq {}: existing status {} is newer than {}", sequenceNumber, existingStatus, status);
                return;
            }
        }
        entries.put(sequenceNumber, new OperationLogEntry(request, status));
        logger.info("Added operation for seq {}: status {}", sequenceNumber, status);
        seqNumsSeen.add(sequenceNumber);
        remapRequestToSeqNum(request, sequenceNumber);
    }

    public OperationLogEntry getOperation(long sequenceNumber) {
        return entries.get(sequenceNumber);
    }

    public OperationLogEntry getDefaultOperation(long sequenceNumber) {
        return entries.getOrDefault(sequenceNumber, new OperationLogEntry(null, OperationStatus.NONE));
    }

    public OperationStatus getOperationStatus(long sequenceNumber) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        return entry != null ? entry.getStatus() : OperationStatus.NONE;
    }

    public void updateStatus(long sequenceNumber, OperationStatus status) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        if (entry != null && status != null) {
            // Only update if new status is newer or equal to the current status according to STATUS_ORDER
            if (isNewer(entry.getStatus(), status)) {
                logger.info("Updating status for seq {}: current={}, new={}", sequenceNumber, entry.getStatus(), status);
                entry.setStatus(status);
            } else {
                logger.info("Ignoring downgrade attempt for seq {}: current={}, attempted={}", sequenceNumber, entry.getStatus(), status);
            }
        }
    }

    private void resetStatus(long sequenceNumber) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        if (entry != null) {
            entry.setStatus(OperationStatus.NONE);
        }
    }

    public void updateStatusForAllBefore(long sequenceNumber, OperationStatus status) {
        if (status == null) return;
        entries.forEach((seqNum, entry) -> {
            if (seqNum <= sequenceNumber) {
                // Only set if the new status is newer or equal
                if (isNewer(entry.getStatus(), status)) {
                    entry.setStatus(status);
                } else {
                    logger.info("Skipping downgrade for seq {}: current={}, attempted={}", seqNum, entry.getStatus(), status);
                }
            }
        });
    }

    public void setRequest(long sequenceNumber, MessageServiceOuterClass.ClientRequest request) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        if (entry != null) {
            entry.setRequest(request);
            if (request != null) remapRequestToSeqNum(request, sequenceNumber);
        }
    }

    public void remapRequestToSeqNum(MessageServiceOuterClass.ClientRequest request, long sequenceNumber) {
        if (request == null) return;
        String digest = MessageUtil.digestToString(MessageUtil.generateDigest(request));
        if (digestToSeqNum.containsKey(digest)) {
            logger.info("Digest {} already mapped to seq {}, overwriting to seq {}", digest, digestToSeqNum.get(digest), sequenceNumber);
            long oldSeqNum = digestToSeqNum.get(digest);
            if (oldSeqNum != sequenceNumber) {
                logger.info("Clearing old mapping for digest {} from seq {}", digest, oldSeqNum);
                setRequest(oldSeqNum, null);
                resetStatus(oldSeqNum);
            }
        }
        digestToSeqNum.put(digest, sequenceNumber);
    }

    public long findSeqNumByDigest(String digestString) {
        for (var pair : entries.entrySet()) {
            Long seqNum = pair.getKey();
            OperationLogEntry entry = pair.getValue();
            if (entry.getRequest() != null && (entry.getStatus() == OperationStatus.NONE || entry.getStatus() == OperationStatus.PREPREPARED || entry.getStatus() == OperationStatus.PREPARED)) {
                String entryDigest = MessageUtil.digestToString(MessageUtil.generateDigest(entry.getRequest()));
                if (entryDigest.equals(digestString)) {
                    return seqNum;
                }
            }
        }
        return -1; // not found
    }

    /**
     * Return true if candidate is newer or equal to current according to STATUS_ORDER.
     */
    private static boolean isNewer(OperationStatus current, OperationStatus candidate) {
        if (current == null) current = OperationStatus.NONE;
        if (candidate == null) candidate = OperationStatus.NONE;
        Integer cur = STATUS_ORDER.get(current);
        Integer cand = STATUS_ORDER.get(candidate);
        logger.info("Comparing statuses: current={} ({}), candidate={} ({})", current, cur, candidate, cand);
        if (cur == null || cand == null) return true; // be permissive if unknown
        return cand > cur;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        entries.forEach((seqNum, entry) -> {
            sb.append("SeqNum: ").append(seqNum).append(", Entry: ").append(entry.toString()).append("\n");
        });
        return sb.toString();
    }

    public void clear() {
        entries.clear();
        seqNumsSeen.clear();
        digestToSeqNum.clear();
    }
}
