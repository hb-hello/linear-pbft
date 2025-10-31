package org.example.messaging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;

import java.util.concurrent.atomic.AtomicBoolean;

public class ServerMessageSender extends MessageSender {

    private static final Logger logger = LogManager.getLogger(ServerMessageSender.class);
    private final AtomicBoolean active;

    public ServerMessageSender(String serverId, CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
        this.active = new AtomicBoolean(true);
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public boolean isActive() {
        return active.get();
    }

    private void ensureActive() {
        if (!isActive()) {
            throw new IllegalStateException("Node is inactive. Cannot send messages.");
        }
    }

    public void sendClientReply(String clientId, MessageServiceOuterClass.ClientReply reply) {
        ensureActive();
        logger.info("Sending ClientReply to client {}: {}", clientId, reply.getResult());
        signAndSend(clientId, reply, (stub, signed) -> stub.reply((MessageServiceOuterClass.ClientReply) signed));
    }

    public void sendPrePrepare(String targetServerId, MessageServiceOuterClass.PrePrepareRequest prePrepare) {
        ensureActive();
        logger.info("Sending PrePrepare to server {}: {}", targetServerId, prePrepare.getPrePrepareMessage().getViewNumber());
        signWithTSSAndSend(targetServerId, prePrepare, (stub, signed) -> stub.prePrepare((MessageServiceOuterClass.PrePrepareRequest) signed));
    }
}
