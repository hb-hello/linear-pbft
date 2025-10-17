package org.example;

import io.grpc.ManagedChannel;

import java.util.concurrent.atomic.AtomicBoolean;

public class MessageSender {

    private final String nodeId;
    private final StubManager stubManager;
    private final CommunicationLogger commLogger;
    private final AtomicBoolean active;

    public MessageSender(String nodeId, CommunicationLogger commLogger) {
        this.nodeId = nodeId;
        this.commLogger = commLogger;
        this.stubManager = new StubManager(nodeId);
        this.active = new AtomicBoolean(true);
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public boolean isActive() {
        return active.get();
    }

    public void shutdown() {
        stubManager.shutdown();
    }

}
