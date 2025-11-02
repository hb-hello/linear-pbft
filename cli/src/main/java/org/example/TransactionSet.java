package org.example;

import org.example.statemachine.StateMachineOperation;
import org.example.statemachine.TransferOp;

import java.util.*;

/**
 * Represents a transaction set with operations, node configuration, and attack information.
 * Note: Uses mutable collections for transactionEvents, activeNodesList, and byzantineNodes.
 * The attackDescription is stored in a single-element array to allow mutation.
 */
public record TransactionSet(int setNumber, List<StateMachineOperation> transactionEvents,
                             List<String> activeNodesList, Set<String> byzantineNodes,
                             String[] attackDescriptionHolder) {

    public TransactionSet(int setNumber) {
        this(setNumber, new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new String[]{""});
    }

    public void addTransactionEvent(StateMachineOperation event) {
        this.transactionEvents.add(event);
    }

    public void addActiveNodesList(List<String> nodes) {
        this.activeNodesList.addAll(nodes);
    }

    public void setByzantineNodes(Set<String> nodes) {
        this.byzantineNodes.clear();
        this.byzantineNodes.addAll(nodes);
    }

    public void setAttackDescription(String description) {
        this.attackDescriptionHolder[0] = description;
    }

    public String getAttackDescription() {
        return this.attackDescriptionHolder[0];
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
}