package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.Optional;

public class PrePrepareHandler {
    private static final Logger logger = LogManager.getLogger(PrePrepareHandler.class);

    private final ServerState state;
    private final MessageAuthenticator auth;

    public PrePrepareHandler(ServerState state, MessageAuthenticator auth) {
        this.state = state;
        this.auth = auth;
    }

    private boolean verifyClientRequest(ByteString requestBytes) {
        try {
            MessageServiceOuterClass.ClientRequest clientRequest =
                    MessageServiceOuterClass.ClientRequest.parseFrom(requestBytes);
            return auth.verify(clientRequest);
        } catch (Exception e) {
            logger.error("Failed to parse ClientRequest from PrePrepare: {}", e.getMessage());
            return false;
        }
    }

    private boolean isValid(MessageServiceOuterClass.PrePrepareMessage prePrepareMessage) {
        if (state.getViewNumber() != prePrepareMessage.getViewNumber()) {
            logger.info("PrePrepare view number {} does not match current view {}",
                    prePrepareMessage.getViewNumber(), state.getViewNumber());
            return false;
        }

        ServerMessage alreadyLoggedPrePrepare = state.findPrePrepare(prePrepareMessage.getViewNumber(), prePrepareMessage.getSequenceNumber());
        if (alreadyLoggedPrePrepare != null && alreadyLoggedPrePrepare.getDigest().isPresent()) {
            logger.info("Duplicate PrePrepare detected for view {} seq {}",
                    prePrepareMessage.getViewNumber(), prePrepareMessage.getSequenceNumber());
            // do not accept if digest differs, can accept if digest is same as state takes care of de-dupe
            return alreadyLoggedPrePrepare.getDigest().get().equals(prePrepareMessage.getDigest());
        }

        if (!state.seqNumBetweenWatermarks(prePrepareMessage.getSequenceNumber())) {
            logger.info("PrePrepare seq number {} out of watermarks (low: {}, high: {})",
                    prePrepareMessage.getSequenceNumber(), state.getLowWatermark(), state.getHighWatermark());
            return false;
        }

        return true;

    }

    public void handle(MessageServiceOuterClass.PrePrepareRequest prePrepareRequest) {

        // pre prepare signature is already verified by messaging layer
        // verify the embedded client request
        if (!verifyClientRequest(prePrepareRequest.getRequest())) {
            logger.info("Invalid client request signature in PrePrepare, ignoring PrePrepare");
            return;
        }

        if (!isValid(prePrepareRequest.getPrePrepareMessage())) {
            logger.info("Invalid PrePrepare message, ignoring");
            return;
        }

        state.appendServerMessage(prePrepareRequest);

        // attempt prepare

    }




}
