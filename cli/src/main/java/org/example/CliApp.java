package org.example;

import org.example.config.Config;

import java.util.List;
import java.util.Scanner;

import static org.example.ServerManager.*;

public final class CliApp {

    public static void main(String[] args) {

        Config.initialize();

        // Reuse existing loader the project already has
        List<TransactionSet> sets = TransactionSetLoader.loadTransactionSets(Config.getTransactionSetsPath()).values().stream().toList();

        System.out.println("Loaded " + sets.size() + " transaction sets.");
        try (SenderDispatcher dispatcher = new SenderDispatcher()) {
            int next = 0;
            Scanner sc = new Scanner(System.in);

            while (true) {
                System.out.println();
                System.out.println("Options:");
                System.out.println(" 1 - PrintDB");
                System.out.println(" 2 - PrintLog");
                System.out.println(" 3 - PrintStatus");
                System.out.println(" 4 - PrintView");
                System.out.println(" 5 - Continue with next set");
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
                        int seq = Integer.parseInt(sc.nextLine().trim());
                        printStatus(seq);
                    }
                    case "4" -> printView();
                    case "5" -> {
                        if (next >= sets.size()) {
                            System.out.println("No more sets.");
                            break;
                        }
                        TransactionSet set = sets.get(next++);
                        System.out.printf("Scheduling set #%d%n", set.setNumber());

                        ServerManager.activateServers(set);

                        // Submit events exactly in file order
                        for (TransactionEvent ev : set.transactionEvents()) {
                            dispatcher.submit(ev);
                        }
                        System.out.println("Set scheduled; processing continues in background.");
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
            }
        }
    }
}
