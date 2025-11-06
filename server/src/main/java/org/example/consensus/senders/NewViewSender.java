package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.handlers.CheckpointHandler;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.*;

public class NewViewSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(NewViewSender.class);

    private final int quorumSize;
    private final CheckpointHandler checkpointHandler;

    public NewViewSender(String serverId, int quorumSize,
                            CommunicationLogger commLogger, MessageAuthenticator auth, CheckpointHandler checkpointHandler) {
        super(serverId, commLogger, auth);
        this.quorumSize = quorumSize;
        this.checkpointHandler = checkpointHandler;
    }

    private long calculateMinSequenceNumber(List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages) {
        long minSeqNum = Long.MAX_VALUE;
        for (MessageServiceOuterClass.ViewChangeMessage vcMsg : viewChangeMessages) {
            if (vcMsg.getLastStableSequenceNumber() < minSeqNum) {
                minSeqNum = vcMsg.getLastStableSequenceNumber();
            }
        }
        return minSeqNum;
    }

    private List<MessageServiceOuterClass.CheckpointMessage> getCheckpointsForSeqNum(List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages, long minSeqNum) {
        for (MessageServiceOuterClass.ViewChangeMessage vcMsg : viewChangeMessages) {
            if (vcMsg.getLastStableSequenceNumber() == minSeqNum) {
                return vcMsg.getCheckpointMessagesList();
            }
        }
        return null;
    }

    private long calculateMaxSequenceNumber(List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages) {
        long maxSeqNum = Long.MIN_VALUE;
        for (MessageServiceOuterClass.ViewChangeMessage vcMsg : viewChangeMessages) {
            for (MessageServiceOuterClass.PreparedCertificate pc : vcMsg.getPreparedCertificatesList()) {
                MessageServiceOuterClass.PrePrepareMessage prePrepareMessage = pc.getPrePrepareMessage();
                if (prePrepareMessage.getSequenceNumber() > maxSeqNum) {
                    maxSeqNum = prePrepareMessage.getSequenceNumber();
                }
            }
        }
        return maxSeqNum;
    }

    private Map<Long, ByteString> getPendingRequests(List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages) {
        Map<Long, ByteString> pendingRequests = new HashMap<>();

        for (MessageServiceOuterClass.ViewChangeMessage vcMsg : viewChangeMessages) {
            for (MessageServiceOuterClass.PreparedCertificate pc : vcMsg.getPreparedCertificatesList()) {
                pendingRequests.put(pc.getPrePrepareMessage().getSequenceNumber(), pc.getPrePrepareMessage().getDigest());
            }
        }

        return pendingRequests;
    }

    private List<MessageServiceOuterClass.PrePrepareMessage> generateAndAppendNewPrePrepareMessages(ServerState state, long newViewNumber, long minSeqNum, long maxSeqNum, Map<Long, ByteString> pendingRequests) {
        List<MessageServiceOuterClass.PrePrepareMessage> prePrepareMessages = new ArrayList<>();
        ByteString nullDigest = ByteString.copyFrom(new byte[32]);

        for (long i = minSeqNum; i <= maxSeqNum; i++) {
            if (!pendingRequests.containsKey(i)) {
                logger.info("Appending null digest view {} seq {} as it is missing from pending requests", newViewNumber, i);
                MessageServiceOuterClass.PrePrepareMessage nullPrePrepare = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                        .setViewNumber(newViewNumber)
                        .setSequenceNumber(i)
                        .setDigest(nullDigest)
                        .build();
                prePrepareMessages.add(nullPrePrepare);
                state.appendServerMessage(nullPrePrepare, quorumSize);
                continue;
            }
            MessageServiceOuterClass.PrePrepareMessage prePrepare = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                    .setViewNumber(newViewNumber)
                    .setSequenceNumber(i)
                    .setDigest(pendingRequests.get(i))
                    .build();
            prePrepareMessages.add(prePrepare);

            MessageServiceOuterClass.ClientRequest clientRequest = state.findClientRequest(pendingRequests.get(i));
            if (clientRequest == null) {
                logger.info("Client request for view {} seq {} not found in state", newViewNumber, i);
                MessageServiceOuterClass.SequenceNumber seqNum = MessageServiceOuterClass.SequenceNumber.newBuilder()
                        .setSequenceNumber(i)
                        .build();
                logger.info("Requesting client request for view {} seq {} from other servers to append to state", newViewNumber, i);
                broadcast(seqNum, (stub, msg) -> stub.getClientRequest((MessageServiceOuterClass.SequenceNumber) msg));
            }
        }
        return prePrepareMessages;
    }

    public void broadcastNewView(ServerState state, long newViewNumber) {
        if (!state.isPrimary()) {
            logger.warn("Not sending new view because another server is primary");
            return;
        }

        if (newViewNumber < state.getViewNumber()) {
            logger.info("Not sending NewView message for view {} because current view is {}",
                    newViewNumber, state.getViewNumber());
            return;
        }

        logger.info("Preparing to broadcast NewView message to all servers");
        String messageIndex = ServerMessage.VIEW_CHANGE + ":" + newViewNumber;
        List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages = new ArrayList<>(state.getQuorumMessages(messageIndex).stream().map((msg) -> (MessageServiceOuterClass.ViewChangeMessage) msg.getMessage()).toList());

        // add own view change message if not already present
        MessageServiceOuterClass.ViewChangeMessage ownViewChange = state.findViewChange(newViewNumber, state.getServerId());
        if (ownViewChange != null && !viewChangeMessages.contains(ownViewChange)) {
            viewChangeMessages.add(ownViewChange);
        }

        long minSeqNum = calculateMinSequenceNumber(viewChangeMessages);
        long maxSeqNum = calculateMaxSequenceNumber(viewChangeMessages);

        if (minSeqNum >= maxSeqNum) {
            logger.info("Not sending NewView message for view {} because there are no pending requests to carry over", newViewNumber);
            return;
        }

        Map<Long, ByteString> pendingRequests = getPendingRequests(viewChangeMessages);
        List<MessageServiceOuterClass.PrePrepareMessage> prePrepareMessages = generateAndAppendNewPrePrepareMessages(state, newViewNumber, minSeqNum, maxSeqNum, pendingRequests);

        logger.info("Generated {} PrePrepare messages for NewView message for view {} with seq num range {}-{}, now sending New View",
                prePrepareMessages.size(), newViewNumber, minSeqNum, maxSeqNum);
        MessageServiceOuterClass.NewViewMessage newView = MessageServiceOuterClass.NewViewMessage.newBuilder()
                .setViewNumber(newViewNumber)
                .addAllPrePrepareMessages(prePrepareMessages)
                .build();

        MessageServiceOuterClass.NewViewMessage signedNewView = (MessageServiceOuterClass.NewViewMessage) auth.sign(newView);

        if (state.getLatestStableCheckpointSeqNum() < minSeqNum) {
            logger.info("Adding stable checkpoint seq num {} from view change messages view {} to state",
                    minSeqNum, newViewNumber);

            List<MessageServiceOuterClass.CheckpointMessage> checkpoints = getCheckpointsForSeqNum(viewChangeMessages, minSeqNum);

            if (checkpoints == null || checkpoints.isEmpty()) {
                logger.error("No checkpoint messages found for stable checkpoint seq num {} in view change messages for view {}, cannot add stable checkpoint",
                        minSeqNum, newViewNumber);
                return;
            }

            try {
                for (MessageServiceOuterClass.CheckpointMessage checkpoint : checkpoints) {
                    checkpointHandler.handle(checkpoint);
                }
            } catch (Exception e) {
                logger.error("Failed to add stable checkpoint seq num {} from view change messages for view {}: {}",
                        minSeqNum, newViewNumber, e);
                return;
            }
            return;
        }

        if (!state.appendServerMessage(signedNewView, quorumSize)) {
            logger.info("Failed to append New View message to state for view {}, likely due to duplicate check", newViewNumber);
            return;
        }

        broadcast(signedNewView, (stub, signed) -> stub.newView((MessageServiceOuterClass.NewViewMessage) signed));
        logger.info("Broadcasted NewView message for view {}", newViewNumber);
    }
}
