package org.example.consensus.handlers;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.ServerNode;
import org.example.consensus.senders.ClientReplySender;
import org.example.consensus.senders.CommitSender;
import org.example.serverstate.ServerState;

import java.util.Objects;

public class CommitHandler {
    private static final Logger logger = LogManager.getLogger(CommitHandler.class);

    private final ServerState state;
    private final int quorumSize;
    private final CommitSender commitSender;
    private final ClientReplySender clientReplySender;

    public CommitHandler(ServerState state, int quorumSize, CommitSender commitSender, ClientReplySender clientReplySender) {
        this.quorumSize = quorumSize;
        this.state = state;
        this.commitSender = commitSender;
        this.clientReplySender = clientReplySender;
    }

    private boolean isValid(MessageServiceOuterClass.CommitMessage commitMessage) {
        try {
            if (!Objects.equals(state.getCollectorServerId(), commitMessage.getSignerId()) && !state.isCollector()) {
                logger.info("Commit message signer {} is not the collector {}",
                        commitMessage.getSignerId(),
                        state.getCollectorServerId());
                return false;
            }
            state.ensureInView(commitMessage.getViewNumber());
            state.ensureInWatermarks(commitMessage.getSequenceNumber());
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public void handle(MessageServiceOuterClass.CommitMessage commitMessage) {

        long viewNumber = commitMessage.getViewNumber();
        long sequenceNumber = commitMessage.getSequenceNumber();
        ByteString digest = commitMessage.getDigest();

        if (!isValid(commitMessage)) {
            logger.info("Invalid Commit message from {}, ignoring Commit for view {} seq {}",
                    commitMessage.getSignerId(),
                    viewNumber,
                    sequenceNumber);
            return;
        }

        if (state.hasAggregatedCommit(viewNumber, sequenceNumber)) {
            logger.info("Aggregated Commit for view {} seq {} already exists in state, ignoring individual Commit",
                    viewNumber, sequenceNumber);
            return;
        }

        if (!state.appendServerMessage(commitMessage, quorumSize)) {
            logger.info("Failed to append Commit message to state for view {} seq {}, likely due to duplicate check",
                    viewNumber, sequenceNumber);
            return;
        }

        if(!state.isCommitted(viewNumber, sequenceNumber)) {
            logger.info("Cannot execute / send Aggregated Commit for view {} seq {}: not committed",
                    viewNumber, sequenceNumber);
            return;
        }

        // If this node is the collector and receives a non-aggregated commit, broadcast aggregated commit
        // The broadcastAggregatedCommit will handle execution, so we return early to avoid duplicate execution
        if (state.isCollector() && !commitMessage.getIsAggregated()) {
            commitSender.broadcastAggregatedCommit(viewNumber, sequenceNumber);
            return; // Collector executes in broadcastAggregatedCommit, don't execute again here
        }

        // Non-collectors execute here when they receive the aggregated commit
        if (!state.isCollector()) logger.info("Committed request for view {} seq {}, now executing", viewNumber, sequenceNumber);

        // Find the corresponding client request
        MessageServiceOuterClass.ClientRequest clientRequest = state.findClientRequest(digest);
        if (clientRequest == null) {
            logger.warn("Client Request not found for digest: {}", digest);
            return;
        }

        // Execute the operation (returns a future)
        state.executeRequest(clientRequest, sequenceNumber)
            .thenAccept(reply -> {
                // Send the reply to the client when execution completes
                if (reply != null) {
                    clientReplySender.sendClientReply(clientRequest, reply);
                }
            })
            .exceptionally(throwable -> {
                logger.error("Error executing request for view {} seq {}", viewNumber, sequenceNumber, throwable);
                return null;
            });
    }
}
