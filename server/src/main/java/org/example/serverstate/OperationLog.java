package org.example.serverstate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.EnumMap;

public record OperationLog(ConcurrentHashMap<Long, OperationLogEntry> entries, Set<Long> seqNumsSeen) {

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
        this(new ConcurrentHashMap<>(), new HashSet<>());
    }

    public void addOperation(long sequenceNumber, MessageServiceOuterClass.ClientRequest request, OperationStatus status) {
        // only add if status is higher than before
        if (entries.containsKey(sequenceNumber)) {
            OperationLogEntry existingEntry = entries.get(sequenceNumber);
            OperationStatus existingStatus = existingEntry.getStatus();
            if (isNewerOrEqual(existingStatus, status)) {
                logger.info("Not adding operation for seq {}: existing status {} is newer or equal to {}", sequenceNumber, existingStatus, status);
                return;
            }
        }
        entries.put(sequenceNumber, new OperationLogEntry(request, status));
        seqNumsSeen.add(sequenceNumber);
    }

    public void addOperationOrUpdateStatus(long sequenceNumber, MessageServiceOuterClass.ClientRequest request, OperationStatus status) {
        entries.compute(sequenceNumber, (seqNum, existingEntry) -> {
            if (existingEntry == null) {
                seqNumsSeen.add(sequenceNumber);
                return new OperationLogEntry(request, status);
            } else {
                // Only update status if new status is higher or equal in the defined ordering
                OperationStatus existingStatus = existingEntry.getStatus();
                if (status != null && isNewerOrEqual(existingStatus, status)) {
                    existingEntry.setStatus(status);
                }
                // don't overwrite existing request if already set
                if (request != null && existingEntry.getRequest() == null) {
                    existingEntry.setRequest(request);
                }
                return existingEntry;
            }
        });
    }

    public OperationLogEntry getOperation(long sequenceNumber) {
        return entries.getOrDefault(sequenceNumber, new OperationLogEntry(MessageServiceOuterClass.ClientRequest.getDefaultInstance(), OperationStatus.NONE));
    }

    public OperationStatus getOperationStatus(long sequenceNumber) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        return entry != null ? entry.getStatus() : OperationStatus.NONE;
    }

    public void updateStatus(long sequenceNumber, OperationStatus status) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        if (entry != null && status != null) {
            // Only update if new status is newer or equal to the current status according to STATUS_ORDER
            if (isNewerOrEqual(entry.getStatus(), status)) {
                logger.info("Updating status for seq {}: current={}, new={}", sequenceNumber, entry.getStatus(), status);
                entry.setStatus(status);
            } else {
                logger.info("Ignoring downgrade attempt for seq {}: current={}, attempted={}", sequenceNumber, entry.getStatus(), status);
            }
        }
    }

    public void updateStatusForAllBefore(long sequenceNumber, OperationStatus status) {
        if (status == null) return;
        entries.forEach((seqNum, entry) -> {
            if (seqNum <= sequenceNumber) {
                // Only set if the new status is newer or equal
                if (isNewerOrEqual(entry.getStatus(), status)) {
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
        }
    }

    /**
     * Return true if candidate is newer or equal to current according to STATUS_ORDER.
     */
    private static boolean isNewerOrEqual(OperationStatus current, OperationStatus candidate) {
        if (current == null) current = OperationStatus.NONE;
        if (candidate == null) candidate = OperationStatus.NONE;
        Integer cur = STATUS_ORDER.get(current);
        Integer cand = STATUS_ORDER.get(candidate);
        if (cur == null || cand == null) return true; // be permissive if unknown
        return cand >= cur;
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
    }
}
