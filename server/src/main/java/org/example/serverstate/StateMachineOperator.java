package org.example.serverstate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.statemachine.BankStateMachine;

import java.util.HashMap;

public class StateMachineOperator {
    private static final Logger logger = LogManager.getLogger(StateMachineOperator.class);

    private final ServerState state;
    private final StateMachine stateMachine;
    private long lastExecutedSeqNum;

    // all methods should be called from within state's runSync
    public StateMachineOperator(ServerState state) {
        this.state = state;
        this.stateMachine = new BankStateMachine(new HashMap<>(Config.getClientBalances()));;
        this.lastExecutedSeqNum = 0L;
    }

    public void markExecutedUpTo(long executedSeqNum) {
        lastExecutedSeqNum = Math.max(lastExecutedSeqNum, executedSeqNum);
    }

    // State-machine operations — example transfer and read-only balance

    // Generic execute that delegates to the pluggable state machine
    public MessageServiceOuterClass.OperationResult executeOperation(MessageServiceOuterClass.Operation operation) {
        logger.info("Executing operation of type: {}", operation.getOpCase());
        return stateMachine.execute(operation);
    }

    public Object snapshot() {
        return stateMachine.snapshot();
    }

    public void reset() {
        lastExecutedSeqNum = 0L;
        stateMachine.reset();
    }

}
