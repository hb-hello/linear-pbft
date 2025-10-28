package org.example.statemachine;

/**
 * Simpler alternative to the sealed OperationResult hierarchy: a single record
 * with exactly one of 'result' (boolean) or 'balance' (double) present.
 */
public record StateMachineOperationResult(Boolean result, Double balance) {
    public StateMachineOperationResult {
        // Exactly one must be non-null
        if ((result == null) == (balance == null)) {
            throw new IllegalArgumentException("Exactly one of 'result' or 'balance' must be set");
        }
    }

    public static StateMachineOperationResult result(boolean value) {
        return new StateMachineOperationResult(value, null);
    }

    public static StateMachineOperationResult balance(double value) {
        return new StateMachineOperationResult(null, value);
    }

    public boolean isResult() { return result != null; }
    public boolean isBalance() { return balance != null; }
}

