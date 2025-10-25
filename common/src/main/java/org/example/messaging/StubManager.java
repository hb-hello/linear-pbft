package org.example.messaging;

import org.example.MessageServiceGrpc;

public class StubManager {

    private final ChannelManager channelManager;

    public StubManager() {
        this.channelManager = new ChannelManager();
    }

    public StubManager(String excludeServerId) {
        this.channelManager = new ChannelManager(excludeServerId);
    }

    public MessageServiceGrpc.MessageServiceBlockingStub getBlockingStub(String serverId) {
        return MessageServiceGrpc.newBlockingStub(channelManager.getChannel(serverId));
    }

    public MessageServiceGrpc.MessageServiceFutureStub getFutureStub(String serverId) {
        return MessageServiceGrpc.newFutureStub(channelManager.getChannel(serverId));
    }

    public void shutdown() {
        channelManager.shutdownChannels();
    }
}
