package org.example.consensus;

import com.google.protobuf.Message;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Generic per-request consensus collector that aggregates unique responses and
 * tracks counts per equivalence class (value key) until a threshold is met.
 *
 * K - request identifier type (e.g., String, composite key)
 * V - value-key used to group equivalent responses (e.g., boolean, enum, digest)
 */
public class ConsensusMessage<K, V> {

    private final K requestId;
    private final int required;

    private final Function<Message, K> requestIdExtractor;
    private final Function<Message, String> responderIdExtractor;
    private final Function<Message, V> valueExtractor;

    private final CompletableFuture<Message> future = new CompletableFuture<>();
    private final Set<String> respondersSeen = ConcurrentHashMap.newKeySet(); // dedupe by responder id
    private final ConcurrentMap<V, AtomicInteger> valueCounts = new ConcurrentHashMap<>(); // value -> count
    private final ConcurrentMap<V, Message> representative = new ConcurrentHashMap<>(); // value -> exemplar response

    public ConsensusMessage(K requestId, int required,
                            Function<Message, K> requestIdExtractor,
                            Function<Message, String> responderIdExtractor,
                            Function<Message, V> valueExtractor) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.required = required;
        this.requestIdExtractor = Objects.requireNonNull(requestIdExtractor, "requestIdExtractor");
        this.responderIdExtractor = Objects.requireNonNull(responderIdExtractor, "responderIdExtractor");
        this.valueExtractor = Objects.requireNonNull(valueExtractor, "valueExtractor");
    }

    /** Whether this bucket accepts the given reply (requestId matches). */
    public boolean canAccept(Message reply) {
        try {
            K id = requestIdExtractor.apply(reply);
            boolean canAccept = Objects.equals(this.requestId, id);
//            System.out.println("Reply with id " + id + "can be accepted for message with id " + this.requestId + " : " + canAccept);
            return canAccept;
        } catch (RuntimeException ex) {
            // extractor may throw if reply is of unexpected type; treat as non-match
            return false;
        }
    }

    /** Add a reply; dedup by responder id and count by value key. No-op if reply doesn't match this bucket. */
    public void addReply(Message reply) {
        if (!canAccept(reply)) return;
        String responderId = responderIdExtractor.apply(reply);
        if (!respondersSeen.add(responderId)) {
//            System.out.println("Duplicate reply from responder " + responderId + " for request " + requestId);
            return; // duplicate from same responder
        }
        V value = valueExtractor.apply(reply);
        System.out.println("DEBUG: Request " + requestId + " - adding reply from " + responderId + " with value: " + value + " (hashCode=" + (value != null ? value.hashCode() : "null") + ")");
        representative.putIfAbsent(value, reply);
        valueCounts.computeIfAbsent(value, k -> new AtomicInteger()).incrementAndGet();

        int count = valueCounts.get(value).get();
        System.out.println("DEBUG: Request " + requestId + " - value count is now " + count + " (required=" + required + ")");
        System.out.println("DEBUG: Request " + requestId + " - valueCounts map has " + valueCounts.size() + " distinct values");

        // Check if quorum is reached and complete the future if so
        checkQuorum();
    }

    public CompletableFuture<Message> future() { return future; }

    public int uniqueResponders() { return respondersSeen.size(); }

    public int getQuorumRequired() { return required; }

    /** Snapshot counts map (copy) for inspection */
    public Map<V, Integer> snapshotCounts() {
        ConcurrentHashMap<V, Integer> copy = new ConcurrentHashMap<>();
        valueCounts.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }

    /**
     * Check if quorum is reached for any value. If quorum is met and future not yet completed,
     * completes the future with the representative message for that value.
     * Handles empty values (e.g., empty digests) as valid consensus values.
     *
     * @return true if quorum is met, false otherwise
     */
    public boolean checkQuorum() {
        // Handle edge case: if required is 0 or negative, quorum is always met if we have any messages
        if (required <= 0) {
            return !valueCounts.isEmpty();
        }

        if (future.isDone()) {
            System.out.println("DEBUG: Request " + requestId + " - checkQuorum called but future already done");
            return true; // already completed
        }

        System.out.println("DEBUG: Request " + requestId + " - checking quorum, valueCounts=" + valueCounts.size() + " entries");
        // Check if any value (including empty values like empty digests) has reached the required count
        for (Map.Entry<V, AtomicInteger> entry : valueCounts.entrySet()) {
            int count = entry.getValue().get();
            System.out.println("DEBUG: Request " + requestId + " - value (hashCode=" + (entry.getKey() != null ? entry.getKey().hashCode() : "null") + ") has count " + count + ", required=" + required);
            if (count >= required) {
                // Complete future if not already done (handles null values gracefully)
                Message representativeMsg = representative.get(entry.getKey());
                System.out.println("DEBUG: Request " + requestId + " - QUORUM REACHED! representativeMsg is " + (representativeMsg != null ? "NOT NULL" : "NULL"));
                System.out.println("DEBUG: Request " + requestId + " - future@" + System.identityHashCode(future) + ", isDone=" + future.isDone());
                if (representativeMsg != null) {
                    boolean completed = future.complete(representativeMsg);
                    System.out.println("DEBUG: Request " + requestId + " - future.complete() returned " + completed + ", isDone now=" + future.isDone());
                } else {
                    System.out.println("ERROR: Request " + requestId + " - representativeMsg is NULL, future NOT completed!");
                }
                return true;
            }
        }
        System.out.println("DEBUG: Request " + requestId + " - no quorum yet");
        return false;
    }

    public V getQuorumValue() {

        if (!future.isDone()) {
            throw new IllegalStateException("Quorum not yet reached for requestId=" + requestId);
        }

        try {
            Message msg = future.get();
            return valueExtractor.apply(msg);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to get quorum value for requestId=" + requestId, e);
        }
    }

    public K requestId() { return requestId; }
}
