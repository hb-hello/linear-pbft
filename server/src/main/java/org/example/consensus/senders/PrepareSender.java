package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.Map;

public class PrepareSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(PrepareSender.class);

    private final ServerState state;

    public PrepareSender(String serverId, ServerState state,
                         CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
        this.state = state;
    }

    // should be called from within state executor
    public void sendPrepare(long viewNumber, long sequenceNumber, byte[] digest) {

        logger.info("Preparing to send Prepare for view {} seq {}",
                viewNumber, sequenceNumber);

        // Build Prepare message
        org.example.MessageServiceOuterClass.PrepareMessage prepareMsg =
                org.example.MessageServiceOuterClass.PrepareMessage.newBuilder()
                        .setViewNumber(viewNumber)
                        .setSequenceNumber(sequenceNumber)
                        .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                        .build();

        MessageServiceOuterClass.PrepareMessage signedPrepareMsg =
                (MessageServiceOuterClass.PrepareMessage) auth.sign(prepareMsg);

        if (!state.appendServerMessage(signedPrepareMsg)) {
            logger.warn("Failed to append Prepare message to state for view {} seq {}, likely due to duplicate check", viewNumber, sequenceNumber);
            return;
        };

        send(state.getCollectorServerId(), signedPrepareMsg, (stub, signed) -> stub.prepare((MessageServiceOuterClass.PrepareMessage) signed));
        logger.info("Sent Prepare for view {} seq {}", viewNumber, sequenceNumber);
    }

    public void broadcastAggregatedPrepare(long viewNumber, long sequenceNumber) {
        logger.info("Creating aggregated Prepare for view {} seq {}", viewNumber, sequenceNumber);

        Map<String, ByteString> prepareSignatures = state.getQuorumSignatures(ServerMessage.PREPARE, viewNumber, sequenceNumber);

        if (prepareSignatures.isEmpty()) {
            logger.warn("No Prepare quorum found to aggregate for view {} seq {}", viewNumber, sequenceNumber);
            return;
        }

        ByteString digest = state.getQuorumDigest(ServerMessage.PREPARE, viewNumber, sequenceNumber);

        // Build AggregatedPrepare message
        MessageServiceOuterClass.PrepareMessage aggregatedPrepareMsg =
                MessageServiceOuterClass.PrepareMessage.newBuilder()
                        .setViewNumber(viewNumber)
                        .setSequenceNumber(sequenceNumber)
                        .setDigest(digest)
                        .build();

        MessageServiceOuterClass.PrepareMessage signedPrepareMsg =
                (MessageServiceOuterClass.PrepareMessage) auth.signWithAggregateTss(aggregatedPrepareMsg, prepareSignatures);

        if (!state.appendServerMessage(signedPrepareMsg)) {
            logger.warn("Failed to append Aggregated Prepare message to state for view {} seq {}, likely due to duplicate check", viewNumber, sequenceNumber);
            return;
        };

        broadcast(aggregatedPrepareMsg, (stub, signed) -> stub.prepare((MessageServiceOuterClass.PrepareMessage) signed));
        logger.info("Broadcasted Aggregated Prepare for view {} seq {}", viewNumber, sequenceNumber);
    }
}
