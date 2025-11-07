package org.example.messaging;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MaliceInjector;
import org.example.MessageServiceGrpc;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class MessageSender {

    private static final Logger logger = LogManager.getLogger(MessageSender.class);

    protected final String nodeId;
    protected final StubManager stubManager;
    protected final CommunicationLogger commLogger;
    protected final MessageAuthenticator auth;
    private final AtomicBoolean active;

    private final ExecutorService networkExecutor;

    public MessageSender(String nodeId, CommunicationLogger commLogger, MessageAuthenticator auth, ExecutorService networkExecutor) {
        this.nodeId = nodeId;
        this.commLogger = commLogger;
        this.stubManager = new StubManager(nodeId);
        this.auth = auth;
        this.active = new AtomicBoolean(true);
        this.networkExecutor = networkExecutor;
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    public boolean isActive() {
        return active.get();
    }

    public void ensureActive() {
        if (!isActive()) {
            logger.warn("Node {} is inactive. Cannot send messages.", nodeId);
            throw new IllegalStateException("Node is inactive. Cannot send messages.");
        }
    }

    // Generic method to sign and send a message using the provided gRPC method
    protected void signAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        ensureActive();
        Message signedMessage = auth.sign(message);
        send(targetNodeId, signedMessage, method);
    }

    // Generic method to send an already-signed message using the provided gRPC method
    protected void send(String targetNodeId, Message signedMessage, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
//        logger.info("Preparing to send message to node {}: {}", targetNodeId, signedMessage.getDescriptorForType().getName());
        ensureActive();

        // to free up calling thread during timing attack
        networkExecutor.submit(() ->{
            // in dark attack
//        logger.info("Checking for MaliceInjector in dark attack for target {}", targetNodeId);
            String senderId = ServerMessage.wrap(signedMessage).getSenderId().orElse("unknown");
            if (MaliceInjector.injectInDarkAttack(senderId, targetNodeId)) {
                logger.info("MaliceInjector in dark attack activated - avoiding sending message to dark target {}", targetNodeId);
                return;
            }
//        logger.info("No dark attack for target {}", targetNodeId);

            MaliceInjector.injectTimingAttack(senderId);

            logger.info("Sending pre-signed message to node {}: {}", targetNodeId, signedMessage.getDescriptorForType().getName());
            MessageServiceGrpc.MessageServiceFutureStub stub = stubManager.getFutureStub(targetNodeId);
            method.accept(stub, signedMessage);
            commLogger.add(signedMessage, true);
        });
    }

    protected void signWithAggregateTSSAndSend(String targetNodeId, Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method, Map<String, ByteString> partialSignatures) {
        ensureActive();
        Message signedMessage = auth.signWithAggregateTss(message, partialSignatures);
        send(targetNodeId, signedMessage, method);
    }

    protected void broadcast(Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        ensureActive();
        for (String targetNodeId : Config.getServerIdsExcept(nodeId)) {
            send(targetNodeId, message, method);
        }
    }

    protected void signAndBroadcast(Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method) {
        ensureActive();
        Message signedMessage = auth.signWithTSS(message);
        logger.info("Signed broadcast message with TSS: {}", message.getClass());
        broadcast(signedMessage, method);
    }

    protected void broadcastWithAggregateTSS(Message message, BiConsumer<MessageServiceGrpc.MessageServiceFutureStub, Message> method, Map<String, ByteString> partialSignatures) {
        ensureActive();
        Message signedMessage = auth.signWithAggregateTss(message, partialSignatures);
        broadcast(signedMessage, method);
    }

    public void shutdown() {
        stubManager.shutdown();
    }

}
