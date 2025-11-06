package org.example;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify the getMaliceMessages() behavior with repeated fields
 */
public class TransactionSetMaliceTest {

    @Test
    public void testSingleAttackWithMultipleTargets() {
        // Test case: equivocation(n6, n7) with byzantine node n1
        TransactionSet set = new TransactionSet(1);
        Set<String> byzantineNodes = new HashSet<>();
        byzantineNodes.add("n1");
        set.setByzantineNodes(byzantineNodes);
        set.setAttackDescription("[equivocation(n6, n7)]");

        List<MessageServiceOuterClass.Malice> maliceMessages = set.getMaliceMessages();

        // Should have exactly 1 Malice object (one per attack type)
        assertEquals(1, maliceMessages.size());

        MessageServiceOuterClass.Malice malice = maliceMessages.get(0);
        assertEquals("equivocation", malice.getMaliceType());

        // Should have 1 byzantine node
        assertEquals(1, malice.getMaliciousServerIdCount());
        assertTrue(malice.getMaliciousServerIdList().contains("n1"));

        // Should have 2 targets
        assertEquals(2, malice.getTargetServerIdCount());
        assertTrue(malice.getTargetServerIdList().contains("n6"));
        assertTrue(malice.getTargetServerIdList().contains("n7"));
    }

    @Test
    public void testMultipleAttackTypes() {
        // Test case: time; dark(n6) with byzantine node n1
        TransactionSet set = new TransactionSet(1);
        Set<String> byzantineNodes = new HashSet<>();
        byzantineNodes.add("n1");
        set.setByzantineNodes(byzantineNodes);
        set.setAttackDescription("[time; dark(n6)]");

        List<MessageServiceOuterClass.Malice> maliceMessages = set.getMaliceMessages();

        // Should have 2 Malice objects (one per attack type)
        assertEquals(2, maliceMessages.size());

        // First attack: time (no targets)
        MessageServiceOuterClass.Malice timeAttack = maliceMessages.get(0);
        assertEquals("time", timeAttack.getMaliceType());
        assertEquals(1, timeAttack.getMaliciousServerIdCount());
        assertTrue(timeAttack.getMaliciousServerIdList().contains("n1"));
        assertEquals(0, timeAttack.getTargetServerIdCount());

        // Second attack: dark(n6)
        MessageServiceOuterClass.Malice darkAttack = maliceMessages.get(1);
        assertEquals("dark", darkAttack.getMaliceType());
        assertEquals(1, darkAttack.getMaliciousServerIdCount());
        assertTrue(darkAttack.getMaliciousServerIdList().contains("n1"));
        assertEquals(1, darkAttack.getTargetServerIdCount());
        assertTrue(darkAttack.getTargetServerIdList().contains("n6"));
    }

    @Test
    public void testMultipleByzantineNodes() {
        // Test case: dark(n1, n2) with byzantine node n7
        TransactionSet set = new TransactionSet(1);
        Set<String> byzantineNodes = new HashSet<>();
        byzantineNodes.add("n7");
        set.setByzantineNodes(byzantineNodes);
        set.setAttackDescription("[dark(n1, n2)]");

        List<MessageServiceOuterClass.Malice> maliceMessages = set.getMaliceMessages();

        // Should have 1 Malice object
        assertEquals(1, maliceMessages.size());

        MessageServiceOuterClass.Malice malice = maliceMessages.get(0);
        assertEquals("dark", malice.getMaliceType());

        // Should have 1 byzantine node
        assertEquals(1, malice.getMaliciousServerIdCount());
        assertTrue(malice.getMaliciousServerIdList().contains("n7"));

        // Should have 2 targets
        assertEquals(2, malice.getTargetServerIdCount());
        assertTrue(malice.getTargetServerIdList().contains("n1"));
        assertTrue(malice.getTargetServerIdList().contains("n2"));
    }

    @Test
    public void testComplexAttackScenario() {
        // Test case: time; dark(n6); equivocation(n7) with byzantine node n1
        TransactionSet set = new TransactionSet(1);
        Set<String> byzantineNodes = new HashSet<>();
        byzantineNodes.add("n1");
        set.setByzantineNodes(byzantineNodes);
        set.setAttackDescription("[time; dark(n6); equivocation(n7)]");

        List<MessageServiceOuterClass.Malice> maliceMessages = set.getMaliceMessages();

        // Should have 3 Malice objects (one per attack type)
        assertEquals(3, maliceMessages.size());

        // Verify each attack type has correct byzantine nodes
        for (MessageServiceOuterClass.Malice malice : maliceMessages) {
            assertEquals(1, malice.getMaliciousServerIdCount());
            assertTrue(malice.getMaliciousServerIdList().contains("n1"));
        }
    }

    @Test
    public void testMultipleByzantineNodesInSet() {
        // Test case: crash with byzantine nodes [n1, n2]
        TransactionSet set = new TransactionSet(1);
        Set<String> byzantineNodes = new HashSet<>();
        byzantineNodes.add("n1");
        byzantineNodes.add("n2");
        set.setByzantineNodes(byzantineNodes);
        set.setAttackDescription("[crash]");

        List<MessageServiceOuterClass.Malice> maliceMessages = set.getMaliceMessages();

        // Should have 1 Malice object
        assertEquals(1, maliceMessages.size());

        MessageServiceOuterClass.Malice malice = maliceMessages.get(0);
        assertEquals("crash", malice.getMaliceType());

        // Should have 2 byzantine nodes
        assertEquals(2, malice.getMaliciousServerIdCount());
        assertTrue(malice.getMaliciousServerIdList().contains("n1"));
        assertTrue(malice.getMaliciousServerIdList().contains("n2"));

        // No targets for crash attack
        assertEquals(0, malice.getTargetServerIdCount());
    }

    @Test
    public void testEmptyAttackDescription() {
        TransactionSet set = new TransactionSet(1);
        set.setAttackDescription("[]");

        List<MessageServiceOuterClass.Malice> maliceMessages = set.getMaliceMessages();

        // Should have 0 Malice objects
        assertEquals(0, maliceMessages.size());
    }
}

