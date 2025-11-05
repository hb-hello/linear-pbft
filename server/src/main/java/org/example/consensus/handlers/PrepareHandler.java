package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.CommitSender;
import org.example.consensus.senders.PrepareSender;
import org.example.serverstate.ServerState;

import java.util.Objects;

public class PrepareHandler {
    private static final Logger logger = LogManager.getLogger(PrepareHandler.class);

    private final ServerState state;
    private final int quorumSize;
    private final PrepareSender prepareSender;
    private final CommitSender commitSender;

    public PrepareHandler(ServerState state, int quorumSize, PrepareSender prepareSender, CommitSender commitSender) {
        this.quorumSize = quorumSize;
        this.state = state;
        this.prepareSender = prepareSender;
        this.commitSender = commitSender;
    }

    private boolean isValid(MessageServiceOuterClass.PrepareMessage prepareMessage) {
        try {
            if (!Objects.equals(state.getCollectorServerId(), prepareMessage.getSignerId()) && !state.isCollector()) {
                logger.info("Prepare message signer {} is not the collector {}",
                        prepareMessage.getSignerId(),
                        state.getCollectorServerId());
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

        long viewNumber = prepareMessage.getViewNumber();
        long sequenceNumber = prepareMessage.getSequenceNumber();

        if (!isValid(prepareMessage)) {
            logger.info("Invalid Prepare message from {}, ignoring Prepare for view {} seq {}",
                    prepareMessage.getSignerId(),
                    viewNumber,
                    sequenceNumber);
            return;
        }

        if (!state.appendServerMessage(prepareMessage, quorumSize)) {
            logger.info("Failed to append Prepare message to state for view {} seq {}, likely due to duplicate check",
                    viewNumber, sequenceNumber);
            return;
        }

        logger.info("Received digest in Prepare for view {} seq {}: {}",
                viewNumber, sequenceNumber, prepareMessage.getDigest());

        if(!state.isPrepared(viewNumber, sequenceNumber)) {
            logger.info("Cannot send Commit / Aggregated Prepare for view {} seq {}: not prepared",
                    viewNumber, sequenceNumber);
            return;
        }

        if (state.isCollector() && !prepareMessage.getIsAggregated()) prepareSender.broadcastAggregatedPrepare(viewNumber, sequenceNumber);

        commitSender.sendCommit(viewNumber, sequenceNumber,
                prepareMessage.getDigest().toByteArray());
    }
}
