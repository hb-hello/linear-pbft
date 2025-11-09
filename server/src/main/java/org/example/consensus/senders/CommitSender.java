package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MaliceInjector;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.Map;
import java.util.concurrent.ExecutorService;

public class CommitSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(CommitSender.class);

    private final ServerState state;
    private final int quorumSize;
    private final ClientReplySender clientReplySender;

    public CommitSender(String serverId, int quorumSize, ClientReplySender clientReplySender, ServerState state,
                        CommunicationLogger commLogger, MessageAuthenticator auth, ExecutorService networkExecutor) {
        super(serverId, commLogger, auth, networkExecutor);
        this.state = state;
        this.quorumSize = quorumSize;
        this.clientReplySender = clientReplySender;
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
        if (!state.appendServerMessage(signedCommitMsg, quorumSize)) {
            logger.warn("Failed to append Commit message to state for view {} seq {}, likely due to duplicate check", viewNumber, sequenceNumber);
            return;
        };

        // Send the signed Commit to the collector
        if (!state.isCollector()) {
            
            send(state.getCollectorServerId(), state.isPrimary(), signedCommitMsg, (stub, signed) -> stub.commit((org.example.MessageServiceOuterClass.CommitMessage) signed));
        };
        logger.info("Sent Commit for view {} seq {}", viewNumber, sequenceNumber);
    }

    public void broadcastAggregatedCommit(long viewNumber, long sequenceNumber) {

        if (state.hasAggregatedCommit(viewNumber, sequenceNumber)) {
            logger.info("Aggregated Commit for view {} seq {} already exists in state, not creating another", viewNumber, sequenceNumber);
            return;
        }

        logger.info("Creating aggregated Commit for view {} seq {}", viewNumber, sequenceNumber);

        Map<String, ByteString> commitSignatures = state.getQuorumSignatures(ServerMessage.COMMIT, viewNumber, sequenceNumber);

        if (commitSignatures.isEmpty()) {
            logger.warn("No Commit quorum found to aggregate for view {} seq {}", viewNumber, sequenceNumber);
            return;
        }

        ByteString digest = state.getQuorumDigest(ServerMessage.COMMIT, viewNumber, sequenceNumber);

        // Build AggregatedCommit message
        MessageServiceOuterClass.CommitMessage aggregatedCommitMsg =
                MessageServiceOuterClass.CommitMessage.newBuilder()
                        .setViewNumber(viewNumber)
                        .setSequenceNumber(sequenceNumber)
                        .setDigest(digest)
                        .build();

        MessageServiceOuterClass.CommitMessage signedCommitMsg =
                (MessageServiceOuterClass.CommitMessage) auth.signWithAggregateTss(aggregatedCommitMsg, commitSignatures);

        if (!state.appendServerMessage(signedCommitMsg, quorumSize)) {
            logger.warn("Failed to append Aggregated Commit message to state for view {} seq {}, likely due to duplicate check", viewNumber, sequenceNumber);
            return;
        };

        broadcast(signedCommitMsg, state.isPrimary(), (stub, signed) -> stub.commit((MessageServiceOuterClass.CommitMessage) signed));
        logger.info("Broadcasted Aggregated Commit for view {} seq {}", viewNumber, sequenceNumber);

        logger.info("Committed request for view {} seq {}, now executing", viewNumber, sequenceNumber);

        // Find the corresponding client request
        MessageServiceOuterClass.ClientRequest clientRequest = state.findClientRequest(digest);

        // Execute the operation (returns a future)
        state.executeRequest(clientRequest, digest, sequenceNumber);
    }
}
