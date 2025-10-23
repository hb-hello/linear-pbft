package org.example.messaging;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.ClientNode;
import org.example.MessageServiceGrpc;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;

public class ClientMessageService extends MessageServiceGrpc.MessageServiceImplBase {

    private static final Logger logger = LogManager.getLogger(ClientMessageService.class);
    private final ClientNode clientNode;
    private final MessageAuthenticator auth;

    public ClientMessageService(ClientNode clientNode, MessageAuthenticator auth) {
        this.clientNode = clientNode;
        this.auth = auth;
    }

    @Override
    public void reply(MessageServiceOuterClass.ClientReply request, StreamObserver<Empty> responseObserver) {
//        logger.info("Received reply from server {}: {}", request.getServerId(), request.getResult());
        // Verify the authenticity of the reply
        if (!auth.verify(request)) {
            logger.warn("Invalid signature for client request from client {}", request.getClientId());
            return;
        }
        logger.info("Signature verified for client request from client {}", request.getClientId());
        // Process the reply
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }
}
