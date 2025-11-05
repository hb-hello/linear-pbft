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
 * - Implicitly creates consensus messages when first reply arrives
 * - Keeps completed consensus messages for later tracking
 */
public class ConsensusMessageTracker<K, V> {

    private static final Logger logger = LogManager.getLogger(ConsensusMessageTracker.class);

    protected final ConcurrentMap<K, ConsensusMessage<K, V>> tracked = new ConcurrentHashMap<>();

    private final Function<Message, K> requestIdExtractor;
    private final Function<Message, String> responderIdExtractor;
    private final Function<Message, V> valueExtractor;

    /**
     * Create a consensus tracker with extractors that will be used for all consensus messages.
     *
     * @param requestIdExtractor Extracts request ID from a reply message
     * @param responderIdExtractor Extracts responder ID from a reply message
     * @param valueExtractor Extracts the value to group by from a reply message
     */
    public ConsensusMessageTracker(Function<Message, K> requestIdExtractor,
                                   Function<Message, String> responderIdExtractor,
                                   Function<Message, V> valueExtractor) {
        this.requestIdExtractor = requestIdExtractor;
        this.responderIdExtractor = responderIdExtractor;
        this.valueExtractor = valueExtractor;
    }

    /** Record an incoming response by request id for O(1) lookup. Implicitly creates consensus message if needed. */
    public boolean recordMessage(K requestId, Message reply, int required) {
        // Get or create consensus message for this request ID
        ConsensusMessage<K, V> state = tracked.computeIfAbsent(requestId, id -> {
            logger.info("Implicitly creating consensus tracker when recording reply for requestId={}.", id);
            return new ConsensusMessage<>(id, required, requestIdExtractor, responderIdExtractor, valueExtractor);
        });

        System.out.println("DEBUG: recordMessage for " + requestId + " - using ConsensusMessage@" + System.identityHashCode(state) + ", future@" + System.identityHashCode(state.future()));
        state.addReply(reply);
        return true;
    }

    /**
     * Record an incoming response by request id and check if quorum was reached.
     * Implicitly creates consensus message if needed.
     *
     * @param requestId The request identifier
     * @param reply The reply message to record
     * @param required Number of matching responses required for consensus
     * @return true if quorum was reached after adding this reply, false otherwise
     */
    public boolean recordMessageAndCheckQuorum(K requestId, Message reply, int required) {
        // Record the reply first (creates consensus message if needed)
        recordMessage(requestId, reply, required);

        // Now check if quorum was reached
        return checkMessageQuorum(requestId);
    }

    /** Block until N matching replies are received for requestId, or timeout occurs. Implicitly creates consensus message if needed. */
    public Message awaitConsensus(K requestId, Duration timeout, int required)
            throws InterruptedException, TimeoutException {
        // Get or create consensus message for this request ID
        ConsensusMessage<K, V> state = tracked.computeIfAbsent(requestId, id -> {
            logger.info("Implicitly creating consensus tracker for requestId={} with required responses = {}.", id, required);
            return new ConsensusMessage<>(id, required, requestIdExtractor, responderIdExtractor, valueExtractor);
        });

        System.out.println("DEBUG: awaitConsensus for " + requestId + " - using ConsensusMessage@" + System.identityHashCode(state) + ", future@" + System.identityHashCode(state.future()));
        System.out.println("DEBUG: awaitConsensus for " + requestId + " - future.isDone()=" + state.future().isDone());

        try {
            if (state.future().isDone()) {
                // Already completed
                System.out.println("DEBUG: awaitConsensus for " + requestId + " - future already done, returning immediately");
                return state.future().get();
            }
            // Keep the state in tracked map even after completion
            System.out.println("DEBUG: awaitConsensus for " + requestId + " - waiting up to " + timeout.toMillis() + "ms for future to complete");
            Message result = state.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            System.out.println("DEBUG: awaitConsensus for " + requestId + " - future completed successfully!");
            return result;
        } catch (ExecutionException e) {
            throw new RuntimeException("Consensus wait failed", e.getCause());
        } catch (TimeoutException te) {
            // keep state for continued waiting
            System.out.println("DEBUG: awaitConsensus for " + requestId + " - TIMEOUT! future.isDone()=" + state.future().isDone());
            logger.info("Timeout waiting for consensus on requestId={}", requestId);
            throw te;
        }
    }

    /** Cancel and clean up a tracking bucket; completes its future exceptionally with CancellationException. */
    public boolean cancel(K requestId) {
        ConsensusMessage<K, V> state = tracked.remove(requestId);
        if (state == null) return false;
        state.future().completeExceptionally(new CancellationException("Consensus cancelled for requestId=" + requestId));
        return true;
    }

    /** Non-blocking snapshot of counts for the request id. */
    public Optional<Status> getStatus(K requestId, int required) {
        ConsensusMessage<K, V> state = tracked.get(requestId);
        if (state == null) return Optional.empty();
        Map<V, Integer> counts = state.snapshotCounts();
        return Optional.of(new Status(state.uniqueResponders(), counts, required));
    }

    public int getQuorumRequired(K requestId) {
        ConsensusMessage<K, V> state = tracked.get(requestId);
        if (state == null) return -1;
        return state.getQuorumRequired();
    }

    /**
     * Check if a quorum of messages with the same request ID exists.
     *
     * @param requestId The request identifier
     * @return true if quorum is met, false otherwise
     */
    public boolean checkMessageQuorum(K requestId) {
        ConsensusMessage<K, V> state = tracked.get(requestId);
        if (state == null) return false;
        return state.checkQuorum();
    }

    public V getQuorumValue(K requestId) {
        ConsensusMessage<K, V> state = tracked.get(requestId);
        if (state == null) return null;
        return state.getQuorumValue();
    }

    /**
     * Clear all tracked consensus messages.
     */
    public void clear() {
        // cancel all tracked messages
        for (ConsensusMessage<K, V> state : tracked.values()) {
            state.future().completeExceptionally(new CancellationException("Consensus tracker cleared"));
        }
        tracked.clear();
        logger.info("Cleared all tracked consensus messages.");
    }

    /**
     * Get the ConsensusMessage for a given request ID.
     * Protected method to allow subclasses to access tracked messages.
     *
     * @param requestId the request identifier
     * @return the ConsensusMessage, or null if not tracked
     */
    protected ConsensusMessage<K, V> getConsensusMessage(K requestId) {
        return tracked.get(requestId);
    }

    /** Simple status DTO */
    public record Status(int uniqueResponders, Map<?, Integer> counts, int required) {
        public boolean quorumReached() {
            return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0) >= required;
        }
    }
}
