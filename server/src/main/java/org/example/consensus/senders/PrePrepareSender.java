package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MaliceInjector;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.MessageUtil;
import org.example.serverstate.ServerState;

import java.util.concurrent.ExecutorService;

public class PrePrepareSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(PrePrepareSender.class);

    private final ServerState state;
    private final PrepareSender prepareSender;

    public PrePrepareSender(String serverId, ServerState state,
                            CommunicationLogger commLogger, MessageAuthenticator auth, PrepareSender prepareSender, ExecutorService networkExecutor) {
        super(serverId, commLogger, auth, networkExecutor);
        this.state = state;
        this.prepareSender = prepareSender;
    }

    /**
     * Check if this node can send PrePrepare messages
     */
    private boolean canSend() {
        return state.isPrimary() && !state.isFaulty() && isActive();
    }

    /**
     * Send PrePrepare message to all replicas
     */
    public void attemptPrePrepare(MessageServiceOuterClass.ClientRequest clientRequest) {
        if (!canSend()) {
            logger.info("Cannot send PrePrepare: isPrimary={}, isFaulty={}, isActive={}",
                    state.isPrimary(), state.isFaulty(), isActive());
            return;
        }

        logger.info("Preparing to send PrePrepare for client request from client {} with timestamp {}",
                clientRequest.getClientId(), clientRequest.getTimestamp());

        // Generate digest and assign sequence number
        byte[] digest = MessageUtil.generateDigest(clientRequest);
        ByteString digestByteString = ByteString.copyFrom(digest);

        long seqNum = state.nextSeq();
        String selfServerId = state.getServerId();

        // for non-equivocation attack
        for (String targetNodeId : Config.getServerIdsExcept(selfServerId)) {

            if (MaliceInjector.injectEquivocationAttack(selfServerId, targetNodeId)) {
                continue;
            }

            logger.info("Calculated next sequence number {} for PrePrepare in view {}",
                    seqNum, state.getViewNumber());

            constructAndSendPrePrepare(clientRequest, targetNodeId, seqNum, digestByteString);
        }

        //for equivocation attack
        if (MaliceInjector.isEquivocation() && !MaliceInjector.getEquivocationTargets().isEmpty()) {
            long previousSeqNum = seqNum;
            seqNum = state.nextSeq();
            for (String targetNodeId : MaliceInjector.getEquivocationTargets()) {
                logger.info("MaliceInjector equivocation attack activated for PrePrepare, by incrementing seqNum from {} to {} for target {}",
                        previousSeqNum, seqNum, targetNodeId);
                constructAndSendPrePrepare(clientRequest, targetNodeId, seqNum, digestByteString);
            }
        }

        // After broadcasting PrePrepare, send Prepare to collector (so that collector gets a quorum of prepares)
        
        prepareSender.sendPrepare(state.getViewNumber(), seqNum, digest);
    }

    private void constructAndSendPrePrepare(MessageServiceOuterClass.ClientRequest clientRequest, String targetNodeId, long seqNum, ByteString digestByteString) {
        // Build PrePrepare message
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg =
                MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                        .setViewNumber(state.getViewNumber())
                        .setSequenceNumber(seqNum)
                        .setDigest(digestByteString)
                        .build();

        MessageServiceOuterClass.PrePrepareMessage signedPrePrepareMsg = (MessageServiceOuterClass.PrePrepareMessage) auth.sign(prePrepareMsg);

        state.appendServerMessage(signedPrePrepareMsg, clientRequest, 0);

        MessageServiceOuterClass.PrePrepareRequest request = MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                .setPrePrepareMessage(signedPrePrepareMsg)
                .setRequest(clientRequest)
                .build();

        logger.info("Constructed PrePrepareRequest for seqNum {} in view {}",
                seqNum, state.getViewNumber());

        

        logger.info("Sending PrePrepare for seqNum {} in view {}",
                request.getPrePrepareMessage().getSequenceNumber(),
                request.getPrePrepareMessage().getViewNumber());

        send(targetNodeId, request, (stub, signed) ->
                stub.prePrepare((MessageServiceOuterClass.PrePrepareRequest) signed));
    }

    // might not be needed
    private void sendPrePrepare(String targetServerId, MessageServiceOuterClass.PrePrepareRequest prePrepare) {
        logger.info("Sending PrePrepare to server {}: {}", targetServerId, prePrepare.getPrePrepareMessage().getViewNumber());
        signAndSend(targetServerId, prePrepare, (stub, signed) -> stub.prePrepare((MessageServiceOuterClass.PrePrepareRequest) signed));
    }
}