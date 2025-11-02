package org.example;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.example.statemachine.BalanceRequestOp;
import org.example.statemachine.StateMachineOperation;
import org.example.statemachine.TransferOp;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TransactionSetLoader {

    // Regex to extract (Item1, Item2, Value) from the Transfer string: (A, B, 1)
    private static final Pattern TRANSFER_PATTERN = Pattern.compile("\\(([A-Z]+),\\s*([A-Z]+),\\s*(\\d+)\\)");
    // Regex to extract (Item) from the BalanceRequest string: (A)
    private static final Pattern BALANCE_REQUEST_PATTERN = Pattern.compile("\\(([A-Z]+)\\)");
    private static final String LEADER_FAILURE_MARKER = "LF";

    /**
     * Load transaction sets from CSV file using openCSV
     * CSV format: Set Number, Transactions, Live Nodes, Byzantine Nodes, Attack
     * - Column 0: Set Number (only present on first row of each set)
     * - Column 1: StateMachineOperation - TransferOp (A, B, 1) or BalanceRequestOp (A)
     * - Column 2: Live Nodes - List of active node IDs [n1, n2, n3, ...]
     * - Column 3: Byzantine Nodes - List of byzantine node IDs [n2] or []
     * - Column 4: Attack - Attack description string or []
     */
    public static HashMap<Integer, TransactionSet> loadTransactionSets(String filePath) {

        HashMap<Integer, TransactionSet> transactionSets = new HashMap<>();

        try (CSVReader reader = new CSVReaderBuilder(new FileReader(filePath)).withSkipLines(1).build()) {
            String[] nextLine;
            Integer currentSetNumber = null;

            while ((nextLine = reader.readNext()) != null) {
                // Column 0: Set Number (only present on first row of each set)
                if (nextLine[0] != null && !nextLine[0].trim().isEmpty()) {
                    currentSetNumber = Integer.parseInt(nextLine[0].trim());
                }

                if (currentSetNumber == null) {
                    throw new IllegalStateException("Encountered row without a set number being established");
                }

                // Get or create the TransactionSet
                TransactionSet transactionSet;
                if (transactionSets.containsKey(currentSetNumber)) {
                    transactionSet = transactionSets.get(currentSetNumber);
                } else {
                    transactionSet = new TransactionSet(currentSetNumber);
                    transactionSets.put(currentSetNumber, transactionSet);
                }

                // Column 2: Live Nodes (only present on first row of each set)
                if (nextLine.length > 2 && nextLine[2] != null && !nextLine[2].trim().isEmpty()) {
                    transactionSet.addActiveNodesList(parseNodesList(nextLine[2]));
                }

                // Column 3: Byzantine Nodes (only present on first row of each set)
                if (nextLine.length > 3 && nextLine[3] != null && !nextLine[3].trim().isEmpty()) {
                    transactionSet.setByzantineNodes(parseByzantineNodes(nextLine[3]));
                }

                // Column 4: Attack description (only present on first row of each set)
                if (nextLine.length > 4 && nextLine[4] != null && !nextLine[4].trim().isEmpty()) {
                    transactionSet.setAttackDescription(nextLine[4].trim());
                }

                // Column 1: StateMachineOperation (TransferOp or BalanceRequestOp)
                if (nextLine.length > 1 && nextLine[1] != null && !nextLine[1].trim().isEmpty()) {
                    String operationStr = nextLine[1].trim();

                    // Skip leader failure markers
                    if (!LEADER_FAILURE_MARKER.equals(operationStr)) {
                        // Parse and add the operation
                        StateMachineOperation operation = parseOperation(operationStr);
                        transactionSet.addTransactionEvent(operation);
                    }
                }
            }

            return transactionSets;

        } catch (FileNotFoundException e) {
            throw new RuntimeException("File not found: " + filePath, e);
        } catch (IOException | CsvValidationException e) {
            throw new RuntimeException(e);
        }

    }


    /**
     * Parses an operation string into a StateMachineOperation.
     * - TransferOp format: (A, B, 1) - 3 elements
     * - BalanceRequestOp format: (A) - 1 element
     */
    private static StateMachineOperation parseOperation(String operationStr) {
        // Try to match TransferOp pattern first (3 elements)
        Matcher transferMatcher = TRANSFER_PATTERN.matcher(operationStr);
        if (transferMatcher.find()) {
            String sender = transferMatcher.group(1);
            String receiver = transferMatcher.group(2);
            double amount = Double.parseDouble(transferMatcher.group(3));
            return new TransferOp(sender, receiver, amount);
        }

        // Try to match BalanceRequestOp pattern (1 element)
        Matcher balanceMatcher = BALANCE_REQUEST_PATTERN.matcher(operationStr);
        if (balanceMatcher.find()) {
            String accountId = balanceMatcher.group(1);
            return new BalanceRequestOp(accountId);
        }

        throw new IllegalArgumentException("Invalid operation format: " + operationStr);
    }

    /**
     * Parses a comma-separated list of nodes from a string like "[n1, n2, n3]"
     */
    private static List<String> parseNodesList(String nodesStr) {
        // Remove square brackets if present
        String cleanedStr = nodesStr.replaceAll("[\\[\\]]", "");

        // Handle empty list
        if (cleanedStr.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Split by comma
        String[] nodesArray = cleanedStr.split(",");

        List<String> nodesList = new ArrayList<>();
        for (String node : nodesArray) {
            String trimmedNode = node.trim();
            // Only add non-empty nodes (handles edge cases like empty brackets)
            if (!trimmedNode.isEmpty()) {
                nodesList.add(trimmedNode);
            }
        }

        return nodesList;
    }

    /**
     * Parses byzantine nodes from a string like "[n2]" or "[]"
     * Returns a Set of byzantine node IDs.
     */
    private static Set<String> parseByzantineNodes(String byzantineNodesStr) {
        // Remove square brackets if present
        String cleanedStr = byzantineNodesStr.replaceAll("[\\[\\]]", "");

        // Handle empty list
        if (cleanedStr.trim().isEmpty()) {
            return new HashSet<>();
        }

        // Split by comma
        String[] nodesArray = cleanedStr.split(",");

        Set<String> byzantineNodes = new HashSet<>();
        for (String node : nodesArray) {
            String trimmedNode = node.trim();
            // Only add non-empty nodes
            if (!trimmedNode.isEmpty()) {
                byzantineNodes.add(trimmedNode);
            }
        }

        return byzantineNodes;
    }

}
