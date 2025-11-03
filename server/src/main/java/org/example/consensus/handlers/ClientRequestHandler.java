package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.senders.ClientRequestSender;
import org.example.consensus.senders.PrePrepareSender;
import org.example.serverstate.ServerState;

public class ClientRequestHandler {

    private static final Logger logger = LogManager.getLogger(ClientRequestHandler.class);

    private final ServerState state;
    private final ClientRequestSender sender;
    private final PrePrepareSender prePrepareSender;

    public ClientRequestHandler(ServerState state, ClientRequestSender sender, PrePrepareSender prePrepareSender) {
        this.state = state;
        this.sender = sender;
        this.prePrepareSender = prePrepareSender;
    }

    public void handle(MessageServiceOuterClass.ClientRequest request) {
        String clientId = request.getClientId();
        long timestamp = request.getTimestamp();
        MessageServiceOuterClass.Operation operation = request.getOperation();
        logger.info("Handling ClientRequest from client {}: timestamp {}, operation {}",
                clientId, timestamp, operation.getOpCase());

            state.runSync(() -> {
                logger.info("Entering state transition for ClientRequest from client {}: timestamp {}",
                        clientId, timestamp);
                if (timestamp <= state.lastReplyTimestamp(clientId)) {
                    logger.info("Ignoring stale ClientRequest from client {}: timestamp {}", clientId, timestamp);
                    // TODO: resend cached reply
                    return;
                }

                if (!state.appendServerMessage(request)) {
                    logger.info("Duplicate ClientRequest from client {}: timestamp {}, ignoring",
                            clientId, timestamp);
                    return;
                }
                // TODO: refresh liveness timer

                if (!state.isPrimary()) {
                    String primaryServerId = state.getPrimaryServerId();
                    logger.info("Forwarding ClientRequest from client {} to primary server {}", clientId, primaryServerId);
                    sender.forwardClientRequest(primaryServerId, request);
                    return;
                }

                // initiate PBFT protocol
                prePrepareSender.attemptPrePrepare(request);
            });

    }
}
