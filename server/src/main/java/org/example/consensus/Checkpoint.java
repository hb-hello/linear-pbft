package org.example.consensus;

import org.example.MessageServiceOuterClass;

import java.util.Map;

public record Checkpoint(Object stateSnapshot, Map<String, Long> replyTimestamps, Map<String, MessageServiceOuterClass.OperationResult> resultCache) {
}
