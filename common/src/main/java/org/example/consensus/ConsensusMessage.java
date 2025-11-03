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

    private final Function<Message, K> requestIdExtractor;
    private final Function<Message, String> responderIdExtractor;
    private final Function<Message, V> valueExtractor;

    private final CompletableFuture<Message> future = new CompletableFuture<>();
    private final Set<String> respondersSeen = ConcurrentHashMap.newKeySet(); // dedupe by responder id
    private final ConcurrentMap<V, AtomicInteger> valueCounts = new ConcurrentHashMap<>(); // value -> count
    private final ConcurrentMap<V, Message> representative = new ConcurrentHashMap<>(); // value -> exemplar response

    public ConsensusMessage(K requestId,
                            Function<Message, K> requestIdExtractor,
                            Function<Message, String> responderIdExtractor,
                            Function<Message, V> valueExtractor) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
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
        representative.putIfAbsent(value, reply);
        valueCounts.computeIfAbsent(value, k -> new AtomicInteger()).incrementAndGet();

        int count = valueCounts.get(value).get();
//        System.out.println("Request " + requestId + " received value " + value + " count " + count);
    }

    public CompletableFuture<Message> future() { return future; }

    public int uniqueResponders() { return respondersSeen.size(); }

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
     * @param required Number of matching responses required for consensus
     * @return true if quorum is met, false otherwise
     */
    public boolean checkQuorum(int required) {
        // Handle edge case: if required is 0 or negative, quorum is always met if we have any messages
        if (required <= 0) {
            return !valueCounts.isEmpty();
        }

        // Check if any value (including empty values like empty digests) has reached the required count
        for (Map.Entry<V, AtomicInteger> entry : valueCounts.entrySet()) {
            if (entry.getValue().get() >= required) {
                // Complete future if not already done (handles null values gracefully)
                Message representativeMsg = representative.get(entry.getKey());
                if (representativeMsg != null) {
                    future.complete(representativeMsg);
                }
                return true;
            }
        }
        return false;
    }

    public K requestId() { return requestId; }
}
