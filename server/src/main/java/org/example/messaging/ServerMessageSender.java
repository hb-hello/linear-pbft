package org.example.messaging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;

import java.util.concurrent.atomic.AtomicBoolean;

public class ServerMessageSender extends MessageSender {

    private static final Logger logger = LogManager.getLogger(ServerMessageSender.class);
    private final AtomicBoolean active;

    public ServerMessageSender(String nodeId, CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(nodeId, commLogger, auth);
        this.active = new AtomicBoolean(true);
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public boolean isActive() {
        return active.get();
    }

    public void sendReply(String targetNodeId, MessageServiceOuterClass.ClientReply reply) {
        if (!isActive()) {
            logger.info("Node is inactive. Cannot send messages.");
        }
        signAndSend(targetNodeId, reply, (stub, signed) -> stub.reply((MessageServiceOuterClass.ClientReply) signed));
    }
}
