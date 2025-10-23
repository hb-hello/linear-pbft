package org.example;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.example.crypto.MessageAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class MessageReceiver {

    private static final Logger logger = LoggerFactory.getLogger(MessageReceiver.class);

    private final String nodeId;
    private final Server grpcServer;
    private final ServerActivityInterceptor interceptor;

    public MessageReceiver(String nodeId,
                           Node node,
                           CommunicationLogger commLogger, MessageAuthenticator auth) {
        this.nodeId = nodeId;
        this.interceptor = new ServerActivityInterceptor();

        MessageService messageService = new MessageService(node, commLogger, auth);
        this.grpcServer = ServerBuilder
                .forPort(Config.getServerPort(nodeId))
                .addService(messageService)
                .intercept(interceptor)
                .build();
    }

    public void setActive(boolean active) {
        interceptor.setActiveFlag(active);
    }

    public void startListening() {
        try {
            grpcServer.start();
            logger.info("GRPC Server {} started listening on port {}",
                    nodeId, Config.getServerPort(nodeId));
            grpcServer.awaitTermination();
        } catch (IOException e) {
            logger.error("Server {}: Error in starting GRPC server : {}", nodeId, e.getMessage());
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            logger.error("Server {}: GRPC server interrupted : {}", nodeId, e.getMessage());
            logger.info("GRPC server was shut down.");
            throw new RuntimeException(e);
        }
    }

    public void shutdown() {
        grpcServer.shutdown();
        try {
            if (!grpcServer.awaitTermination(5, TimeUnit.SECONDS)) {
                grpcServer.shutdownNow();
            }
        } catch (InterruptedException e) {
            grpcServer.shutdownNow();
        }
    }
}
