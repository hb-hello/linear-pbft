package org.example.serverstate;

public enum OperationStatus {
    PREPREPARED,
    PREPARED,
    COMMITTED,
    EXECUTED,
    CHECKPOINTED,
    NONE
}