package org.example.messaging;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.serialization.NodeDetails;
import org.example.serialization.NodeDetails;

import java.util.HashMap;
import java.util.Map;

public class ChannelManager {

    private static final Logger logger = LogManager.getLogger(ChannelManager.class);
    private final HashMap<String, ManagedChannel> channels; // [channels]: Map of node ids and their gRPC channels
    private final Map<String, NodeDetails> nodes;

    // create a constructor
    public ChannelManager() {
        this.channels = new HashMap<>();
        this.nodes = Config.getNodes();

//        Create channels for all nodes in the configuration
        for(String nodeId : nodes.keySet()) {
            createChannel(nodeId);
        }
    }

    public ChannelManager(String excludeNodeId) {
        this.channels = new HashMap<>();
        this.nodes = Config.getNodesExcept(excludeNodeId);

//        Create channels for all nodes in the configuration except the excluded node
        for (String nodeId : nodes.keySet()) {
            if (!nodeId.equals(excludeNodeId)) {
                createChannel(nodeId);
            }
        }
    }

    public void createChannel(String nodeId) {

        if (!nodes.containsKey(nodeId)) {
            logger.error("Node ID {} not found in configuration while creating GRPC channel.", nodeId);
            throw new RuntimeException();
        }
        NodeDetails node = nodes.get(nodeId);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(node.host(), node.port()).usePlaintext().build();
        channels.put(nodeId, channel);
        logger.info("Initialized GRPC channel to node {} at {}:{}", nodeId, node.host(), node.port());
    }

    public ManagedChannel getChannel(String nodeId) {
        if (!channels.containsKey(nodeId)) {
            logger.error("Channel for Node ID {} not found.", nodeId);
            throw new RuntimeException();
        }
        return channels.get(nodeId);
    }

    public void shutdownChannels() {
        for (Map.Entry<String, ManagedChannel> entry : channels.entrySet()) {
            String nodeId = entry.getKey();
            ManagedChannel channel = entry.getValue();
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
                logger.info("Shutdown GRPC channel to node {}", nodeId);
            }
        }
    }


}
