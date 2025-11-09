package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.LivenessTimer;
import org.example.consensus.handlers.CheckpointHandler;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.*;
import java.util.concurrent.ExecutorService;

public class NewViewSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(NewViewSender.class);

    private final int quorumSize;
    private final CheckpointHandler checkpointHandler;
    private final PrePrepareSender prePrepareSender;

    private final LivenessTimer viewChangeTimer;

    public NewViewSender(String serverId, int quorumSize, LivenessTimer viewChangeTimer,
                            CommunicationLogger commLogger, MessageAuthenticator auth,
                         CheckpointHandler checkpointHandler, PrePrepareSender prePrepareSender, ExecutorService networkExecutor) {
        super(serverId, commLogger, auth, networkExecutor);
        this.quorumSize = quorumSize;
        this.checkpointHandler = checkpointHandler;
        this.viewChangeTimer = viewChangeTimer;
        this.prePrepareSender = prePrepareSender;
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

        for (long i = Math.max(minSeqNum, 1L); i <= maxSeqNum; i++) {

            state.setSeqNum(i);

            if (!pendingRequests.containsKey(i)) {
                logger.info("Appending null digest view {} seq {} as it is missing from pending requests", newViewNumber, i);
                MessageServiceOuterClass.PrePrepareMessage nullPrePrepare = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                        .setViewNumber(newViewNumber)
                        .setSequenceNumber(i)
                        .setDigest(nullDigest)
                        .build();
                MessageServiceOuterClass.PrePrepareMessage signedNullPrePrepare = (MessageServiceOuterClass.PrePrepareMessage) auth.sign(nullPrePrepare);

                state.appendServerMessage(signedNullPrePrepare, quorumSize);
                prePrepareMessages.add(signedNullPrePrepare);
                continue;
            }
            MessageServiceOuterClass.PrePrepareMessage prePrepare = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                    .setViewNumber(newViewNumber)
                    .setSequenceNumber(i)
                    .setDigest(pendingRequests.get(i))
                    .build();

            MessageServiceOuterClass.PrePrepareMessage signedMsg = (MessageServiceOuterClass.PrePrepareMessage) auth.sign(prePrepare);

            requestClientMessage(state, newViewNumber, signedMsg);

            prePrepareMessages.add(signedMsg);
        }
        return prePrepareMessages;
    }

    public void requestClientMessage(ServerState state, long newViewNumber, MessageServiceOuterClass.PrePrepareMessage prePrepareMessage) {
        // find the client request to attach to this pre-prepare in the new view
        long seqNum = prePrepareMessage.getSequenceNumber();
        MessageServiceOuterClass.ClientRequest clientRequest = state.findClientRequest(prePrepareMessage.getDigest());
        if (clientRequest == null) {
            logger.info("Client request for view {} seq {} not found in state", newViewNumber, seqNum);
            MessageServiceOuterClass.ClientRequestMessage clientRequestMessage = MessageServiceOuterClass.ClientRequestMessage.newBuilder()
                    .setSequenceNumber(seqNum)
                    .setRequesterId(state.getServerId())
                    .build();
            logger.info("Requesting client request for view {} seq {} from other servers to append to state", newViewNumber, seqNum);
            broadcast(clientRequestMessage, state.isPrimary(), (stub, msg) -> stub.getClientRequest((MessageServiceOuterClass.ClientRequestMessage) msg));
            // state will be updated and whenever client request is received, it will be added to this sequence number
            state.appendServerMessage(prePrepareMessage, quorumSize);
        } else {
            logger.info("Client request for view {} seq {} found in state, no need to request", newViewNumber, seqNum);
            state.appendServerMessage(prePrepareMessage, clientRequest, quorumSize);
        }
    }

    public void broadcastNewView(ServerState state) {
        long viewNumber = state.getViewNumber();

        if (!state.isPrimary()) {
            logger.warn("Not sending new view because another server is primary");
            return;
        }

        viewChangeTimer.stop();
        if (!state.getPendingOperations().isEmpty()) {
            logger.info("Restarting liveness timer for view {} due to pending operations", viewNumber);
            viewChangeTimer.start();
        }

        logger.info("Preparing to broadcast NewView message to all servers");
        String messageIndex = ServerMessage.VIEW_CHANGE + ":" + viewNumber;
        List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages = new ArrayList<>(state.getQuorumMessages(messageIndex).stream().map((msg) -> (MessageServiceOuterClass.ViewChangeMessage) msg.getMessage()).toList());

        // add own view change message if not already present
        MessageServiceOuterClass.ViewChangeMessage ownViewChange = state.findViewChange(viewNumber, state.getServerId());
        if (ownViewChange != null && !viewChangeMessages.contains(ownViewChange)) {
            viewChangeMessages.add(ownViewChange);
        }

        long minSeqNum = calculateMinSequenceNumber(viewChangeMessages);
        long maxSeqNum = calculateMaxSequenceNumber(viewChangeMessages);

        Map<Long, ByteString> pendingRequests = getPendingRequests(viewChangeMessages);
        List<MessageServiceOuterClass.PrePrepareMessage> prePrepareMessages = generateAndAppendNewPrePrepareMessages(state, viewNumber, minSeqNum, maxSeqNum, pendingRequests);

        logger.info("Generated {} PrePrepare messages for NewView message for view {} with seq num range {}-{}, now sending New View",
                prePrepareMessages.size(), viewNumber, minSeqNum, maxSeqNum);
        MessageServiceOuterClass.NewViewMessage newView = MessageServiceOuterClass.NewViewMessage.newBuilder()
                .setViewNumber(viewNumber)
                .addAllViewChangeMessages(viewChangeMessages)
                .addAllPrePrepareMessages(prePrepareMessages)
                .build();

        MessageServiceOuterClass.NewViewMessage signedNewView = (MessageServiceOuterClass.NewViewMessage) auth.sign(newView);

        if (state.getLatestStableCheckpointSeqNum() < minSeqNum) {
            logger.info("Adding stable checkpoint seq num {} from view change messages view {} to state",
                    minSeqNum, viewNumber);

            List<MessageServiceOuterClass.CheckpointMessage> checkpoints = getCheckpointsForSeqNum(viewChangeMessages, minSeqNum);

            if (checkpoints == null || checkpoints.isEmpty()) {
                logger.error("No checkpoint messages found for stable checkpoint seq num {} in view change messages for view {}, cannot add stable checkpoint",
                        minSeqNum, viewNumber);
                return;
            }

            try {
                for (MessageServiceOuterClass.CheckpointMessage checkpoint : checkpoints) {
                    checkpointHandler.handle(checkpoint);
                }
            } catch (Exception e) {
                logger.error("Failed to add stable checkpoint seq num {} from view change messages for view {}: {}",
                        minSeqNum, viewNumber, e);
                return;
            }
            return;
        }

        if (!state.appendServerMessage(signedNewView, quorumSize)) {
            logger.info("Failed to append New View message to state for view {}, likely due to duplicate check", viewNumber);
            return;
        }

        broadcast(signedNewView, state.isPrimary(), (stub, signed) -> stub.newView((MessageServiceOuterClass.NewViewMessage) signed));
        logger.info("Broadcasted NewView message for view {}", viewNumber);

        // mark view change as completed
        state.setViewChangeInProgress(false);
        state.completeNewViewBroadcast(viewNumber);

        if (minSeqNum >= maxSeqNum) {
            logger.info("Sent empty NewView message for view {} because there are no pending requests to carry over", viewNumber);
            // reset sequence number to minSeqNum again.
            state.setSeqNum(minSeqNum);

            // if primary has pending client requests, process pre-prepares for them.
            List<MessageServiceOuterClass.ClientRequest> pendingClientRequests = state.findClientRequestsNotPrePrepared();
            if (!pendingClientRequests.isEmpty()) {
                logger.info("Primary has {} pending client requests after NewView for view {}, processing them now", pendingClientRequests.size(), viewNumber);
                for (MessageServiceOuterClass.ClientRequest clientRequest : pendingClientRequests) {
                    prePrepareSender.attemptPrePrepare(clientRequest);
                }
            }
        }
    }
}
