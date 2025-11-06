package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.ServerNode;
import org.example.consensus.Checkpoint;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.MessageUtil;
import org.example.serverstate.ServerState;

import java.util.Map;

public class CheckpointSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(CheckpointSender.class);

    public CheckpointSender(String serverId,
                         CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
    }

    private ByteString generateCheckpointDigest(ServerState state) {
        logger.info("Generating checkpoint digest for server {}", state.getServerId());
        Object stateMachineSnapshot = state.snapshotStateMachine();
        logger.info("State machine snapshot taken : {}", stateMachineSnapshot.toString());
        byte[] checkpointDigest = MessageUtil.generateDigest(stateMachineSnapshot);
        return ByteString.copyFrom(checkpointDigest);
    }

    public void sendCheckpoint(ServerState state, long sequenceNumber) {

        if(!state.atCheckpointInterval(sequenceNumber)) {
            logger.info("Sequence number {} is not at checkpoint interval, skipping Checkpoint send", sequenceNumber);
            return;
        }

        long viewNumber = state.getViewNumber();

        logger.info("Preparing to send Checkpoint for view {} seq {}", viewNumber, sequenceNumber);

        ByteString checkpointDigest = generateCheckpointDigest(state);

        // Build Checkpoint message
        MessageServiceOuterClass.CheckpointMessage checkpointMsg =
                MessageServiceOuterClass.CheckpointMessage.newBuilder()
                        .setViewNumber(viewNumber)
                        .setSequenceNumber(sequenceNumber)
                        .setDigest(checkpointDigest)
                        .build();

        MessageServiceOuterClass.CheckpointMessage signedCheckpointMsg =
                (MessageServiceOuterClass.CheckpointMessage) auth.sign(checkpointMsg);

        if (!state.appendServerMessage(signedCheckpointMsg, ServerNode.majorityCount())) {
            logger.warn("Failed to append Checkpoint message to state for view {} seq {}, likely due to duplicate check", viewNumber, sequenceNumber);
            return;
        };

        broadcast(signedCheckpointMsg, (stub, signed) -> stub.checkpoint((MessageServiceOuterClass.CheckpointMessage) signed));
        logger.info("Sent Checkpoint for view {} seq {}", viewNumber, sequenceNumber);
    }

    public void broadcastStateRequest(String serverId) {
        logger.info("Broadcasting state request from server {}", serverId);
        MessageServiceOuterClass.StateRequestMessage stateRequestMessage = MessageServiceOuterClass.StateRequestMessage.newBuilder()
                .setRequesterId(serverId)
                .build();

        broadcast(stateRequestMessage, (stub, msg) -> stub.stateRequest((MessageServiceOuterClass.StateRequestMessage) msg));
    }
}
