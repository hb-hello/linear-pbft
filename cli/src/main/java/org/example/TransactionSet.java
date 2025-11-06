package org.example;

import org.example.statemachine.StateMachineOperation;
import org.example.statemachine.TransferOp;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a transaction set with operations, node configuration, and attack information.
 * Note: Uses mutable collections for transactionEvents, activeNodesList, and byzantineNodes.
 * The attackDescription is stored in a single-element array to allow mutation.
 */
public record TransactionSet(int setNumber, List<StateMachineOperation> transactionEvents,
                             List<String> activeNodesList, Set<String> byzantineNodes,
                             String[] attackDescriptionHolder) {

    // Regex patterns for parsing attack descriptions
    private static final Pattern ATTACK_WITH_TARGET_PATTERN = Pattern.compile("(\\w+)\\(([^)]+)\\)");

    public TransactionSet(int setNumber) {
        this(setNumber, new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new String[]{""});
    }

    /**
     * Parses the attack description and creates Malice protobuf objects.
     *
     * Attack format examples:
     * - "crash" -> crash attack, no target
     * - "time; dark(n6)" -> time attack (no target) and dark attack targeting n6
     * - "equivocation(n6, n7)" -> equivocation attacks targeting n6 and n7
     * - "dark(n1, n2)" -> dark attacks targeting n1 and n2
     *
     * Creates one Malice object per distinct attack type.
     * All byzantine nodes are added to the malicious_server_id repeated field.
     * All targets (if any) are added to the target_server_id repeated field.
     *
     * @return List of Malice protobuf messages (one per distinct attack type)
     */
    public List<MessageServiceOuterClass.Malice> getMaliceMessages() {
        List<MessageServiceOuterClass.Malice> maliceList = new ArrayList<>();

        String attackDesc = attackDescriptionHolder[0];

        // Handle empty or "[]" attack descriptions
        if (attackDesc == null || attackDesc.trim().isEmpty() || "[]".equals(attackDesc.trim())) {
            return maliceList;
        }

        // Remove surrounding brackets if present
        attackDesc = attackDesc.replaceAll("^\\[|\\]$", "").trim();

        if (attackDesc.isEmpty()) {
            return maliceList;
        }

        // Split by semicolon to handle multiple attack types
        String[] attacks = attackDesc.split(";");

        for (String attack : attacks) {
            attack = attack.trim();

            if (attack.isEmpty()) {
                continue;
            }

            // Try to match pattern: attackType(target1, target2, ...)
            Matcher matcher = ATTACK_WITH_TARGET_PATTERN.matcher(attack);

            MessageServiceOuterClass.Malice.Builder maliceBuilder = MessageServiceOuterClass.Malice.newBuilder();

            if (matcher.matches()) {
                // Attack with targets
                String attackType = matcher.group(1);
                String targetsStr = matcher.group(2);

                // Parse targets (comma-separated)
                String[] targets = targetsStr.split(",");

                // Set attack type
                maliceBuilder.setMaliceType(attackType);

                // Add all byzantine nodes to malicious_server_id repeated field
                maliceBuilder.addAllMaliciousServerId(byzantineNodes);

                // Add all targets to target_server_id repeated field
                for (String target : targets) {
                    maliceBuilder.addTargetServerId(target.trim());
                }

                maliceList.add(maliceBuilder.build());
            } else {
                // Attack without targets (e.g., "crash", "time", "sign")
                String attackType = attack.trim();

                // Set attack type
                maliceBuilder.setMaliceType(attackType);

                // Add all byzantine nodes to malicious_server_id repeated field
                maliceBuilder.addAllMaliciousServerId(byzantineNodes);

                // No targets for this attack type
                maliceList.add(maliceBuilder.build());
            }
        }

        return maliceList;
    }

    /**
     * Groups transfer operations by sender ID.
     * Only processes TransferOp operations, ignoring BalanceRequestOp.
     *
     * Returns a map where each key is a sender ID and the value is a list of their transfer operations.
     *
     * Example:
     *   Events: [TransferOp(C->H:3), TransferOp(E->D:1), TransferOp(C->I:2)]
     *
     *   Result: {
     *     C: [TransferOp(C->H:3), TransferOp(C->I:2)],
     *     E: [TransferOp(E->D:1)]
     *   }
     *
     * @return HashMap of sender -> List of TransferOp operations
     */
    public Map<String, List<TransferOp>> groupTransactionsBySender() {
        Map<String, List<TransferOp>> groupedBySender = new HashMap<>();

        for (StateMachineOperation operation : transactionEvents) {
            if (operation instanceof TransferOp transferOp) {
                String sender = transferOp.sender();

                // Add transfer operation to the sender's list
                groupedBySender.computeIfAbsent(sender, k -> new ArrayList<>()).add(transferOp);
            }
        }

        return groupedBySender;
    }

    /**
     * Utility method to print the grouped operations structure for debugging
     */
    public void printGroups() {
        Map<String, List<TransferOp>> groupedBySender = groupTransactionsBySender();

        System.out.println("TransactionSet #" + setNumber + " [Nodes: " + activeNodesList + "]");
        System.out.println("Total senders: " + groupedBySender.size());

        for (Map.Entry<String, List<TransferOp>> entry : groupedBySender.entrySet()) {
            String sender = entry.getKey();
            List<TransferOp> transfers = entry.getValue();
            System.out.println("\n  Sender '" + sender + "': " + transfers.size() + " transfer(s)");

            for (TransferOp transfer : transfers) {
                System.out.println("      - " + transfer);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("TransactionSet #%d [Nodes: %s]\n", setNumber, activeNodesList));
        for (int i = 0; i < transactionEvents.size(); i++) {
            sb.append(String.format("  %d. %s\n", i + 1, transactionEvents.get(i)));
        }
        return sb.toString();
    }

    // Helper methods to mutate the mutable collections within the record

    public void addTransactionEvent(StateMachineOperation event) {
        this.transactionEvents.add(event);
    }

    public void addActiveNodesList(List<String> nodes) {
        this.activeNodesList.clear();
        this.activeNodesList.addAll(nodes);
    }

    public void setByzantineNodes(Set<String> nodes) {
        this.byzantineNodes.clear();
        this.byzantineNodes.addAll(nodes);
    }

    public void setAttackDescription(String description) {
        this.attackDescriptionHolder[0] = description;
    }
}