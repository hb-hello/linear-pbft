package org.example;

import org.example.config.Config;
import org.example.statemachine.StateMachineOperation;

import java.util.List;
import java.util.Scanner;

import static org.example.ServerManager.*;

public final class CliApp {

    @SuppressWarnings("unused")
    static void main(String[] args) {

        Config.initialize();

        // Reuse existing loader the project already has
        List<TransactionSet> sets = TransactionSetLoader.loadTransactionSets(Config.getTransactionSetsPath()).values().stream().toList();

        System.out.println("Loaded " + sets.size() + " transaction sets.");
        try (SenderDispatcher dispatcher = new SenderDispatcher()) {
            // Ensure servers are reset and activated before warming up client gRPC connections.
            try {
                Thread.sleep(500); // brief pause to ensure any prior operations have settled
                for (String serverId : Config.getServerIds()) {
                    try {
                        ServerManager.setServerNodeActiveFlag(serverId, true);
                    } catch (Exception e) {
                        System.out.println("Warning: failed to activate server " + serverId + " before warmup: " + e.getMessage());
                    }
                }
                System.out.println("Resetting all servers before client warmup...");
                ServerManager.resetAllServers();
                // Activate all known servers so clients can warm connections to all of them
                System.out.println("Server reset/activation complete.");
            } catch (Exception e) {
                System.out.println("Warning: failed to reset/activate servers before warmup: " + e.getMessage());
            }

            // Warm up gRPC connections once for all clients (A through J) before running any sets.
            try {
                boolean warmed = dispatcher.warmUpAllClients();
                if (warmed) System.out.println("Client warmup succeeded (at least one client reached quorum).");
                else System.out.println("Client warmup did not reach quorum for any client (continuing anyway).");
            } catch (Exception e) {
                System.out.println("Client warmup encountered an error (continuing anyway): " + e.getMessage());
            }

            int next = 0;
            Scanner sc = new Scanner(System.in);

            while (true) {
                System.out.println();
                System.out.println("Options:");
                System.out.println(" 1 - PrintDB");
                System.out.println(" 2 - PrintLog");
                System.out.println(" 3 - PrintStatus");
                System.out.println(" 4 - PrintView");
                System.out.println(" 5 - Continue with next set (#" + (next + 1) + ")");
                System.out.println(" 6 - DEBUG: PrintOperationLog");
                System.out.println(" 7 - DEBUG: Pause/Resume client (pause a client to inspect logs/db)");
                System.out.println(" 8 - DEBUG: Choose next set number");
                System.out.println(" 0 - Exit");
                System.out.print("Choice: ");
                String choice = sc.nextLine().trim();

                switch (choice) {
                    case "1" -> printDB();
                    case "2" -> {
                        System.out.print("Enter server id: ");
                        String serverId = sc.nextLine().trim();
                        printLog(serverId);
                    }
                    case "3" -> {
                        System.out.print("Enter sequence number: ");
                        String in = sc.nextLine().trim();
                        try {
                            int seq = Integer.parseInt(in);
                            if (seq < 0) {
                                System.out.println("Invalid sequence number. Must be >= 0.");
                            } else {
                                printStatus(seq);
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid number format.");
                        }
                    }
                    case "4" -> printView();
                    case "5" -> {
                        if (next >= sets.size()) {
                            System.out.println("No more sets.");
                            break;
                        }

                        // Cancel previous dispatch and reset for new set
                        if (next > 0) {
                            System.out.println("Cancelling previous dispatch...");
                            dispatcher.reset();
                            System.out.println("Previous dispatch cancelled and reset.");
                        }

                        // Start the set at index 'next' and advance
                        startSet(next, sets, dispatcher);
                        next++;
                    }
                    case "6" -> {
                        System.out.print("Enter server id: ");
                        String serverId = sc.nextLine().trim();
                        if (serverId.isEmpty()) {
                            System.out.println("Server id cannot be empty.");
                        } else if (!Config.getServerIds().contains(serverId)) {
                            System.out.println("Invalid server id. Valid ids: " + Config.getServerIds());
                        } else {
                            printOperationLog(serverId);
                        }
                    }
                    case "7" -> {
                        // Toggle: if any client is paused then resume all, otherwise pause all
                        boolean anyPaused = false;
                        for (char c = 'A'; c <= 'J'; c++) {
                            if (dispatcher.isClientPaused(String.valueOf(c))) { anyPaused = true; break; }
                        }
                        if (anyPaused) {
                            dispatcher.resumeAll();
                            System.out.println("Resumed all clients.");
                        } else {
                            dispatcher.pauseAll();
                            System.out.println("Paused all clients.");
                        }
                    }
                    case "8" -> {
                        // DEBUG: allow operator to set the next transaction set index (1-based input)
                        if (sets.isEmpty()) {
                            System.out.println("No transaction sets loaded.");
                            break;
                        }
                        System.out.print("Enter next set number (1-" + sets.size() + "): ");
                        String in = sc.nextLine().trim();
                        try {
                            int chosen = Integer.parseInt(in);
                            if (chosen < 1 || chosen > sets.size()) {
                                System.out.println("Invalid set number. Must be between 1 and " + sets.size() + ".");
                            } else {
                                next = chosen - 1;
                                System.out.println("Next set number set to #" + chosen + ".");
                                // Immediately start the chosen set
                                // Cancel previous dispatch and reset for new set
                                if (next > 0) {
                                    System.out.println("Cancelling previous dispatch...");
                                    dispatcher.reset();
                                    System.out.println("Previous dispatch cancelled and reset.");
                                }
                                startSet(next, sets, dispatcher);
                                next++;
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid number format.");
                        }
                    }
                    case "0" -> {
                        System.out.println("Exiting...");
                        return;
                    }
                    default -> System.out.println("Unknown choice.");
                }

                // Optional local progress peek
                SenderDispatcher.Status s = dispatcher.snapshotStatus();
                System.out.printf("Progress: submitted=%d completed=%d outstanding=%d%n",
                        s.submitted(), s.completed(), s.outstanding());

                // Print aggregated duration stats across all clients (avg/min/max in ms)
                try {
                    SenderDispatcher.AggregatedDurations agg = dispatcher.aggregateDurationStats();
                    SenderDispatcher.DurationSummary ro = agg.readOnly();
                    SenderDispatcher.DurationSummary rw = agg.readWrite();
                    System.out.printf("Aggregated request duration statistics (with server timeout being %dms and client timeout being %dms):%n",
                            Config.getServerTimeoutMillis(), Config.getClientTimeoutMillis());
                    System.out.printf("Durations (ms) READ-ONLY: avg=%.2f min=%d max=%d count=%d%n",
                            ro.avgMillis(), ro.minMillis(), ro.maxMillis(), ro.count());
                    System.out.printf("Durations (ms) READ-WRITE: avg=%.2f min=%d max=%d count=%d%n",
                            rw.avgMillis(), rw.minMillis(), rw.maxMillis(), rw.count());
                } catch (Exception e) {
                    System.out.println("Unable to aggregate durations: " + e.getMessage());
                }
            }
        }
    }

    // Helper to start a transaction set by zero-based index. Submits events to the provided dispatcher
    private static void startSet(int index, List<TransactionSet> sets, SenderDispatcher dispatcher) {
        TransactionSet set = sets.get(index);

        // Display detailed set information
        System.out.printf("Scheduling set #%d%n", set.setNumber());
        System.out.printf("  Operations: %d%n", set.transactionEvents().size());
        System.out.printf("  Active servers: %s%n", set.activeNodesList());
        System.out.printf("  Byzantine nodes: %s%n",
                set.byzantineNodes().isEmpty() ? "[]" : set.byzantineNodes());
        System.out.printf("  Attack: %s%n",
                set.attackDescriptionHolder()[0]);

        List<MessageServiceOuterClass.Malice> maliceMessages = set.getMaliceMessages();

        ServerManager.resetAllServers();
        ServerManager.activateServers(set);

        for (MessageServiceOuterClass.Malice malice : maliceMessages) {
            ServerManager.setMalice(malice);
        }

        // Clear per-client duration stats so metrics reported for this set start from zero
        dispatcher.resetDurationTrackers();

        // Submit events exactly in file order
        for (StateMachineOperation ev : set.transactionEvents()) {
            dispatcher.submit(ev);
        }
        System.out.println("Set scheduled; processing continues in background.");
    }
}
