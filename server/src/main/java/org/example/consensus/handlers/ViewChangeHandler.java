package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.ViewChangeSender;
import org.example.crypto.MessageAuthenticator;
import org.example.serverstate.ServerState;

import java.util.List;

public class ViewChangeHandler {
    private static final Logger logger = LogManager.getLogger(ViewChangeHandler.class);

    private final ServerState state;
    private final MessageAuthenticator auth;
    private final int quorumSize;
    private final ViewChangeSender viewChangeSender;

    public ViewChangeHandler(ServerState state, MessageAuthenticator auth, int quorumSize, ViewChangeSender viewChangeSender) {
        this.quorumSize = quorumSize;
        this.state = state;
        this.auth = auth;
        this.viewChangeSender = viewChangeSender;
    }

    private boolean isValid(List<MessageServiceOuterClass.CheckpointMessage> checkpointMessages, long viewNumber, long lastStableSeqNum) {
        for (MessageServiceOuterClass.CheckpointMessage checkpointMessage : checkpointMessages) {
            if (checkpointMessage.getViewNumber() != viewNumber - 1) {
                logger.warn("Checkpoint message view number {} does not match expected view number {}",
                        checkpointMessage.getViewNumber(), viewNumber - 1);
                return false;
            }

            if (checkpointMessage.getSequenceNumber() != lastStableSeqNum) {
                logger.warn("Checkpoint message sequence number {} is less than last stable sequence number {}",
                        checkpointMessage.getSequenceNumber(), lastStableSeqNum);
                return false;
            }

            if (!auth.verify(checkpointMessage)) {
                logger.warn("Invalid signature for Checkpoint message in view {}", checkpointMessage.getViewNumber());
                return false;
            }
        }
        return true;
    }

    public boolean isValid(List<MessageServiceOuterClass.PreparedCertificate> preparedCertificates, long viewNumber) {

        long previousView = viewNumber - 1;

        for (MessageServiceOuterClass.PreparedCertificate preparedCertificate : preparedCertificates) {

            if (!preparedCertificate.hasPrePrepareMessage() || !preparedCertificate.hasPrepareMessage()) {
                logger.warn("PrePrepare message or Prepare message in PreparedCertificate for view {} is missing", previousView);
                return false;
            }

            MessageServiceOuterClass.PrePrepareMessage prePrepareMessage = preparedCertificate.getPrePrepareMessage();

            long seqNum = prePrepareMessage.getSequenceNumber();

            if (!auth.verify(prePrepareMessage)) {
                logger.warn("Invalid signature for PrePrepare message in view {} seq {}", prePrepareMessage.getViewNumber(), seqNum);
                return false;
            }

            if (prePrepareMessage.getViewNumber() != previousView) {
                logger.warn("PreparedCertificate PrePrepare message view number {} does not match expected view number {}",
                        prePrepareMessage.getViewNumber(), previousView);
                return false;
            }

            MessageServiceOuterClass.PrepareMessage prepareMessage = preparedCertificate.getPrepareMessage();

            if (!auth.verify(prepareMessage)) {
                logger.warn("Invalid signature for Prepare message in view {} seq {}", prepareMessage.getViewNumber(), seqNum);
                return false;
            }

            if (prepareMessage.getViewNumber() != previousView || prepareMessage.getSequenceNumber() != seqNum) {
                logger.warn("PreparedCertificate Prepare message view number {} or sequence number {} does not match expected view number {} and sequence number {}",
                        prepareMessage.getViewNumber(), prepareMessage.getSequenceNumber(), previousView, seqNum);
                return false;
            }
        }
        return true;
    }

    public void handle(MessageServiceOuterClass.ViewChangeMessage viewChangeMessage) {
        long viewNumber = viewChangeMessage.getViewNumber();
        long lastStableSeqNum = state.getLatestStableCheckpointSeqNum();

        if (!isValid(viewChangeMessage.getCheckpointMessagesList(), viewNumber, lastStableSeqNum)) {
            logger.info("Invalid Checkpoint messages in ViewChangeMessage for view {}, ignoring", viewNumber);
            return;
        }

        if (!isValid(viewChangeMessage.getPreparedCertificatesList(), viewNumber)) {
            logger.info("Invalid PreparedCertificates in ViewChangeMessage for view {}, ignoring", viewNumber);
            return;
        }

        if (!state.appendServerMessage(viewChangeMessage, quorumSize)) {
            logger.info("Failed to append ViewChangeMessage to state for view {}, likely due to duplicate view change",
                    viewNumber);
            return;
        }

        if (!state.checkMessageQuorum(viewChangeMessage)) {
            logger.info("ViewChangeMessage for view {} has not reached quorum yet", viewNumber);
            return;
        }

        if (!state.isViewChangeInProgress()) {
            logger.info("As view change in progress is false, setting it to true and broadcasting ViewChange message for view {}",
                    viewNumber);
            viewChangeSender.broadcastViewChange(state);
        }

        if (state.isPrimary()) {
            logger.info("This server is the new primary for view {}, preparing NewView message",
                    viewNumber);
            // broadcast new view
        }

        logger.info("ViewChangeMessage for view {} has reached quorum, sending",
                viewNumber);
    }
}
