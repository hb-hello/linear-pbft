package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.ClientReplySender;
import org.example.consensus.senders.ClientRequestSender;
import org.example.consensus.senders.PrePrepareSender;
import org.example.serverstate.ServerState;

public class ClientRequestHandler {

    private static final Logger logger = LogManager.getLogger(ClientRequestHandler.class);

    private final ServerState state;
    private final ClientRequestSender clientRequestSender;
    private final ClientReplySender clientReplySender;
    private final PrePrepareSender prePrepareSender;

    public ClientRequestHandler(ServerState state, ClientRequestSender clientRequestSender, ClientReplySender clientReplySender, PrePrepareSender prePrepareSender) {
        this.state = state;
        this.clientRequestSender = clientRequestSender;
        this.clientReplySender = clientReplySender;
        this.prePrepareSender = prePrepareSender;
    }

    public void handle(MessageServiceOuterClass.ClientRequest request) {
        String clientId = request.getClientId();
        long timestamp = request.getTimestamp();
        MessageServiceOuterClass.Operation operation = request.getOperation();
        logger.info("Handling ClientRequest from client {}: timestamp {}, operation {}",
                clientId, timestamp, operation.getOpCase());


        logger.info("Entering state transition for ClientRequest from client {}: timestamp {}",
                clientId, timestamp);
        if (timestamp <= state.lastReplyTimestamp(clientId)) {
            logger.info("Ignoring stale ClientRequest from client {}: timestamp {}", clientId, timestamp);
            clientReplySender.resendCachedReply(state, request);
            return;
        }

        if (!state.appendClientRequest(request)) {
            logger.info("Duplicate ClientRequest from client {}: timestamp {}, ignoring",
                    clientId, timestamp);
            return;
        }

        if (!state.isPrimary()) {
            String primaryServerId = state.getPrimaryServerId();
            logger.info("Forwarding ClientRequest from client {} to primary server {}", clientId, primaryServerId);
            clientRequestSender.forwardClientRequest(primaryServerId, request);
            return;
        }

        // initiate PBFT protocol
        prePrepareSender.attemptPrePrepare(request);

    }
}
