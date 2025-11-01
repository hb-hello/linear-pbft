package org.example.messaging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;

import java.util.concurrent.atomic.AtomicBoolean;

public class ServerMessageSender extends MessageSender {

    private static final Logger logger = LogManager.getLogger(ServerMessageSender.class);

    public ServerMessageSender(String serverId, CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
    }

    public void sendClientReply(String clientId, MessageServiceOuterClass.ClientReply reply) {
        ensureActive();
        logger.info("Sending ClientReply to client {}: {}", clientId, reply.getResult());
        signAndSend(clientId, reply, (stub, signed) -> stub.reply((MessageServiceOuterClass.ClientReply) signed));
    }

    public void forwardClientRequest(String targetServerId, MessageServiceOuterClass.ClientRequest request) {
        ensureActive();
        logger.info("Forwarding ClientRequest to server {}: {}", targetServerId, request.getTimestamp());
        signAndSend(targetServerId, request, (stub, signed) -> stub.request((MessageServiceOuterClass.ClientRequest) signed));
    }
}
