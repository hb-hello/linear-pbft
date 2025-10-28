package org.example.serverstate;

import org.example.statemachine.StateMachineOperation;

import java.util.Map;

public interface StateMachine {
    // Execute a single deterministic operation; return a domain result object.
    Object execute(StateMachineOperation op);

    // Consistent snapshot of application state for CLI/inspection.
    Map<String, Double> snapshot();

    // Clear all application state (used between test sets).
    void reset();
}
