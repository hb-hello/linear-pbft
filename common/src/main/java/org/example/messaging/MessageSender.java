package org.example.messaging;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.example.MessageServiceGrpc;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class MessageSender {

    protected final String nodeId;
    protected final StubManager stubManager;
    protected final CommunicationLogger commLogger;
    protected final MessageAuthenticator auth;
    private final AtomicBoolean active;

    protected MessageSender(String nodeId, CommunicationLogger commLogger, MessageAuthenticator auth) {
        this.nodeId = nodeId;
        this.commLogger = commLogger;
        this.stubManager = new StubManager(nodeId);
        this.auth = auth;
        this.active = new AtomicBoolean(true);
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public boolean isActive() {
        return active.get();
    }

    public void ensureActive() {
        if (!isActive()) {
            throw new IllegalStateException("Node is inactive. Cannot send messages.");
        }
    }

    // Generic method to sign and send a message using the provided gRPC method
    protected void signAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        ensureActive();
        Message signedMessage = auth.sign(message);
        MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
        method.accept(stub, signedMessage);
    }

    // Generic method to sign with threshold signature scheme and send a message using the provided gRPC method
    protected void signWithTSSAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        ensureActive();
        Message signedMessage = auth.signWithTSS(message);
        MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
        method.accept(stub, signedMessage);
    }

    protected void signWithAggregateTSSAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method, Map<String, ByteString> partialSignatures) {
        ensureActive();
        Message signedMessage = auth.signWithAggregateTss(message, partialSignatures);
        MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
        method.accept(stub, signedMessage);
    }

    protected void broadcast(Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        ensureActive();
        Message signedMessage = auth.sign(message);
        for (String targetNodeId : Config.getServerIdsExcept(nodeId)) {
            MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
            method.accept(stub, signedMessage);
        }
    }

    protected void broadcastWithTSS(Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        ensureActive();
        Message signedMessage = auth.signWithTSS(message);
        for (String targetNodeId : Config.getServerIdsExcept(nodeId)) {
            MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
            method.accept(stub, signedMessage);
        }
    }

    protected void broadcastWithAggregateTSS(Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method, Map<String, ByteString> partialSignatures) {
        ensureActive();
        Message signedMessage = auth.signWithAggregateTss(message, partialSignatures);
        for (String targetNodeId : Config.getServerIdsExcept(nodeId)) {
            MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
            method.accept(stub, signedMessage);
        }
    }

    public void shutdown() {
        stubManager.shutdown();
    }

}
