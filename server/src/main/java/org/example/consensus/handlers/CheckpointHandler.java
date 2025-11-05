package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.Checkpoint;
import org.example.serverstate.ServerState;

public class CheckpointHandler {
    private static final Logger logger = LogManager.getLogger(CheckpointHandler.class);

    private final ServerState state;
    private final int quorumSize;

    public CheckpointHandler(ServerState state, int quorumSize) {
        this.quorumSize = quorumSize;
        this.state = state;
    }

    private boolean isValid(MessageServiceOuterClass.CheckpointMessage checkpointMessage) {
        try {
            state.ensureInView(checkpointMessage.getViewNumber());
            state.ensureInWatermarks(checkpointMessage.getSequenceNumber());
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public void handle(MessageServiceOuterClass.CheckpointMessage checkpointMessage) {

        long viewNumber = checkpointMessage.getViewNumber();
        long sequenceNumber = checkpointMessage.getSequenceNumber();

        if (!isValid(checkpointMessage)) {
            logger.info("Invalid Checkpoint for view {} seq {}, ignoring", viewNumber, sequenceNumber);
            return;
        }

        if (!state.appendServerMessage(checkpointMessage, quorumSize)) {
            logger.info("Failed to append Checkpoint message to state for view {} seq {}, likely due to duplicate check",
                    viewNumber, sequenceNumber);
            return;
        }

        if(!state.checkMessageQuorum(checkpointMessage)) {
            logger.info("Checkpoint for view {} seq {} has not reached quorum yet", viewNumber, sequenceNumber);
            return;
        }

        state.addStableCheckpoint(checkpointMessage);

    }
}
