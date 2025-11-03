package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.CommitSender;
import org.example.serverstate.ServerState;

import java.util.Objects;

public class PrepareHandler {
    private static final Logger logger = LogManager.getLogger(PrepareHandler.class);

    private final ServerState state;
    private final int quorumSize;
    private final CommitSender commitSender;

    public PrepareHandler(ServerState state, int quorumSize, CommitSender commitSender) {
        this.quorumSize = quorumSize;
        this.state = state;
        this.commitSender = commitSender;
    }

    private boolean isValid(MessageServiceOuterClass.PrepareMessage prepareMessage) {
        try {
            if (Objects.equals(state.getPrimaryServerId(), prepareMessage.getSignerId())) {
                return false;
            }
            state.ensureInView(prepareMessage.getViewNumber());
            state.ensureInWatermarks(prepareMessage.getSequenceNumber());
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public void handle(MessageServiceOuterClass.PrepareMessage prepareMessage) {

        if (!isValid(prepareMessage)) {
            logger.info("Invalid Prepare message from {}, ignoring Prepare for view {} seq {}",
                    prepareMessage.getSignerId(),
                    prepareMessage.getViewNumber(),
                    prepareMessage.getSequenceNumber());
            return;
        }

        state.appendServerMessage(prepareMessage);

        commitSender.attemptCommit(prepareMessage.getViewNumber(), prepareMessage.getSequenceNumber(),
                prepareMessage.getDigest().toByteArray());
    }
}
