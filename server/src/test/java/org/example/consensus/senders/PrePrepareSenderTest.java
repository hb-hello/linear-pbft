package org.example.consensus.senders;

import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.serverstate.ServerState;
import org.example.testutil.MockPrePrepareSender;
import org.junit.jupiter.api.*;

import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PrePrepareSenderTest {

    private static ExecutorService stateExec;
    private ServerState state;
    private MockPrePrepareSender sender;

    @BeforeAll
    static void setup() {
        Config.initialize("src/test/resources/config.properties");
        stateExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("-state-manager-0");
            return t;
        });
    }

    @AfterAll
    static void tearDown() {
        if (stateExec != null) {
            stateExec.shutdownNow();
        }
    }

    @BeforeEach
    void setUp() {
        state = new ServerState("n1", false, stateExec);
    }

    @AfterEach
    void tearDownTest() {
        if (sender != null) {
            sender.shutdown();
        }
    }

    @Test
    void attemptPrePrepare_broadcastsWithCorrectFields_whenCanSend() throws Exception {
        // Make this node primary by choosing a view whose primary is n1
        state.setViewAndPrimary(1L);

        sender = new MockPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
        sender.setActive(true);

        MessageServiceOuterClass.ClientRequest clientReq = MessageServiceOuterClass.ClientRequest.newBuilder().build();

        long expectedSeq = state.snapshotHeader().seq() + 1; // nextSeq will be called inside
        long expectedView = state.getViewNumber();

        sender.attemptPrePrepare(clientReq);

        MessageServiceOuterClass.PrePrepareRequest sent = sender.getCapturedRequest();
        assertNotNull(sent);

        byte[] expectedDigest = MessageDigest.getInstance("MD5").digest(clientReq.toByteArray());
        assertEquals(expectedView, sent.getPrePrepareMessage().getViewNumber());
        assertEquals(expectedSeq, sent.getPrePrepareMessage().getSequenceNumber());
        assertArrayEquals(expectedDigest, sent.getPrePrepareMessage().getDigest().toByteArray());
        assertArrayEquals(clientReq.toByteArray(), sent.getRequest().toByteArray());
    }

    @Test
    void attemptPrePrepare_doesNotSend_whenNotPrimary() {
        // Set view whose primary is n0 so n1 is not primary
        state.setViewAndPrimary(0L);

        sender = new MockPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
        sender.setActive(true);

        sender.attemptPrePrepare(MessageServiceOuterClass.ClientRequest.newBuilder().build());
        assertNull(sender.getCapturedRequest());
    }

    @Test
    void attemptPrePrepare_doesNotSend_whenFaulty() {
        state.setViewAndPrimary(1L); // n1 primary
        state.setFaulty(true);

        sender = new MockPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
        sender.setActive(true);

        sender.attemptPrePrepare(MessageServiceOuterClass.ClientRequest.newBuilder().build());
        assertNull(sender.getCapturedRequest());
    }

    @Test
    void attemptPrePrepare_doesNotSend_whenInactive() {
        state.setViewAndPrimary(1L); // n1 primary

        sender = new MockPrePrepareSender("n1", state, new CommunicationLogger(), new MessageAuthenticator("n1"));
        sender.setActive(false);

        sender.attemptPrePrepare(MessageServiceOuterClass.ClientRequest.newBuilder().build());
        assertNull(sender.getCapturedRequest());
    }
}
