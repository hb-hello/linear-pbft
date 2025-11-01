package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.MessageUtil;
import org.example.serverstate.ServerState;

public class PrePrepareSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(PrePrepareSender.class);

    private final ServerState state;

    public PrePrepareSender(String serverId, ServerState state,
                            CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
        this.state = state;
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
            return;
        }

        // Generate digest and assign sequence number
        byte[] digest = MessageUtil.generateDigest(clientRequest);
        long seqNum = state.nextSeq();

        // Build PrePrepare message
        MessageServiceOuterClass.PrePrepareMessage prePrepareMsg =
                MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                        .setViewNumber(state.getViewNumber())
                        .setSequenceNumber(seqNum)
                        .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                        .build();

        MessageServiceOuterClass.PrePrepareRequest request =
                MessageServiceOuterClass.PrePrepareRequest.newBuilder()
                        .setPrePrepareMessage(prePrepareMsg)
                        .setRequest(com.google.protobuf.ByteString.copyFrom(clientRequest.toByteArray()))
                        .build();

        // Sign with TSS and broadcast
        broadcastToServers(request);
    }

    // package-private for testing
    void broadcastToServers(MessageServiceOuterClass.PrePrepareRequest request) {
        logger.info("Broadcasting PrePrepare for seqNum {} in view {}",
                request.getPrePrepareMessage().getSequenceNumber(),
                request.getPrePrepareMessage().getViewNumber());
        broadcastWithTSS(request, (stub, signed) ->
                stub.prePrepare((MessageServiceOuterClass.PrePrepareRequest) signed));
    }

    // might not be needed
    private void sendPrePrepare(String targetServerId, MessageServiceOuterClass.PrePrepareRequest prePrepare) {
        logger.info("Sending PrePrepare to server {}: {}", targetServerId, prePrepare.getPrePrepareMessage().getViewNumber());
        signWithTSSAndSend(targetServerId, prePrepare, (stub, signed) -> stub.prePrepare((MessageServiceOuterClass.PrePrepareRequest) signed));
    }
}