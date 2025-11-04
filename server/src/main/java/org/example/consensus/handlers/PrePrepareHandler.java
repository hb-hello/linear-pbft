package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.PrepareSender;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.MessageUtil;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.Optional;

public class PrePrepareHandler {
    private static final Logger logger = LogManager.getLogger(PrePrepareHandler.class);

    private final ServerState state;
    private final MessageAuthenticator auth;
    private final PrepareSender prepareSender;

    public PrePrepareHandler(ServerState state, MessageAuthenticator auth, PrepareSender prepareSender) {
        this.state = state;
        this.auth = auth;
        this.prepareSender = prepareSender;
    }

    private boolean verifyClientRequest(MessageServiceOuterClass.ClientRequest request) {
        try {
            return auth.verify(request);
        } catch (Exception e) {
            logger.error("Failed to parse ClientRequest from PrePrepare: {}", e.getMessage());
            return false;
        }
    }

    private boolean isValid(MessageServiceOuterClass.PrePrepareMessage prePrepareMessage) {
        try {
            state.ensureInView(prePrepareMessage.getViewNumber());
            ServerMessage alreadyLoggedPrePrepare = state.findPrePrepare(prePrepareMessage.getViewNumber(), prePrepareMessage.getSequenceNumber());
            if (alreadyLoggedPrePrepare != null && alreadyLoggedPrePrepare.getDigest().isPresent()) {
                logger.info("Duplicate PrePrepare detected for view {} seq {}",
                        prePrepareMessage.getViewNumber(), prePrepareMessage.getSequenceNumber());
                // do not accept if digest differs, can accept if digest is same as state, this takes care of de-dupe
                return alreadyLoggedPrePrepare.getDigest().get().equals(prePrepareMessage.getDigest());
            }
            state.ensureInWatermarks(prePrepareMessage.getSequenceNumber());
            return true;
        } catch (IllegalStateException e) {
            return false;
        }

    }

    public void handle(MessageServiceOuterClass.PrePrepareRequest prePrepareRequest) {

        // pre prepare signature is already verified by messaging layer
        // verify the embedded client request
        if (!verifyClientRequest(prePrepareRequest.getRequest())) {
            logger.info("Invalid client request signature in PrePrepare, ignoring PrePrepare");
            return;
        }

        MessageServiceOuterClass.PrePrepareMessage prePrepareMessage = prePrepareRequest.getPrePrepareMessage();

        if (!isValid(prePrepareMessage)) {
            logger.info("Invalid PrePrepare message, ignoring");
            return;
        }

        if (!MessageUtil.verifyDigest(prePrepareRequest.getRequest(), prePrepareMessage.getDigest().toByteArray())) {
            logger.info("PrePrepare message digest does not match client request, ignoring PrePrepare for view {} seq {}",
                    prePrepareMessage.getViewNumber(), prePrepareMessage.getSequenceNumber());
            return;
        }

        logger.info("Digest received in PrePrepare  for view {} seq {} is {}",
                prePrepareMessage.getViewNumber(),
                prePrepareMessage.getSequenceNumber(),
                prePrepareMessage.getDigest());

        if (!state.appendServerMessage(prePrepareMessage)) {
            logger.info("Duplicate PrePrepare message detected in state, ignoring");
            return;
        }

        state.appendServerMessage(prePrepareRequest.getRequest());

        prepareSender.sendPrepare(state.getViewNumber(), prePrepareMessage.getSequenceNumber(),
                prePrepareMessage.getDigest().toByteArray());
    }


}
