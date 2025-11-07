package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MaliceInjector;
import org.example.MessageServiceOuterClass;
import org.example.ServerNode;
import org.example.consensus.LivenessTimer;
import org.example.consensus.senders.NewViewSender;
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
    private final NewViewSender newViewSender;

    private final LivenessTimer viewChangeTimer;

    public ViewChangeHandler(ServerState state, MessageAuthenticator auth, int quorumSize, LivenessTimer viewChangeTimer,
                             ViewChangeSender viewChangeSender, NewViewSender newViewSender) {
        this.quorumSize = quorumSize;
        this.state = state;
        this.auth = auth;
        this.viewChangeSender = viewChangeSender;
        this.newViewSender = newViewSender;
        this.viewChangeTimer = viewChangeTimer;
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

//            if (prePrepareMessage.getViewNumber() != previousView) {
//                logger.warn("PreparedCertificate PrePrepare message view number {} does not match expected view number {}",
//                        prePrepareMessage.getViewNumber(), previousView);
//                return false;
//            }

            MessageServiceOuterClass.PrepareMessage prepareMessage = preparedCertificate.getPrepareMessage();

            if (!auth.verify(prepareMessage)) {
                logger.warn("Invalid signature for Prepare message in view {} seq {}", prepareMessage.getViewNumber(), seqNum);
                return false;
            }

            if (prepareMessage.getViewNumber() != prePrepareMessage.getViewNumber() || prepareMessage.getSequenceNumber() != seqNum) {
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

        if (viewNumber < state.getViewNumber()) {
            logger.info("Ignoring ViewChangeMessage for view {} because current view is {}",
                    viewNumber, state.getViewNumber());
            return;
        }

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
            logger.info("ViewChangeMessage for view {} has not reached full quorum yet", viewNumber);

            // this should happen once before we reach quorum - which is when view number will be incremented and primary will be updated
            if (state.appendAndCheckMinQuorumForViewChange(viewChangeMessage, ServerNode.majorityCountForViewChange()) && !state.isViewChangeInProgress()) {
                logger.info("ViewChangeMessage for view {} has reached minimum quorum for requesting view change, setting view change in progress to true and broadcasting ViewChange message",
                        viewNumber);
                viewChangeSender.broadcastViewChange(state, state.getViewNumber(), state.getViewChangeQuorumMinView());
                return;
            }

            logger.info("Minimum quorum for view change not yet reached for view {}", viewNumber);
            return;
        }

        if (MaliceInjector.injectCrashAttack(state.getServerId())) {
            logger.info("MaliceInjector crash attack activated - refraining from broadcasting NewView message");
            return;
        }

        newViewSender.broadcastNewView(state, viewNumber);

        logger.info("ViewChangeMessage for view {} has reached full quorum, starting view change timer",
                viewNumber);
        if (!state.isPrimary()) viewChangeTimer.startIfNotRunning();
    }
}
