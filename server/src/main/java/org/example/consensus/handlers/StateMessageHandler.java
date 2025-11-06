package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.serverstate.ServerState;

import java.util.Objects;

/**
 * Handler for incoming StateMessage messages. These messages contain a snapshot of
 * the state machine and a digest. StateMessageHandler appends received state messages
 * into the ServerState's message tracker so they can participate in any state consensus
 * operations (e.g. checkpoints requesting state).
 */
public class StateMessageHandler {
    private static final Logger logger = LogManager.getLogger(StateMessageHandler.class);

    private final ServerState state;

    public StateMessageHandler(ServerState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    private boolean isValid(MessageServiceOuterClass.StateMessage stateMessage) {
        if (stateMessage == null) return false;
        // Basic validation: must include a digest and a non-empty snapshot map
        try {
            ByteString digest = stateMessage.getDigest();
            if (digest == null || digest.isEmpty()) {
                logger.warn("Received StateMessage with empty digest, ignoring");
                return false;
            }
            if (stateMessage.getStateSnapshotCount() == 0) {
                logger.warn("Received StateMessage with empty snapshot, ignoring");
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            logger.warn("Exception validating StateMessage: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Append a received StateMessage to the server state's message tracker.
     */
    public void handle(MessageServiceOuterClass.StateMessage stateMessage) {
        if (!isValid(stateMessage)) return;

        logger.info("Handling StateMessage (digest={})", stateMessage.getDigest());

        state.appendServerMessage(stateMessage, 2);

        logger.info("Appended StateMessage to state tracker (digest={})", stateMessage.getDigest());
    }
}

