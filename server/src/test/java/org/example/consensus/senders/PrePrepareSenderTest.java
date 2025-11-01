package org.example.consensus.senders;

import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.serverstate.ServerState;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PrePrepareSenderTest {

    // Sender that captures the broadcast request
    static class CapturingPrePrepareSender extends PrePrepareSender {
        MessageServiceOuterClass.PrePrepareRequest captured;
        public CapturingPrePrepareSender(String nodeId, ServerState state, CommunicationLogger commLogger, MessageAuthenticator auth) {
            super(nodeId, state, commLogger, auth);
        }
        @Override
        void broadcastToServers(MessageServiceOuterClass.PrePrepareRequest request) {
            this.captured = request; // do not call super to avoid networking
        }
    }

    @Test
    void attemptPrePrepare_broadcastsWithCorrectFields_whenCanSend() throws Exception {
        Config.initialize("src/test/resources/config.properties");
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "state-manager-test"));
        try {
            ServerState state = new ServerState("n1", false, exec);
            // Make this node primary by choosing a view whose primary is n1
            state.setViewAndPrimary(1L);

            CapturingPrePrepareSender sender = new CapturingPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
            sender.setActive(true);

            MessageServiceOuterClass.ClientRequest clientReq = MessageServiceOuterClass.ClientRequest.newBuilder().build();

            long expectedSeq = state.snapshotHeader().seq() + 1; // nextSeq will be called inside
            long expectedView = state.getViewNumber();

            sender.attemptPrePrepare(clientReq);

            MessageServiceOuterClass.PrePrepareRequest sent = sender.captured;
            assertNotNull(sent);

            byte[] expectedDigest = MessageDigest.getInstance("MD5").digest(clientReq.toByteArray());
            assertEquals(expectedView, sent.getPrePrepareMessage().getViewNumber());
            assertEquals(expectedSeq, sent.getPrePrepareMessage().getSequenceNumber());
            assertArrayEquals(expectedDigest, sent.getPrePrepareMessage().getDigest().toByteArray());
            assertArrayEquals(clientReq.toByteArray(), sent.getRequest().toByteArray());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void attemptPrePrepare_doesNotSend_whenNotPrimary() {
        Config.initialize("src/test/resources/config.properties");
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "state-manager-test"));
        try {
            ServerState state = new ServerState("n1", false, exec);
            // Set view whose primary is n0 so n1 is not primary
            state.setViewAndPrimary(0L);

            CapturingPrePrepareSender sender = new CapturingPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
            sender.setActive(true);

            sender.attemptPrePrepare(MessageServiceOuterClass.ClientRequest.newBuilder().build());
            assertNull(sender.captured);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void attemptPrePrepare_doesNotSend_whenFaulty() {
        Config.initialize("src/test/resources/config.properties");
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "state-manager-test"));
        try {
            ServerState state = new ServerState("n1", false, exec);
            state.setViewAndPrimary(1L); // n1 primary
            state.setFaulty(true);

            CapturingPrePrepareSender sender = new CapturingPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
            sender.setActive(true);

            sender.attemptPrePrepare(MessageServiceOuterClass.ClientRequest.newBuilder().build());
            assertNull(sender.captured);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void attemptPrePrepare_doesNotSend_whenInactive() {
        Config.initialize("src/test/resources/config.properties");
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "state-manager-test"));
        try {
            ServerState state = new ServerState("n1", false, exec);
            state.setViewAndPrimary(1L); // n1 primary

            CapturingPrePrepareSender sender = new CapturingPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
            sender.setActive(false);

            sender.attemptPrePrepare(MessageServiceOuterClass.ClientRequest.newBuilder().build());
            assertNull(sender.captured);
        } finally {
            exec.shutdownNow();
        }
    }
}
