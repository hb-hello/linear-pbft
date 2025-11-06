package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.consensus.Checkpoint;
import org.example.consensus.senders.CheckpointSender;
import org.example.serverstate.ServerState;

import javax.swing.plaf.nimbus.State;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class CheckpointHandler {
    private static final Logger logger = LogManager.getLogger(CheckpointHandler.class);

    private final ServerState state;
    private final int quorumSize;
    private final CheckpointSender checkpointSender;

    public CheckpointHandler(ServerState state, int quorumSize, CheckpointSender checkpointSender) {
        this.quorumSize = quorumSize;
        this.state = state;
        this.checkpointSender = checkpointSender;
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
        ByteString digest = checkpointMessage.getDigest();

        if (!isValid(checkpointMessage)) {
            logger.info("Invalid Checkpoint for view {} seq {}, ignoring", viewNumber, sequenceNumber);
            return;
        }

        if (!state.appendServerMessage(checkpointMessage, quorumSize)) {
            logger.info("Failed to append Checkpoint message to state for view {} seq {}, likely due to duplicate check",
                    viewNumber, sequenceNumber);
            return;
        }

        if (!state.checkMessageQuorum(checkpointMessage)) {
            logger.info("Checkpoint for view {} seq {} has not reached quorum yet", viewNumber, sequenceNumber);
            return;
        }

        if (state.getLatestStableCheckpointSeqNum() >= sequenceNumber) {
            logger.info("Checkpoint for view {} seq {} is not newer than current stable checkpoint {}, ignoring",
                    viewNumber, sequenceNumber, state.getLatestStableCheckpointSeqNum());
            return;
        }

        if (state.isExecuted(sequenceNumber)) {
            logger.info("Checkpoint for view {} seq {} has reached quorum and is executed, adding stable checkpoint",
                    viewNumber, sequenceNumber);
            state.addStableCheckpoint(checkpointMessage);
            return;
        }

        logger.info("Checkpoint for view {} seq {} has reached quorum but is not executed yet, requesting state from other servers",
                viewNumber, sequenceNumber);


        // create state message using stable checkpoint's digest to await consensus
        MessageServiceOuterClass.StateMessage stateMessage =
                MessageServiceOuterClass.StateMessage.newBuilder()
                        .setDigest(digest)
                        .build();

        try {
            checkpointSender.broadcastStateRequest(state.getServerId());
            MessageServiceOuterClass.StateMessage stateMessageReceived = (MessageServiceOuterClass.StateMessage) state.appendAndAwaitConsensus(stateMessage, Duration.ofMillis(Config.getServerTimeoutMillis()), 2);
            logger.info("Received state for checkpoint view {} seq {}, applying snapshot to state machine", viewNumber, sequenceNumber);
            if (!state.applySnapshotToStateMachine(stateMessageReceived, sequenceNumber)) {
                logger.error("Failed to apply snapshot for checkpoint view {} seq {}", viewNumber, sequenceNumber);
                throw new RuntimeException("Failed to apply snapshot for checkpoint");
            }
            logger.info("Successfully applied snapshot for checkpoint view {} seq {}, adding stable checkpoint", viewNumber, sequenceNumber);
            state.addStableCheckpoint(checkpointMessage);
        } catch (TimeoutException e) {
            //do nothing, just log
            logger.warn("Timeout while awaiting state consensus for checkpoint view {} seq {}", viewNumber, sequenceNumber);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while awaiting state consensus for checkpoint view {} seq {}", viewNumber, sequenceNumber);
        }

    }
}
