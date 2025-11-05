package org.example;

import com.google.protobuf.Empty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.messaging.StubManager;

public class ServerManager {

    private static final Logger logger = LogManager.getLogger(ServerManager.class);
    private static final StubManager stubManager = new StubManager();

    public static void setServerNodeActiveFlag(String serverId, boolean activeFlag) {
        try {
            MessageServiceOuterClass.Acknowledgement ack = stubManager.getBlockingStub(serverId)
                    .setActiveFlag(MessageServiceOuterClass.ActiveFlag.newBuilder().setActiveFlag(activeFlag).build());
            if (!ack.getStatus()) {
                logger.error("Server {} not activated", serverId);
                throw new RuntimeException("Server {} not activated");
            } else logger.info("Server {} {}", serverId, activeFlag ? "activated" : "deactivated");
        } catch (RuntimeException e) {
            logger.error("Error when activating server {}.", serverId);
            throw new RuntimeException(e);
        }
    }

    public static void activateServers(TransactionSet transactionSet) {
//        Deactivate all servers
        for (String serverId : Config.getServers().keySet()) {
            setServerNodeActiveFlag(serverId, false);
        }

//        Activate required servers based on transaction set
        for (String serverIdToActivate : transactionSet.activeNodesList()) {
            setServerNodeActiveFlag(serverIdToActivate, true);
        }
    }

    public static void resetServer(String serverId) {
        try {
            MessageServiceOuterClass.Acknowledgement ack = stubManager.getBlockingStub(serverId)
                    .reset(Empty.getDefaultInstance());
            if (!ack.getStatus()) {
                logger.error("Server {} not reset", serverId);
                throw new RuntimeException("Server {} not reset");
            } else logger.info("Server {} reset", serverId);
        } catch (RuntimeException e) {
            logger.error("Error when resetting server {}.", serverId);
            throw new RuntimeException(e);
        }
    }

    public static void resetAllServers() {
        for (String serverId : Config.getServers().keySet()) {
            resetServer(serverId);
        }
    }

    // ================= Helper methods for CLI commands =================

    public static void printLog(String serverId) {
        try {
            MessageServiceOuterClass.CLIResponse response =
                    stubManager.getBlockingStub(serverId).getLog(Empty.getDefaultInstance());
            System.out.println(response.getCliResponse());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void printDB() {
        for (String serverId : Config.getServerIds()) {
//            Request DB from server and print as a table row
            try {
                MessageServiceOuterClass.CLIResponse response =
                        stubManager.getBlockingStub(serverId).getDB(Empty.getDefaultInstance());
                printDBAsTable(serverId, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Print the provided server's CLIResponse in a two-column ASCII table.
     * First column: Server ID
     * Second column: Response (may wrap if long or contain newlines)
     */
    public static void printDBAsTable(String serverId, MessageServiceOuterClass.CLIResponse response) {
        String col1 = "Server ID";
        String col2 = "Response";
        String responseStr = response == null ? "" : response.getCliResponse();

        int width1 = Math.max(col1.length(), serverId.length());
        // Determine the longest line in the response (cap to avoid extremely wide tables)
        int maxLineLen = 0;
        String[] respLines = responseStr.split("\\r?\\n");
        for (String line : respLines) {
            if (line.length() > maxLineLen) maxLineLen = line.length();
        }
        int cap = 120; // cap the response column width to 120 characters
        int width2 = Math.max(col2.length(), Math.min(maxLineLen, cap));

        // Print header
        printRowBorder(width1, width2);
        System.out.printf("| %s | %s |%n", padRight(col1, width1), padRight(col2, width2));
        printRowBorder(width1, width2);

        // Print response content, wrapping lines longer than width2
        boolean firstRow = true;
        for (String line : respLines) {
            if (line.isEmpty()) {
                System.out.printf("| %s | %s |%n", padRight(firstRow ? serverId : "", width1), padRight("", width2));
                firstRow = false;
                continue;
            }
            int start = 0;
            while (start < line.length()) {
                int end = Math.min(start + width2, line.length());
                String chunk = line.substring(start, end);
                System.out.printf("| %s | %s |%n", padRight(firstRow ? serverId : "", width1), padRight(chunk, width2));
                start = end;
                firstRow = false;
            }
        }

        // If response was empty, still print a row with the server id
        if (respLines.length == 0) {
            System.out.printf("| %s | %s |%n", padRight(serverId, width1), padRight("", width2));
        }

        printRowBorder(width1, width2);
        System.out.println();
    }

    private static void printRowBorder(int w1, int w2) {
        System.out.print("+");
        for (int i = 0; i < w1 + 2; i++) System.out.print("-");
        System.out.print("+");
        for (int i = 0; i < w2 + 2; i++) System.out.print("-");
        System.out.println("+");
    }

    private static String padRight(String s, int n) {
        if (s == null) s = "";
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    public static void printStatus(int sequenceNumber) {
        for (String serverId : Config.getServerIds()) {
            System.out.print("Status for sequence number : " + sequenceNumber +
                    " at server : " + serverId + " is ");
            try {
                MessageServiceOuterClass.SequenceNumber seqNumMessage =
                        MessageServiceOuterClass.SequenceNumber.newBuilder()
                                .setSequenceNumber(sequenceNumber)
                                .build();
                MessageServiceOuterClass.CLIResponse response =
                        stubManager.getBlockingStub(serverId).getStatus(seqNumMessage);
                System.out.print(response.getCliResponse() + "\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            System.out.println();
        }
    }

    public static void printView() {
        for (String serverId : Config.getServerIds()) {
            try {
                MessageServiceOuterClass.CLIResponse response =
                        stubManager.getBlockingStub(serverId).getNewViews(Empty.getDefaultInstance());
                System.out.print(response.getCliResponse() + "\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            System.out.println();
        }
    }

    public static void printOperationLog(String serverId) {
        try {
            MessageServiceOuterClass.CLIResponse response =
                    stubManager.getBlockingStub(serverId).getOperationLog(Empty.getDefaultInstance());
            System.out.println("Operation Log for server : " + serverId);
            System.out.println(response.getCliResponse());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
