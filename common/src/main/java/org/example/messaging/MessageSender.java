package org.example.messaging;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.example.MessageServiceGrpc;
import org.example.crypto.MessageAuthenticator;

import java.util.Map;
import java.util.function.BiConsumer;

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

    public void setActive(boolean active) {
        // needed for subclass overrides
    }

    // Generic method to sign and send a message using the provided gRPC method
    protected void signAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        Message signedMessage = auth.sign(message);
        MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
        method.accept(stub, signedMessage);
    }

    // Generic method to sign with threshold signature scheme and send a message using the provided gRPC method
    protected void signWithTSSAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        Message signedMessage = auth.signWithTSS(message);
        MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
        method.accept(stub, signedMessage);
    }

    protected void signWithAggregateTSSAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method, Map<String, ByteString> partialSignatures) {
        Message signedMessage = auth.signWithAggregateTss(message, partialSignatures);
        MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
        method.accept(stub, signedMessage);
    }

    public void shutdown() {
        stubManager.shutdown();
    }

}
