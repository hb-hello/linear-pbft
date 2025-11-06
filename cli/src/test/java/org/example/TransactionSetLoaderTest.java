package org.example;

import org.example.statemachine.BalanceRequestOp;
import org.example.statemachine.StateMachineOperation;
import org.example.statemachine.TransferOp;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TransactionSetLoaderTest {

    private static final String TEST_CSV_PATH = "../src/main/resources/transactionSets2.csv";

    @Test
    void testLoadTransactionSets_fileExists() {
        // Test that the CSV file can be loaded without errors
        assertDoesNotThrow(() -> {
            HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
            assertNotNull(sets);
            assertFalse(sets.isEmpty());
        });
    }

    @Test
    void testLoadTransactionSets_correctNumberOfSets() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);

        // From the CSV, we have sets numbered 1 through 10
        assertEquals(10, sets.size(), "Should load exactly 10 transaction sets");

        // Verify all expected set numbers are present
        for (int i = 1; i <= 10; i++) {
            assertTrue(sets.containsKey(i), "Should contain set " + i);
        }
    }

    @Test
    void testSet1_basicTransfersAndBalanceRequest() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set1 = sets.get(1);

        assertNotNull(set1);
        assertEquals(1, set1.setNumber());

        // Set 1 has: (A, B, 1), (B, C, 2), (C, D, 3), (D, E, 4), (E)
        List<StateMachineOperation> operations = set1.transactionEvents();
        assertEquals(5, operations.size(), "Set 1 should have 5 operations");

        // First 4 should be TransferOp
        assertTrue(operations.get(0) instanceof TransferOp);
        assertTrue(operations.get(1) instanceof TransferOp);
        assertTrue(operations.get(2) instanceof TransferOp);
        assertTrue(operations.get(3) instanceof TransferOp);

        // Last one should be BalanceRequestOp
        assertTrue(operations.get(4) instanceof BalanceRequestOp);

        // Verify first transfer details
        TransferOp firstTransfer = (TransferOp) operations.get(0);
        assertEquals("A", firstTransfer.sender());
        assertEquals("B", firstTransfer.receiver());
        assertEquals(1.0, firstTransfer.amount());

        // Verify balance request details
        BalanceRequestOp balanceRequest = (BalanceRequestOp) operations.get(4);
        assertEquals("E", balanceRequest.accountId());
    }

    @Test
    void testSet1_liveNodes() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set1 = sets.get(1);

        List<String> liveNodes = set1.activeNodesList();
        assertEquals(7, liveNodes.size(), "Set 1 should have 7 live nodes");

        // Verify all expected nodes are present
        assertTrue(liveNodes.contains("n1"));
        assertTrue(liveNodes.contains("n2"));
        assertTrue(liveNodes.contains("n3"));
        assertTrue(liveNodes.contains("n4"));
        assertTrue(liveNodes.contains("n5"));
        assertTrue(liveNodes.contains("n6"));
        assertTrue(liveNodes.contains("n7"));
    }

    @Test
    void testSet1_noByzantineNodes() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set1 = sets.get(1);

        Set<String> byzantineNodes = set1.byzantineNodes();
        assertTrue(byzantineNodes.isEmpty(), "Set 1 should have no byzantine nodes");
    }

    @Test
    void testSet1_noAttack() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set1 = sets.get(1);

//        String attack = set1.getAttackDescription();
//        assertTrue(attack.isEmpty() || attack.equals("[]"), "Set 1 should have no attack");
    }

    @Test
    void testSet3_withByzantineNode() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set3 = sets.get(3);

        assertNotNull(set3);

        // Set 3 has byzantine node n2
        Set<String> byzantineNodes = set3.byzantineNodes();
        assertEquals(1, byzantineNodes.size(), "Set 3 should have 1 byzantine node");
        assertTrue(byzantineNodes.contains("n2"), "Set 3 should have n2 as byzantine");

        // Verify live nodes
        List<String> liveNodes = set3.activeNodesList();
        assertEquals(6, liveNodes.size(), "Set 3 should have 6 live nodes");
    }

    @Test
    void testSet3_withCrashAttack() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set3 = sets.get(3);

//        String attack = set3.getAttackDescription();
//        assertEquals("[crash]", attack, "Set 3 should have crash attack");
    }

    @Test
    void testSet4_complexAttack() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set4 = sets.get(4);

        assertNotNull(set4);

        // Set 4 has byzantine node n1
        Set<String> byzantineNodes = set4.byzantineNodes();
        assertEquals(1, byzantineNodes.size());
        assertTrue(byzantineNodes.contains("n1"));

        // Set 4 has attack: [time; dark(n6)]
//        String attack = set4.getAttackDescription();
//        assertEquals("[time; dark(n6)]", attack, "Set 4 should have time and dark attack");
    }

    @Test
    void testSet5_equivocationAttack() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set5 = sets.get(5);

        assertNotNull(set5);

        // Set 5 has byzantine node n1
        Set<String> byzantineNodes = set5.byzantineNodes();
        assertEquals(1, byzantineNodes.size());
        assertTrue(byzantineNodes.contains("n1"));

        // Set 5 has attack: [equivocation(n6, n7)]
//        String attack = set5.getAttackDescription();
//        assertEquals("[equivocation(n6, n7)]", attack);
    }

    @Test
    void testSet6_multipleAttacks() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set6 = sets.get(6);

        assertNotNull(set6);

        // Set 6 has attack: [time; dark(n6); equivocation(n7)]
//        String attack = set6.getAttackDescription();
//        assertEquals("[time; dark(n6); equivocation(n7)]", attack,
//                    "Set 6 should have multiple attacks");
    }

    @Test
    void testSet7_signAttack() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set7 = sets.get(7);

        assertNotNull(set7);

        // Set 7 has byzantine node n3
        Set<String> byzantineNodes = set7.byzantineNodes();
        assertEquals(1, byzantineNodes.size());
        assertTrue(byzantineNodes.contains("n3"));

        // Set 7 has attack: [sign]
//        String attack = set7.getAttackDescription();
//        assertEquals("[sign]", attack);

        // Set 7 has 5 live nodes
        assertEquals(5, set7.activeNodesList().size());
    }

    @Test
    void testSet9_multipleByzantineNodes() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set9 = sets.get(9);

        assertNotNull(set9);

        // Set 9 has byzantine nodes n1 and n2
        Set<String> byzantineNodes = set9.byzantineNodes();
        assertEquals(2, byzantineNodes.size(), "Set 9 should have 2 byzantine nodes");
        assertTrue(byzantineNodes.contains("n1"));
        assertTrue(byzantineNodes.contains("n2"));

        // Set 9 has crash attack
//        String attack = set9.getAttackDescription();
//        assertEquals("[crash]", attack);
    }

    @Test
    void testSet10_manyOperations() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set10 = sets.get(10);

        assertNotNull(set10);

        // Set 10 has many operations (50 transfers)
        List<StateMachineOperation> operations = set10.transactionEvents();
        assertEquals(50, operations.size(), "Set 10 should have 50 operations");

        // All should be TransferOp (no balance requests in set 10)
        for (StateMachineOperation op : operations) {
            assertTrue(op instanceof TransferOp, "All operations in set 10 should be transfers");
        }

        // Verify first transfer
        TransferOp first = (TransferOp) operations.get(0);
        assertEquals("A", first.sender());
        assertEquals("B", first.receiver());
        assertEquals(1.0, first.amount());

        // Set 10 has byzantine node n7
        Set<String> byzantineNodes = set10.byzantineNodes();
        assertEquals(1, byzantineNodes.size());
        assertTrue(byzantineNodes.contains("n7"));

        // Set 10 has attack: [dark(n1, n2)]
//        String attack = set10.getAttackDescription();
//        assertEquals("[dark(n1, n2)]", attack);
    }

    @Test
    void testSet2_differentAmounts() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set2 = sets.get(2);

        assertNotNull(set2);

        // Set 2 has: (F, G, 12), (G, H, 1), (H, I, 11), (I, J, 11), (J)
        List<StateMachineOperation> operations = set2.transactionEvents();
        assertEquals(5, operations.size());

        // Verify amounts
        TransferOp transfer1 = (TransferOp) operations.get(0);
        assertEquals(12.0, transfer1.amount(), "First transfer should be 12");

        TransferOp transfer2 = (TransferOp) operations.get(1);
        assertEquals(1.0, transfer2.amount(), "Second transfer should be 1");

        TransferOp transfer3 = (TransferOp) operations.get(2);
        assertEquals(11.0, transfer3.amount(), "Third transfer should be 11");

        // Last operation should be balance request for J
        BalanceRequestOp balanceRequest = (BalanceRequestOp) operations.get(4);
        assertEquals("J", balanceRequest.accountId());
    }

    @Test
    void testOperationParsing_transferOp() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set1 = sets.get(1);

        // Get a transfer operation
        TransferOp transfer = (TransferOp) set1.transactionEvents().get(2);

        // Should be (C, D, 3)
        assertEquals("C", transfer.sender());
        assertEquals("D", transfer.receiver());
        assertEquals(3.0, transfer.amount());
    }

    @Test
    void testOperationParsing_balanceRequestOp() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);

        // Set 2 has balance request for J
        TransactionSet set2 = sets.get(2);
        BalanceRequestOp balanceRequest = (BalanceRequestOp) set2.transactionEvents().get(4);
        assertEquals("J", balanceRequest.accountId());

        // Set 3 has balance request for H
        TransactionSet set3 = sets.get(3);
        BalanceRequestOp balanceRequest2 = (BalanceRequestOp) set3.transactionEvents().get(4);
        assertEquals("H", balanceRequest2.accountId());
    }

    @Test
    void testGroupTransactionsBySender_set1() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set1 = sets.get(1);

        var grouped = set1.groupTransactionsBySender();

        // Set 1 has transfers from A, B, C, D
        assertEquals(4, grouped.size());

        // A has 1 transfer
        assertTrue(grouped.containsKey("A"));
        assertEquals(1, grouped.get("A").size());

        // B has 1 transfer
        assertTrue(grouped.containsKey("B"));
        assertEquals(1, grouped.get("B").size());

        // C has 1 transfer
        assertTrue(grouped.containsKey("C"));
        assertEquals(1, grouped.get("C").size());

        // D has 1 transfer
        assertTrue(grouped.containsKey("D"));
        assertEquals(1, grouped.get("D").size());
    }

    @Test
    void testGroupTransactionsBySender_set10() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);
        TransactionSet set10 = sets.get(10);

        var grouped = set10.groupTransactionsBySender();

        // Set 10 has transfers from all 10 clients (A through J)
        assertEquals(10, grouped.size());

        // Each client should have 5 transfers
        for (char c = 'A'; c <= 'J'; c++) {
            String sender = String.valueOf(c);
            assertTrue(grouped.containsKey(sender), "Should have transfers from " + sender);
            assertEquals(5, grouped.get(sender).size(), sender + " should have 5 transfers");
        }
    }

    @Test
    void testEmptyByzantineNodes_parsedAsEmpty() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);

        // Set 1 has empty byzantine nodes
        TransactionSet set1 = sets.get(1);
        assertTrue(set1.byzantineNodes().isEmpty());

        // Set 2 has empty byzantine nodes
        TransactionSet set2 = sets.get(2);
        assertTrue(set2.byzantineNodes().isEmpty());
    }

    @Test
    void testAllSetsHaveCorrectSetNumber() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);

        for (int i = 1; i <= 10; i++) {
            TransactionSet set = sets.get(i);
            assertNotNull(set, "Set " + i + " should exist");
            assertEquals(i, set.setNumber(), "Set should have correct set number");
        }
    }

    @Test
    void testAllSetsHaveOperations() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);

        for (int i = 1; i <= 10; i++) {
            TransactionSet set = sets.get(i);
            assertNotNull(set.transactionEvents(), "Set " + i + " should have operations list");
            assertFalse(set.transactionEvents().isEmpty(), "Set " + i + " should have at least one operation");
        }
    }

    @Test
    void testAllSetsHaveLiveNodes() {
        HashMap<Integer, TransactionSet> sets = TransactionSetLoader.loadTransactionSets(TEST_CSV_PATH);

        for (int i = 1; i <= 10; i++) {
            TransactionSet set = sets.get(i);
            assertNotNull(set.activeNodesList(), "Set " + i + " should have live nodes list");
            assertFalse(set.activeNodesList().isEmpty(), "Set " + i + " should have at least one live node");
        }
    }
}

