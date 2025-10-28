package org.example.statemachine;

import org.example.MessageServiceOuterClass;
import org.example.serverstate.StateMachine;

import java.util.Map;

public final class BankStateMachine implements StateMachine {

    // Mutated only on the state-manager thread.
    private final Map<String, Double> balances;

    public BankStateMachine(Map<String, Double> balances) {
        this.balances = balances;
    }

    private double requireBalance(String id) {
        Double v = balances.get(id);
        if (v == null) {
            throw new IllegalArgumentException("Account not present in balances: " + id);
        }
        return v;
    }

    @Override
    public MessageServiceOuterClass.OperationResult execute(MessageServiceOuterClass.Operation operation) {
        StateMachineOperation op = StateMachineOperationMapper.fromProto(operation);
        return op.accept(new StateMachineOperation.Visitor<>() {
            @Override
            public MessageServiceOuterClass.OperationResult onTransfer(String sender, String receiver, double amount) {
                // Validate presence of both accounts
                double fromBal = requireBalance(sender);
                double toBal = requireBalance(receiver);

                if (amount == 0.0) {
                    // No-op, success without mutating state
                    return StateMachineOperationResultMapper.toProto(StateMachineOperationResult.result(true));
                }

                if (fromBal < amount) {
                    // Failure -> boolean result false; do not mutate
                    return StateMachineOperationResultMapper.toProto(StateMachineOperationResult.result(false));
                }

                balances.put(sender, fromBal - amount);
                balances.put(receiver, toBal + amount);
                // Success -> boolean result true
                return StateMachineOperationResultMapper.toProto(StateMachineOperationResult.result(true));
            }

            @Override
            public MessageServiceOuterClass.OperationResult onBalanceRequest(String accountId) {
                // Throw if account absent
                return StateMachineOperationResultMapper.toProto(StateMachineOperationResult.balance(requireBalance(accountId)));
            }
        });
    }

    @Override
    public Map<String, Double> snapshot() {
        return Map.copyOf(balances);
    }

    @Override
    public void reset() {
        balances.clear();
    }
}
