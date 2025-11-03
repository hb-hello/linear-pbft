package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.serverstate.ServerState;

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

        // Sign the message first so that signer_id is set
        MessageServiceOuterClass.PrepareMessage signedPrepareMsg =
                (MessageServiceOuterClass.PrepareMessage) auth.sign(prepareMsg);

        // Append our own signed Prepare to state (we count our own vote in the quorum)
        if (!state.appendServerMessage(signedPrepareMsg)) {
            logger.warn("Failed to append Prepare message to state for view {} seq {}, likely due to duplicate check", viewNumber, sequenceNumber);
            return;
        };

        // Send the signed PrepareRequest to the collector
        send(state.getCollectorServerId(), signedPrepareMsg, (stub, signed) -> stub.prepare((MessageServiceOuterClass.PrepareMessage) signed));
        logger.info("Sent Prepare for view {} seq {}", viewNumber, sequenceNumber);
    }

}
