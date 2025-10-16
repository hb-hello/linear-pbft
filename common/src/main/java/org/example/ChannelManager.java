package org.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

public class ChannelManager {

    private static final Logger logger = LogManager.getLogger(ChannelManager.class);
    private final HashMap<String, ManagedChannel> channels; // [channels]: Map of server ids and their gRPC channels
    private final Map<String, ServerDetails> servers;

    // create a constructor
    public ChannelManager() {
        this.channels = new HashMap<>();
        this.servers = Config.getServers();

//        Create channels for all servers in the configuration
        for(String serverId : servers.keySet()) {
            createChannel(serverId);
        }
    }

    public ChannelManager(String excludeServerId) {
        this.channels = new HashMap<>();
        this.servers = Config.getServersExcept(excludeServerId);

//        Create channels for all servers in the configuration except the excluded server
        for (String serverId : servers.keySet()) {
            if (!serverId.equals(excludeServerId)) {
                createChannel(serverId);
            }
        }
    }

    public void createChannel(String serverId) {

        if (!servers.containsKey(serverId)) {
            logger.error("Server ID {} not found in configuration while creating GRPC channel.", serverId);
            throw new RuntimeException();
        }
        ServerDetails server = servers.get(serverId);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(server.host(), server.port()).usePlaintext().build();
        channels.put(serverId, channel);
        logger.info("Initialized GRPC channel to server {} at {}:{}", serverId, server.host(), server.port());
    }

    public ManagedChannel getChannel(String serverId) {
        if (!channels.containsKey(serverId)) {
            logger.error("Channel for Server ID {} not found.", serverId);
            throw new RuntimeException();
        }
        return channels.get(serverId);
    }

    public void shutdownChannels() {
        for (Map.Entry<String, ManagedChannel> entry : channels.entrySet()) {
            String serverId = entry.getKey();
            ManagedChannel channel = entry.getValue();
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
                logger.info("Shutdown GRPC channel to server {}", serverId);
            }
        }
    }


}
