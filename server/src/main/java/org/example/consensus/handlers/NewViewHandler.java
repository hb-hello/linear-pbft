package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.LivenessTimer;
import org.example.consensus.senders.NewViewSender;
import org.example.consensus.senders.PrepareSender;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.*;

public class NewViewHandler {
    private static final Logger logger = LogManager.getLogger(NewViewHandler.class);

    private final ServerState state;
    private final MessageAuthenticator auth;
    private final PrepareSender prepareSender;
    private final NewViewSender newViewSender;
    private final PrePrepareHandler prePrepareHandler;

    private final LivenessTimer viewChangeTimer;

    public NewViewHandler(ServerState state, MessageAuthenticator auth, LivenessTimer viewChangeTimer, PrepareSender prepareSender, NewViewSender newViewSender, PrePrepareHandler prePrepareHandler) {
        this.prepareSender = prepareSender;
        this.state = state;
        this.auth = auth;
        this.viewChangeTimer = viewChangeTimer;
        this.newViewSender = newViewSender;
        this.prePrepareHandler = prePrepareHandler;
    }

    /**
     * Verify checkpoint messages within a view change message.
     * Checks that all checkpoint messages have the correct view number and sequence number,
     * and that their signatures are valid.
     */
    private boolean verifyCheckpointMessages(List<MessageServiceOuterClass.CheckpointMessage> checkpointMessages,
                                             long expectedSeqNum) {
        for (MessageServiceOuterClass.CheckpointMessage checkpointMessage : checkpointMessages) {
//            if (checkpointMessage.getViewNumber() != expectedViewNumber) {
//                logger.warn("Checkpoint message view number {} does not match expected view number {}",
//                        checkpointMessage.getViewNumber(), expectedViewNumber);
//                return false;
//            }

            if (checkpointMessage.getSequenceNumber() != expectedSeqNum) {
                logger.warn("Checkpoint message sequence number {} does not match expected sequence number {}",
                        checkpointMessage.getSequenceNumber(), expectedSeqNum);
                return false;
            }

            if (!auth.verify(checkpointMessage)) {
                logger.warn("Invalid signature for Checkpoint message in view {} seq {}",
                        checkpointMessage.getViewNumber(), checkpointMessage.getSequenceNumber());
                return false;
            }
        }
        return true;
    }

    /**
     * Verify prepared certificates within a view change message.
     * Checks that pre-prepare and prepare messages are present, have matching view/seq numbers,
     * and have valid signatures.
     */
    private boolean verifyPreparedCertificates(List<MessageServiceOuterClass.PreparedCertificate> preparedCertificates) {
        for (MessageServiceOuterClass.PreparedCertificate preparedCertificate : preparedCertificates) {
            if (!preparedCertificate.hasPrePrepareMessage() || !preparedCertificate.hasPrepareMessage()) {
                logger.warn("PrePrepare message or Prepare message in PreparedCertificate is missing");
                return false;
            }

            MessageServiceOuterClass.PrePrepareMessage prePrepareMessage = preparedCertificate.getPrePrepareMessage();
            long seqNum = prePrepareMessage.getSequenceNumber();

            if (!auth.verify(prePrepareMessage)) {
                logger.warn("Invalid signature for PrePrepare message for view {} seq {} in Prepared certificate",
                        prePrepareMessage.getViewNumber(), seqNum);
                return false;
            }

            MessageServiceOuterClass.PrepareMessage prepareMessage = preparedCertificate.getPrepareMessage();

            if (!auth.verify(prepareMessage)) {
                logger.warn("Invalid signature for Prepare message in view {} seq {}",
                        prepareMessage.getViewNumber(), seqNum);
                return false;
            }

            if (prepareMessage.getViewNumber() != prePrepareMessage.getViewNumber() ||
                prepareMessage.getSequenceNumber() != seqNum) {
                logger.warn("PreparedCertificate Prepare message view number {} or sequence number {} does not match PrePrepare view {} seq {}",
                        prepareMessage.getViewNumber(), prepareMessage.getSequenceNumber(),
                        prePrepareMessage.getViewNumber(), seqNum);
                return false;
            }
        }
        return true;
    }

    /**
     * Verify a single view change message: signature, checkpoint messages, and prepared certificates.
     */
    private boolean verifyViewChangeMessage(MessageServiceOuterClass.ViewChangeMessage viewChangeMessage,
                                           long newViewNumber) {
        // Verify signature of view change message itself
        if (!auth.verify(viewChangeMessage)) {
            logger.warn("Invalid signature for ViewChangeMessage for view {}", viewChangeMessage.getViewNumber());
            return false;
        }

        // Verify view number matches expected new view
        if (viewChangeMessage.getViewNumber() != newViewNumber) {
            logger.warn("ViewChangeMessage has view number {} but expected {}",
                    viewChangeMessage.getViewNumber(), newViewNumber);
            return false;
        }

        long lastStableSeqNum = viewChangeMessage.getLastStableSequenceNumber();

        // Verify checkpoint messages
        if (!verifyCheckpointMessages(viewChangeMessage.getCheckpointMessagesList(),
                                     lastStableSeqNum)) {
            logger.warn("Invalid checkpoint messages in ViewChangeMessage for view {}", newViewNumber);
            return false;
        }

        // Verify prepared certificates
        if (!verifyPreparedCertificates(viewChangeMessage.getPreparedCertificatesList())) {
            logger.warn("Invalid prepared certificates in ViewChangeMessage for view {}", newViewNumber);
            return false;
        }

        return true;
    }

    /**
     * Calculate the minimum sequence number across all view change messages.
     */
    private long calculateMinSequenceNumber(List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages) {
        long minSeqNum = Long.MAX_VALUE;
        logger.info("Calculating minimum sequence number from view change messages");
        for (MessageServiceOuterClass.ViewChangeMessage vcMsg : viewChangeMessages) {
            if (vcMsg.getLastStableSequenceNumber() < minSeqNum) {
                minSeqNum = vcMsg.getLastStableSequenceNumber();
            }
        }
        return minSeqNum;
    }

    /**
     * Calculate the maximum sequence number from all prepared certificates in view change messages.
     */
    private long calculateMaxSequenceNumber(List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages) {
        long maxSeqNum = Long.MIN_VALUE;
        logger.info("Calculating maximum sequence number from prepared certificates in view change messages");
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

    /**
     * Extract pending requests (seq num -> digest mapping) from view change messages.
     */
    private Map<Long, ByteString> getPendingRequests(List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages) {
        Map<Long, ByteString> pendingRequests = new HashMap<>();
        logger.info("Extracting pending requests from view change messages for view{}", viewChangeMessages.get(0).getViewNumber());
        for (MessageServiceOuterClass.ViewChangeMessage vcMsg : viewChangeMessages) {
            for (MessageServiceOuterClass.PreparedCertificate pc : vcMsg.getPreparedCertificatesList()) {
                long seqNum = pc.getPrePrepareMessage().getSequenceNumber();
                ByteString digest = pc.getPrePrepareMessage().getDigest();
                pendingRequests.put(seqNum, digest);
            }
        }

        return pendingRequests;
    }

    /**
     * Verify that pre-prepare messages in the new view match the pending requests from view change messages.
     */
    private boolean verifyPrePrepareMessages(List<MessageServiceOuterClass.PrePrepareMessage> prePrepareMessages,
                                            Map<Long, ByteString> pendingRequests,
                                            long newViewNumber,
                                            long minSeqNum,
                                            long maxSeqNum) {
        ByteString nullDigest = ByteString.copyFrom(new byte[32]);

        // Check that pre-prepares cover the range [minSeqNum, maxSeqNum]
        Set<Long> seqNumsInPrePrepares = new HashSet<>();
        for (MessageServiceOuterClass.PrePrepareMessage prePrepare : prePrepareMessages) {
            long seqNum = prePrepare.getSequenceNumber();
            seqNumsInPrePrepares.add(seqNum);

            logger.info("Verifying PrePrepare message for view {} seq {}",
                    prePrepare.getViewNumber(), seqNum);
            if(!auth.verify(prePrepare)) {
                logger.warn("Invalid signature for PrePrepare message in view {} seq {}",
                        prePrepare.getViewNumber(), seqNum);
                return false;
            }

            logger.info("Verifying view number for PrePrepare message seq {}: expected {}, got {}",
                    seqNum, newViewNumber, prePrepare.getViewNumber());
            // Verify view number
            if (prePrepare.getViewNumber() != newViewNumber) {
                logger.warn("PrePrepare message has view number {} but expected {}",
                        prePrepare.getViewNumber(), newViewNumber);
                return false;
            }

            logger.info("Verifying sequence number for PrePrepare message: expected range [{}, {}], got {}",
                    Math.max(minSeqNum, 1L), maxSeqNum, seqNum);
            // Verify sequence number is in valid range
            if (seqNum < Math.max(minSeqNum, 1L) || seqNum > maxSeqNum) {
                logger.warn("PrePrepare message has sequence number {} outside expected range [{}, {}]",
                        seqNum, minSeqNum, maxSeqNum);
                return false;
            }

            logger.info("Verifying digest for PrePrepare message seq {} against pending requests", seqNum);
            // Verify digest matches pending request or is null digest
            ByteString expectedDigest = pendingRequests.get(seqNum);
            if (expectedDigest == null) {
                // Should be null digest (no-op)
                if (!prePrepare.getDigest().equals(nullDigest)) {
                    logger.warn("PrePrepare for seq {} has no pending request but digest is not null", seqNum);
                    return false;
                }
            } else {
                // Should match the pending request's digest
                if (!prePrepare.getDigest().equals(expectedDigest)) {
                    logger.warn("PrePrepare for seq {} has digest mismatch", seqNum);
                    return false;
                }
            }
        }

        logger.info("Verifying coverage of PrePrepare messages for sequence numbers in range [{}, {}]",
                Math.max(minSeqNum, 1L), maxSeqNum);
        // Verify all sequence numbers in range are covered
        for (long seqNum = Math.max(minSeqNum, 1L); seqNum <= maxSeqNum; seqNum++) {
            if (!seqNumsInPrePrepares.contains(seqNum)) {
                logger.warn("PrePrepare messages missing sequence number {} in range [{}, {}]",
                        seqNum, minSeqNum, maxSeqNum);
                return false;
            }
        }

        return true;
    }

    public void handle(MessageServiceOuterClass.NewViewMessage newView) {
        long currentView = state.getViewNumber();
        long newViewNumber = newView.getViewNumber();

        if (newViewNumber < currentView) {
            logger.warn("Received NewViewMessage for view {} which is not greater than current view {}. Ignoring.",
                    newViewNumber, currentView);
            return;
        }

        state.setViewChangeInProgress(true);

        List<MessageServiceOuterClass.ViewChangeMessage> viewChangeMessages = newView.getViewChangeMessagesList();

        // Verify we have enough view change messages (should be quorum)
        if (viewChangeMessages.isEmpty()) {
            logger.warn("NewViewMessage for view {} contains no view change messages", newViewNumber);
            return;
        }

        logger.info("Verifying NewViewMessage for view {} with {} view change messages and {} pre-prepare messages",
                newViewNumber, viewChangeMessages.size(), newView.getPrePrepareMessagesList().size());

        // Verify each view change message
        for (MessageServiceOuterClass.ViewChangeMessage viewChangeMessage : viewChangeMessages) {
            if (!verifyViewChangeMessage(viewChangeMessage, newViewNumber)) {
                logger.warn("Invalid view change message in NewViewMessage for view {}", newViewNumber);
                return;
            }
        }
        logger.info("All view change messages in NewViewMessage for view {} are valid", newViewNumber);

        // Calculate min/max sequence numbers and pending requests from view change messages
        long minSeqNum = calculateMinSequenceNumber(viewChangeMessages);
        long maxSeqNum = calculateMaxSequenceNumber(viewChangeMessages);

        if (minSeqNum >= maxSeqNum) {
            logger.info("NewViewMessage for view {} has no pending requests (min {} >= max {})",
                    newViewNumber, minSeqNum, maxSeqNum);
            // This is valid - no pending operations to carry over
            // Continue processing
        }

        Map<Long, ByteString> pendingRequests = getPendingRequests(viewChangeMessages);
        logger.info("NewViewMessage for view {} has pending requests for seq nums {} to {} in view change messages, verifying against pre-prepares now",
                newViewNumber, Math.max(minSeqNum, 1L), maxSeqNum);
        // Verify pre-prepare messages match the view change messages
        if (!verifyPrePrepareMessages(newView.getPrePrepareMessagesList(),
                                     pendingRequests,
                                     newViewNumber,
                                     minSeqNum,
                                     maxSeqNum)) {
            logger.warn("Invalid pre-prepare messages in NewViewMessage for view {}", newViewNumber);
            return;
        }

        logger.info("NewViewMessage for view {} passed all verification checks", newViewNumber);

        // Apply the new view - update state, process pre-prepares
        applyNewView(newView);
    }

    /**
     * Apply the new view: update view number, primary server, process pre-prepare messages,
     * and complete the view change.
     */
    private void applyNewView(MessageServiceOuterClass.NewViewMessage newView) {
        long newViewNumber = newView.getViewNumber();

        logger.info("Applying NewView for view {}", newViewNumber);

        // Update to the new view number and primary
        if(!state.setViewAndPrimary(newViewNumber)) {
            logger.warn("Failed to set new view {} and primary, another view change may have completed first",
                    newViewNumber);
            return;
        }

        // Stop view change timer
        viewChangeTimer.stop();
        if (!state.getPendingOperations().isEmpty()) {
            logger.info("Restarting liveness timer for view {} due to pending operations", newViewNumber);
            viewChangeTimer.start();
        }

        // clear view change state
        state.setViewChangeInProgress(false);

        // Process each pre-prepare message from the new view
        for (MessageServiceOuterClass.PrePrepareMessage prePrepareMessage : newView.getPrePrepareMessagesList()) {
            processPrePrepareFromNewView(prePrepareMessage);
        }

        Set<MessageServiceOuterClass.PrePrepareRequest> prePrepareRequests = state.snapshotPrePrepareBuffer();
        if (!prePrepareRequests.isEmpty()) {
            logger.info("Processing {} buffered PrePrepare messages after applying NewView for view {}",
                    prePrepareRequests.size(), newViewNumber);
            for (MessageServiceOuterClass.PrePrepareRequest bufferedPrePrepare : prePrepareRequests) {
                prePrepareHandler.handle(bufferedPrePrepare);
            }
        }

        logger.info("Successfully applied NewView for view {}", newViewNumber);
    }

    /**
     * Process a single pre-prepare message from a new view.
     * Adapted from PrePrepareHandler but works with PrePrepareMessages directly
     * (without ClientRequests).
     */
    private void processPrePrepareFromNewView(MessageServiceOuterClass.PrePrepareMessage prePrepareMessage) {
        long viewNumber = prePrepareMessage.getViewNumber();
        long seqNum = prePrepareMessage.getSequenceNumber();
        ByteString digest = prePrepareMessage.getDigest();

        logger.info("Processing PrePrepare from NewView for view {} seq {} digest {}",
                viewNumber, seqNum, digest);

        // Check if we already have this pre-prepare logged
        ServerMessage alreadyLoggedPrePrepare = state.findPrePrepare(viewNumber, seqNum);
        if (alreadyLoggedPrePrepare != null && alreadyLoggedPrePrepare.getDigest().isPresent()) {
            if (alreadyLoggedPrePrepare.getDigest().get().equals(digest)) {
                logger.info("PrePrepare for view {} seq {} already logged with same digest, skipping",
                        viewNumber, seqNum);
                return;
            } else {
                logger.warn("PrePrepare for view {} seq {} already logged with different digest, ignoring",
                        viewNumber, seqNum);
                return;
            }
        }

        // Verify sequence number is within watermarks
        try {
            state.ensureInWatermarks(seqNum);
        } catch (IllegalStateException e) {
            logger.warn("PrePrepare from NewView has sequence number {} outside watermarks, ignoring", seqNum);
            return;
        }

        ByteString nullDigest = ByteString.copyFrom(new byte[32]);
        if (!digest.equals(nullDigest)) {
            logger.info("PrePrepare from NewView for view {} seq {} is a not no-op (null digest), requesting client message from other servers",
                    viewNumber, seqNum);
            newViewSender.requestClientMessage(state, viewNumber, prePrepareMessage);
        } else {
            // Append the pre-prepare message to state (with null request since we don't have it yet)
            if (!state.appendServerMessage(prePrepareMessage, null, 0)) {
                logger.info("Failed to append PrePrepare from NewView for view {} seq {}, already exists",
                        viewNumber, seqNum);
                return;
            }
        }

        prepareSender.sendPrepare(state.getViewNumber(), prePrepareMessage.getSequenceNumber(),
                prePrepareMessage.getDigest().toByteArray());

        logger.info("Successfully processed PrePrepare from NewView for view {} seq {}", viewNumber, seqNum);
    }
}
