package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;

import java.util.concurrent.atomic.AtomicBoolean;

public class NodeMessageSender extends MessageSender {

    private static final Logger logger = LogManager.getLogger(NodeMessageSender.class);
    private final AtomicBoolean active;

    public NodeMessageSender(String nodeId, CommunicationLogger commLogger, MessageAuthenticator auth) {
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
