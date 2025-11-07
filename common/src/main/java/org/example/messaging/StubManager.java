package org.example.messaging;

import org.example.MessageServiceGrpc;

public class StubManager {

    // Shared ChannelManager reused across all StubManager instances.
    // Lazily initialized so the first StubManager can optionally provide an excludeNodeId.
    private static ChannelManager channelManager;

    public StubManager() {
        initChannelManager(null);
    }

    public StubManager(String excludeNodeId) {
        initChannelManager(excludeNodeId);
    }

    private static synchronized void initChannelManager(String excludeNodeId) {
        if (channelManager == null) {
            if (excludeNodeId == null || excludeNodeId.isEmpty()) {
                channelManager = new ChannelManager();
            } else {
                channelManager = new ChannelManager(excludeNodeId);
            }
        }
    }

    public MessageServiceGrpc.MessageServiceBlockingStub getBlockingStub(String nodeId) {
        initChannelManager(null); // ensure initialized
        return MessageServiceGrpc.newBlockingStub(channelManager.getChannel(nodeId));
    }

    public MessageServiceGrpc.MessageServiceFutureStub getFutureStub(String nodeId) {
        initChannelManager(null); // ensure initialized
        return MessageServiceGrpc.newFutureStub(channelManager.getChannel(nodeId));
    }

    public void shutdown() {
        if (channelManager != null) {
            channelManager.shutdownChannels();
        }
    }
}
