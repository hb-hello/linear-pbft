package org.example.statemachine;

import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BankStateMachineTest {

    private static Map<String, Double> initialBalances;

    @BeforeAll
    static void setupConfig() {
        // Initialize config for tests and load client balances
        Config.initialize("src/test/resources/config.properties");
        initialBalances = Config.getClientBalances();
    }

    private BankStateMachine newSMWithAB() {
        Map<String, Double> m = new HashMap<>();
        m.put("A", initialBalances.get("A"));
        m.put("B", initialBalances.get("B"));
        return new BankStateMachine(m);
    }

    private MessageServiceOuterClass.Operation transferOp(String from, String to, double amount) {
        return MessageServiceOuterClass.Operation.newBuilder()
                .setTransfer(MessageServiceOuterClass.Transfer.newBuilder()
                        .setSender(from)
                        .setReceiver(to)
                        .setAmount(amount)
                        .build())
                .build();
    }

    private MessageServiceOuterClass.Operation balanceOp(String account) {
        return MessageServiceOuterClass.Operation.newBuilder()
                .setBalanceRequest(MessageServiceOuterClass.BalanceRequest.newBuilder()
                        .setAccountId(account)
                        .build())
                .build();
    }

    @Test
    void transfer_success_updates_balances_and_returns_true() {
        BankStateMachine sm = newSMWithAB();

        var op = transferOp("A", "B", 5.0);
        MessageServiceOuterClass.OperationResult res = sm.execute(op);

        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.RESULT, res.getOpCase(), "Should be a boolean result");
        assertTrue(res.getResult(), "Successful transfer should return true");

        Map<String, Double> snapshot = sm.snapshot();
        double a0 = initialBalances.get("A");
        double b0 = initialBalances.get("B");
        assertEquals(a0 - 5.0, snapshot.get("A"), 1e-9);
        assertEquals(b0 + 5.0, snapshot.get("B"), 1e-9);
    }

    @Test
    void transfer_insufficient_funds_returns_false_and_does_not_mutate_state() {
        BankStateMachine sm = newSMWithAB();

        double a0 = initialBalances.get("A");
        double b0 = initialBalances.get("B");
        var op = transferOp("A", "B", a0 + 10.0); // more than available
        MessageServiceOuterClass.OperationResult res = sm.execute(op);

        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.RESULT, res.getOpCase());
        assertFalse(res.getResult(), "Insufficient funds should return false");
        Map<String, Double> snapshot = sm.snapshot();
        assertEquals(a0, snapshot.get("A"), 1e-9, "Sender balance should remain unchanged");
        assertEquals(b0, snapshot.get("B"), 1e-9, "Receiver balance should remain unchanged");

        // Verify subsequent balance reads still reflect current stored balance
        var bal = balanceOp("A");
        MessageServiceOuterClass.OperationResult balRes = sm.execute(bal);
        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.BALANCE, balRes.getOpCase());
        assertEquals(a0, balRes.getBalance(), 1e-9);
    }

    @Test
    void balance_request_returns_current_balance() {
        BankStateMachine sm = newSMWithAB();

        // Pre-change state with a transfer A->B of 3.0
        sm.execute(transferOp("A", "B", 3.0));

        double a0 = initialBalances.get("A");
        double b0 = initialBalances.get("B");

        MessageServiceOuterClass.OperationResult resB = sm.execute(balanceOp("B"));
        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.BALANCE, resB.getOpCase(), "Balance branch expected");
        assertEquals(b0 + 3.0, resB.getBalance(), 1e-9, "B should have initial+3");

        MessageServiceOuterClass.OperationResult resA = sm.execute(balanceOp("A"));
        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.BALANCE, resA.getOpCase());
        assertEquals(a0 - 3.0, resA.getBalance(), 1e-9, "A should have initial-3");
    }

    @Test
    void reset_clears_mutations_and_snapshot_is_empty() {
        BankStateMachine sm = newSMWithAB();
        sm.execute(transferOp("A", "B", 4.0));
        assertFalse(sm.snapshot().isEmpty(), "State should be mutated after transfer");

        sm.reset();
        assertTrue(sm.snapshot().isEmpty(), "Snapshot should be empty after reset");

        // After reset, accounts are absent; balance request should throw
        assertThrows(IllegalArgumentException.class, () -> sm.execute(balanceOp("A")));
    }

    @Test
    void transfer_zero_amount_is_noop_and_returns_true() {
        BankStateMachine sm = newSMWithAB();

        var op = transferOp("A", "B", 0.0);
        MessageServiceOuterClass.OperationResult res = sm.execute(op);

        assertEquals(MessageServiceOuterClass.OperationResult.OpCase.RESULT, res.getOpCase());
        assertTrue(res.getResult(), "Zero-amount transfer should be treated as success");

        Map<String, Double> snapshot = sm.snapshot();
        double a0 = initialBalances.get("A");
        double b0 = initialBalances.get("B");
        assertEquals(a0, snapshot.get("A"), 1e-9, "A's balance should remain at initial value");
        assertEquals(b0, snapshot.get("B"), 1e-9, "B's balance should remain at initial value");
    }

    @Test
    void transfer_missing_sender_throws() {
        Map<String, Double> m = new HashMap<>();
        m.put("B", initialBalances.get("B"));
        BankStateMachine sm = new BankStateMachine(m);
        assertThrows(IllegalArgumentException.class, () -> sm.execute(transferOp("A", "B", 1.0)));
    }

    @Test
    void transfer_missing_receiver_throws() {
        Map<String, Double> m = new HashMap<>();
        m.put("A", initialBalances.get("A"));
        BankStateMachine sm = new BankStateMachine(m);
        assertThrows(IllegalArgumentException.class, () -> sm.execute(transferOp("A", "B", 1.0)));
    }

    @Test
    void balance_missing_account_throws() {
        Map<String, Double> m = new HashMap<>();
        m.put("A", initialBalances.get("A"));
        BankStateMachine sm = new BankStateMachine(m);
        assertThrows(IllegalArgumentException.class, () -> sm.execute(balanceOp("Z")));
    }
}
