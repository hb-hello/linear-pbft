package org.example.serverstate;

import org.example.MessageServiceOuterClass;

import java.util.concurrent.ConcurrentHashMap;

public record OperationLog(ConcurrentHashMap<Long, OperationLogEntry> entries) {

    public OperationLog() {
        this(new ConcurrentHashMap<>());
    }

    public void addOperation(long sequenceNumber, MessageServiceOuterClass.ClientRequest request, OperationStatus status) {
        entries.put(sequenceNumber, new OperationLogEntry(request, status));
    }

    public void addOperationOrUpdateStatus(long sequenceNumber, MessageServiceOuterClass.ClientRequest request, OperationStatus status) {
        entries.compute(sequenceNumber, (seqNum, existingEntry) -> {
            if (existingEntry == null) {
                return new OperationLogEntry(request, status);
            } else {
                existingEntry.setStatus(status);
                // don't overwrite existing request if already set
                if (request != null && existingEntry.getRequest() == null) {
                    existingEntry.setRequest(request);
                }
                return existingEntry;
            }
        });
    }

    public OperationLogEntry getOperation(long sequenceNumber) {
        return entries.get(sequenceNumber);
    }

    public OperationStatus getOperationStatus(long sequenceNumber) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        return entry != null ? entry.getStatus() : OperationStatus.NONE;
    }

    public void updateStatus(long sequenceNumber, OperationStatus status) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        if (entry != null) {
            entry.setStatus(status);
        }
    }

    public void updateStatusForAllBefore(long sequenceNumber, OperationStatus status) {
        entries.forEach((seqNum, entry) -> {
            if (seqNum < sequenceNumber) {
                entry.setStatus(status);
            }
        });
    }

    public void setRequest(long sequenceNumber, MessageServiceOuterClass.ClientRequest request) {
        OperationLogEntry entry = entries.get(sequenceNumber);
        if (entry != null) {
            entry.setRequest(request);
        }
    }

    public void clear() {
        entries.clear();
    }
}

