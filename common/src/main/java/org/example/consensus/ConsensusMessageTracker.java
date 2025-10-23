package org.example.consensus;

import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Heterogeneous consensus tracker keyed by request id (K). Each in-flight request is represented
 * by a ConsensusMessage<K, ?> instance that owns its own extractors.
 *
 * - Supports different message types simultaneously (each bucket defines its own extractors)
 * - Deduplicates by responder id and groups by a value key until required threshold is reached
 */
public class ConsensusMessageTracker<K> {

    private static final Logger logger = LogManager.getLogger(ConsensusMessageTracker.class);

    private final ConcurrentMap<K, ConsensusMessage<K, ?>> inFlight = new ConcurrentHashMap<>();

    /** Register a pre-constructed consensus bucket. If a bucket already exists for the same id, keeps the existing one. */
    public <V> void startTracking(ConsensusMessage<K, V> consensus) {
        inFlight.putIfAbsent(consensus.requestId(), consensus);
        logger.debug("Start tracking requestId={} with required responses = {}.", consensus.requestId(), consensus.required());
    }

    /** Convenience: construct and register a consensus bucket, then return it to the caller. */
    public <V> ConsensusMessage<K, V> startTracking(K requestId,
                                                    int required,
                                                    Function<Message, K> requestIdExtractor,
                                                    Function<Message, String> responderIdExtractor,
                                                    Function<Message, V> valueExtractor) {
        ConsensusMessage<K, V> cm = new ConsensusMessage<>(requestId, required,
                requestIdExtractor, responderIdExtractor, valueExtractor);
        startTracking(cm);
        return cm;
    }

    /** Record an incoming response by request id for O(1) lookup. */
    public boolean recordReply(K requestId, Message reply) {
        ConsensusMessage<K, ?> state = inFlight.get(requestId);
        if (state == null) {
            logger.debug("Received reply for untracked requestId={}, ignoring.", requestId);
            return false;
        }
        state.addReply(reply);
        if (state.isCompleted()) {
            inFlight.remove(requestId, state);
        }
        return true;
    }

    /** Record an incoming response by scanning buckets to find a match via canAccept(). */
    public boolean recordReply(Message reply) {
        for (Map.Entry<K, ConsensusMessage<K, ?>> e : inFlight.entrySet()) {
            ConsensusMessage<K, ?> state = e.getValue();
            if (state.canAccept(reply)) {
                state.addReply(reply);
                if (state.isCompleted()) {
                    inFlight.remove(e.getKey(), state);
                }
                return true;
            }
        }
        logger.debug("Received reply that matched no in-flight request. Ignoring.");
        return false;
    }

    /** Block until N matching replies are received for requestId, or timeout occurs. */
    public Message awaitConsensus(K requestId, Duration timeout)
            throws InterruptedException, TimeoutException {
        ConsensusMessage<K, ?> state = inFlight.get(requestId);
        if (state == null) {
            throw new IllegalStateException("awaitConsensus called before startTracking for requestId: " + requestId);
        }
        try {
            Message reply = state.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            inFlight.remove(requestId, state);
            return reply;
        } catch (ExecutionException e) {
            throw new RuntimeException("Consensus wait failed", e.getCause());
        } catch (TimeoutException te) {
            // keep state for continued waiting
            logger.info("Timeout waiting for consensus on requestId={}", requestId);
            throw te;
        }
    }

    /** Cancel and clean up a tracking bucket; completes its future exceptionally with CancellationException. */
    public boolean cancel(K requestId) {
        ConsensusMessage<K, ?> state = inFlight.remove(requestId);
        if (state == null) return false;
        state.future().completeExceptionally(new CancellationException("Consensus cancelled for requestId=" + requestId));
        return true;
    }

    /** Non-blocking snapshot of counts for the request id. */
    public Optional<Status> getStatus(K requestId) {
        ConsensusMessage<K, ?> state = inFlight.get(requestId);
        if (state == null) return Optional.empty();
        Map<?, Integer> counts = state.snapshotCounts();
        return Optional.of(new Status(state.uniqueResponders(), counts, state.required()));
    }

    /** Simple status DTO */
    public record Status(int uniqueResponders, Map<?, Integer> counts, int required) {
        public boolean quorumReached() {
            return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0) >= required;
        }
    }
}
