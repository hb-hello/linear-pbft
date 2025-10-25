package org.example.messaging;

import org.example.MessageServiceGrpc;

public class StubManager {

    private final ChannelManager channelManager;

    public StubManager() {
        this.channelManager = new ChannelManager();
    }

    public StubManager(String excludeNodeId) {
        this.channelManager = new ChannelManager(excludeNodeId);
    }

    public MessageServiceGrpc.MessageServiceBlockingStub getBlockingStub(String nodeId) {
        return MessageServiceGrpc.newBlockingStub(channelManager.getChannel(nodeId));
    }

    public MessageServiceGrpc.MessageServiceFutureStub getFutureStub(String nodeId) {
        return MessageServiceGrpc.newFutureStub(channelManager.getChannel(nodeId));
    }

    public void shutdown() {
        channelManager.shutdownChannels();
    }
}
