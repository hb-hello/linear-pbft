package org.example.serverstate;

import org.example.MessageServiceOuterClass;

/**
 * State machine execution contract used by ServerState.
 * <p>
 * Contract:
 * - Input: a gRPC {@link MessageServiceOuterClass.Operation} built from client requests.
 * - Output: a gRPC {@link MessageServiceOuterClass.OperationResult} oneof:
 *   - RESULT (boolean): for mutation-style operations (e.g., Transfer): true on success, false on insufficient funds.
 *   - BALANCE (double): for read-only operations (e.g., BalanceRequest): current balance value.
 * <p>
 * Error behavior:
 * - Missing accounts (sender/receiver/account not present in the state machine's balances) MUST throw {@link IllegalArgumentException}.
 * - Zero-amount transfers MUST succeed without mutating balances (RESULT=true).
 */
public interface StateMachine {
    /**
     * Execute a single deterministic operation and return a proto OperationResult.
     * Implementations should not mutate state for read-only operations and must enforce the error behavior above.
     */
    MessageServiceOuterClass.OperationResult execute(MessageServiceOuterClass.Operation operation);

    /**
     * Consistent snapshot of application state for CLI/inspection.
     * The returned object must be immutable or a defensive copy of internal state (e.g., Map.copyOf).
     */
    Object snapshot();

    /**
     * Clear all application state (used between test sets and on ServerState.reset()).
     */
    void reset();
}
