package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.serverstate.ServerState;

public class ClientRequestSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(ClientRequestSender.class);

    public ClientRequestSender(String serverId,
                        CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
    }

    public void forwardClientRequest(String targetServerId, MessageServiceOuterClass.ClientRequest request) {
        logger.info("Forwarding ClientRequest to server {}: {}", targetServerId, request.getTimestamp());
        signAndSend(targetServerId, request, (stub, signed) -> stub.request((MessageServiceOuterClass.ClientRequest) signed));
    }
}
