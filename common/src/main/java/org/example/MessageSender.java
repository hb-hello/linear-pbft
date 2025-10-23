package org.example;

import com.google.protobuf.Message;
import io.grpc.stub.AbstractBlockingStub;
import org.example.crypto.MessageAuthenticator;

import java.util.function.BiFunction;

public class MessageSender {

    protected final String nodeId;
    protected final StubManager stubManager;
    protected final CommunicationLogger commLogger;
    protected final MessageAuthenticator auth;

    protected MessageSender(String nodeId, CommunicationLogger commLogger, MessageAuthenticator auth) {
        this.nodeId = nodeId;
        this.commLogger = commLogger;
        this.stubManager = new StubManager(nodeId);
        this.auth = auth;
    }

    // Generic method to sign and send a message using the provided gRPC method
    protected Message signAndSend(String targetNodeId, Message message, BiFunction<MessageServiceGrpc.MessageServiceBlockingStub, Message, Message> method) {
        Message signedMessage = auth.sign(message);
        MessageServiceGrpc.MessageServiceBlockingStub stub = stubManager.getBlockingStub(targetNodeId);
        return method.apply(stub, signedMessage);
    }

    protected void shutdown() {
        stubManager.shutdown();
    }

}
