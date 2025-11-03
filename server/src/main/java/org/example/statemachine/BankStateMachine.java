package org.example.statemachine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.serverstate.StateMachine;

import java.util.HashMap;
import java.util.Map;

public final class BankStateMachine implements StateMachine {

    private static final Logger logger = LogManager.getLogger(BankStateMachine.class);

    // Mutated only on the state-manager thread.
    private final Map<String, Double> balances;
    private final Map<String, Double> initialBalances; // Store initial balances for reset

    public BankStateMachine(Map<String, Double> balances) {
        this.initialBalances = Map.copyOf(balances); // Immutable copy of initial state
        this.balances = new HashMap<>(balances);
        logger.info("BankStateMachine initialized with balances: {}", this.balances);
    }

    private double requireBalance(String id) {
        logger.info("requireBalance: Looking up account ID: '{}' (length: {}, type: {})",
            id, id.length(), id.getClass().getSimpleName());
        logger.info("requireBalance: Current balances map keys: {}", balances.keySet());
        logger.info("requireBalance: Current balances map size: {}", balances.size());

        // Check for exact matches and similar keys
        for (String key : balances.keySet()) {
            logger.info("requireBalance: Comparing with key: '{}' (length: {}, equals: {})",
                key, key.length(), key.equals(id));
        }

        Double v = balances.get(id);
        logger.info("requireBalance: Fetched balance for '{}': {}", id, v);

        if (v == null) {
            logger.error("requireBalance: Account '{}' not present in balances, throwing exception", id);
            logger.error("requireBalance: Available accounts are: {}", balances.keySet());
            throw new IllegalArgumentException("Account not present in balances: " + id);
        }
        return v;
    }

    @Override
    public MessageServiceOuterClass.OperationResult execute(MessageServiceOuterClass.Operation operation) {
        logger.info("EXECUTE START: Received operation to execute");
        logger.info("EXECUTE: Operation proto: {}", operation);

        StateMachineOperation op = StateMachineOperationMapper.fromProto(operation);
        logger.info("EXECUTE: Unmarshalled operation: {}, now executing", op);

        logger.info("EXECUTE: About to call visitor accept");
        MessageServiceOuterClass.OperationResult result = op.accept(new StateMachineOperation.Visitor<>() {
            @Override
            public MessageServiceOuterClass.OperationResult onTransfer(String sender, String receiver, double amount) {
                logger.info("VISITOR: onTransfer called - sender={}, receiver={}, amount={}", sender, receiver, amount);

                // Validate presence of both accounts
                logger.info("VISITOR: Checking sender balance for {}", sender);
                double fromBal = requireBalance(sender);
                logger.info("VISITOR: Sender {} has balance {}", sender, fromBal);

                logger.info("VISITOR: Checking receiver balance for {}", receiver);
                double toBal = requireBalance(receiver);
                logger.info("VISITOR: Receiver {} has balance {}", receiver, toBal);

                if (amount == 0.0) {
                    logger.info("VISITOR: Amount is 0.0, returning success without mutation");
                    // No-op, success without mutating state
                    return StateMachineOperationResultMapper.toProto(StateMachineOperationResult.result(true));
                }

                if (fromBal < amount) {
                    logger.info("VISITOR: Insufficient funds - fromBal {} < amount {}, returning failure", fromBal, amount);
                    // Failure -> boolean result false; do not mutate
                    return StateMachineOperationResultMapper.toProto(StateMachineOperationResult.result(false));
                }

                logger.info("VISITOR: Performing transfer - updating balances");
                balances.put(sender, fromBal - amount);
                balances.put(receiver, toBal + amount);
                logger.info("VISITOR: Transfer complete - new balances: sender={}, receiver={}",
                    balances.get(sender), balances.get(receiver));

                // Success -> boolean result true
                return StateMachineOperationResultMapper.toProto(StateMachineOperationResult.result(true));
            }

            @Override
            public MessageServiceOuterClass.OperationResult onBalanceRequest(String accountId) {
                logger.info("VISITOR: onBalanceRequest called - accountId={}", accountId);
                logger.info("VISITOR: Fetching balance for {}", accountId);

                // Throw if account absent
                double balance = requireBalance(accountId);
                logger.info("VISITOR: Account {} has balance {}", accountId, balance);

                MessageServiceOuterClass.OperationResult result = StateMachineOperationResultMapper.toProto(StateMachineOperationResult.balance(balance));
                logger.info("VISITOR: Returning balance result");
                return result;
            }
        });

        logger.info("EXECUTE: Visitor accept returned, result: {}", result);
        logger.info("EXECUTE END: Returning operation result");
        return result;
    }

    @Override
    public Map<String, Double> snapshot() {
        return Map.copyOf(balances);
    }

    @Override
    public void reset() {
        logger.info("RESET: Clearing balances and restoring initial state");
        logger.info("RESET: Current balances before clear: {}", balances);
        balances.clear();
        balances.putAll(initialBalances);
        logger.info("RESET: Balances after restore: {}", balances);
    }
}
