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

    public ConsensusMessage(K requestId,
                            int required,
                            Function<Message, K> requestIdExtractor,
                            Function<Message, String> responderIdExtractor,
                            Function<Message, V> valueExtractor) {
        if (required <= 0) throw new IllegalArgumentException("required must be > 0");
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
            return Objects.equals(this.requestId, id);
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
            return; // duplicate from same responder
        }
        V value = valueExtractor.apply(reply);
        representative.putIfAbsent(value, reply);
        valueCounts.computeIfAbsent(value, k -> new AtomicInteger()).incrementAndGet();

        int count = valueCounts.get(value).get();
        if (count >= required) {
            future.complete(representative.get(value));
        }
    }

    public CompletableFuture<Message> future() { return future; }

    public int required() { return required; }

    public int uniqueResponders() { return respondersSeen.size(); }

    /** Snapshot counts map (copy) for inspection */
    public Map<V, Integer> snapshotCounts() {
        ConcurrentHashMap<V, Integer> copy = new ConcurrentHashMap<>();
        valueCounts.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }

    public boolean isCompleted() { return future.isDone(); }

    public K requestId() { return requestId; }
}
