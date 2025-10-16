package org.example;

import com.google.protobuf.Empty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class ServerManager {

    private static final Logger logger = LogManager.getLogger(ServerManager.class);
    private static final List<Process> processes = new ArrayList<>();
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
            System.out.println("Database for server : " + serverId);
            try {
                MessageServiceOuterClass.CLIResponse response =
                        stubManager.getBlockingStub(serverId).getDB(Empty.getDefaultInstance());
                System.out.println(response.getCliResponse());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            System.out.println();
        }
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

}
