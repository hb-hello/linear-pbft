package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.serverstate.ServerState;

public class CommitSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(CommitSender.class);

    private final ServerState state;
    private final int quorumSize;

    public CommitSender(String serverId, int quorumSize, ServerState state,
                        CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
        this.state = state;
        this.quorumSize = quorumSize;
    }

    public void sendCommit(long viewNumber, long sequenceNumber, byte[] digest) {
        logger.info("Preparing to send Commit for view {} seq {}",
                viewNumber, sequenceNumber);

        // Build Commit message
        org.example.MessageServiceOuterClass.CommitMessage commitMsg =
                org.example.MessageServiceOuterClass.CommitMessage.newBuilder()
                        .setViewNumber(viewNumber)
                        .setSequenceNumber(sequenceNumber)
                        .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                        .build();

        // Sign the message first so that signer_id is set
        org.example.MessageServiceOuterClass.CommitMessage signedCommitMsg =
                (org.example.MessageServiceOuterClass.CommitMessage) auth.sign(commitMsg);

        // Append our own signed Commit to state (we count our own vote in the quorum)
        if (!state.appendServerMessage(signedCommitMsg)) {
            logger.warn("Failed to append Commit message to state for view {} seq {}, likely due to duplicate check", viewNumber, sequenceNumber);
            return;
        };

        // Send the signed Commit to the collector
        send(state.getCollectorServerId(), signedCommitMsg, (stub, signed) -> stub.commit((org.example.MessageServiceOuterClass.CommitMessage) signed));
        logger.info("Sent Commit for view {} seq {}", viewNumber, sequenceNumber);
    }
}
