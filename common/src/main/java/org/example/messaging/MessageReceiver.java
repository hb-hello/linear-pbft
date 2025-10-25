package org.example.messaging;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceGrpc;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class MessageReceiver {

    private static final Logger logger = LogManager.getLogger(MessageReceiver.class);

    private final String nodeId;
    private final int port;
    private final Server grpcServer;
    private final ServerActivityInterceptor interceptor;

    protected MessageReceiver(String nodeId, int port,
                              MessageServiceGrpc.MessageServiceImplBase service, ServerActivityInterceptor interceptor) {
        this.nodeId = nodeId;
        this.port = port;
        this.interceptor = interceptor;

        this.grpcServer = ServerBuilder
                .forPort(port)
                .addService(service)
                .intercept(interceptor)
                .build();
    }

    // Overloaded constructor without interceptor parameter
    protected MessageReceiver(String nodeId, int port,
                           MessageServiceGrpc.MessageServiceImplBase service) {
        this.nodeId = nodeId;
        this.port = port;
        this.interceptor = null;

        this.grpcServer = ServerBuilder
                .forPort(port)
                .addService(service)
                .build();
    }

    public void setActive(boolean active) {
        if (interceptor != null) {
            interceptor.setActiveFlag(active);
        }
    }

    public void startListening() {
        try {
            grpcServer.start();
            logger.info("GRPC Server for node {} started listening on port {}",
                    nodeId, port);
            grpcServer.awaitTermination();
        } catch (IOException e) {
            logger.error("Node {}: Error in starting GRPC server : {}", nodeId, e.getMessage());
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            logger.error("Node {}: GRPC server interrupted : {}", nodeId, e.getMessage());
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
