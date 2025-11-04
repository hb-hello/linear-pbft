package org.example.serverstate;

import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class StateMachineOperatorTest {

    private static ExecutorService stateExec;
    private ServerState state;
    private StateMachineOperator operator;

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
    void setupOperator() {
        // Pass a no-op callback for testing - replies aren't actually sent in unit tests
        state = new ServerState("n1", false, stateExec, (request, reply) -> {});
        operator = new StateMachineOperator(state, (request, reply) -> { });
    }

    private MessageServiceOuterClass.ClientRequest createTransferRequest(String clientId, long timestamp,
                                                                          String from, String to, double amount) {
        return MessageServiceOuterClass.ClientRequest.newBuilder()
                .setClientId(clientId)
                .setTimestamp(timestamp)
                .setOperation(MessageServiceOuterClass.Operation.newBuilder()
                        .setTransfer(MessageServiceOuterClass.Transfer.newBuilder()
                                .setSender(from)
                                .setReceiver(to)
                                .setAmount(amount)
                                .build())
                        .build())
                .build();
    }

    private MessageServiceOuterClass.ClientRequest createBalanceRequest(String clientId, long timestamp, String accountId) {
        return MessageServiceOuterClass.ClientRequest.newBuilder()
                .setClientId(clientId)
                .setTimestamp(timestamp)
                .setOperation(MessageServiceOuterClass.Operation.newBuilder()
                        .setBalanceRequest(MessageServiceOuterClass.BalanceRequest.newBuilder()
                                .setAccountId(accountId)
                                .build())
                        .build())
                .build();
    }

    @Test
    void testExecuteOperation_firstRequest_executesSuccessfully() throws Exception {
        MessageServiceOuterClass.ClientRequest request = createTransferRequest("client1", 100L, "A", "B", 5.0);

        MessageServiceOuterClass.ClientReply reply = operator.executeOperation(request, 1L).get();

        assertNotNull(reply, "Reply should not be null for first execution");
        assertEquals("client1", reply.getClientId());
        assertEquals(100L, reply.getTimestamp());
        assertEquals("n1", reply.getServerId());
        assertTrue(reply.hasResult());
    }

    @Test
    void testExecuteOperation_sameSequenceNumber_doesNotExecuteTwice() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);
        MessageServiceOuterClass.ClientRequest request2 = createTransferRequest("client2", 200L, "A", "B", 10.0);

        // Execute first time
        MessageServiceOuterClass.ClientReply reply1 = operator.executeOperation(request1, 1L).get();
        assertNotNull(reply1, "First execution should return a reply");
        assertTrue(reply1.getResult().getResult(), "First transfer should succeed");

        // Try to execute again with same sequence number
        MessageServiceOuterClass.ClientReply reply2 = operator.executeOperation(request2, 1L).get();
        assertNull(reply2, "Second execution with same seqNum should return null");
    }

    @Test
    void testExecuteOperation_lowerSequenceNumber_doesNotExecute() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);
        MessageServiceOuterClass.ClientRequest request2 = createTransferRequest("client2", 200L, "A", "B", 10.0);

        // Execute seqNum 3
        operator.executeOperation(request1, 3L).get();
        operator.executeOperation(request1, 1L).get();
        operator.executeOperation(request1, 2L).get();

        // Try to execute seqNum 2 (lower than last executed)
        MessageServiceOuterClass.ClientReply reply = operator.executeOperation(request2, 2L).get();
        assertNull(reply, "Operation with lower seqNum should return null");
    }

    @Test
    void testExecuteOperation_outOfOrder_pendingExecution() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);

        // Try to execute seqNum 3 without executing 1 and 2 first
        MessageServiceOuterClass.ClientReply reply = operator.executeOperation(request1, 3L).get();

        assertNull(reply, "Out-of-order operation should return null (pending)");
    }

    @Test
    void testExecuteOperation_sequentialExecution_allExecuted() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);
        MessageServiceOuterClass.ClientRequest request2 = createTransferRequest("client2", 200L, "B", "C", 3.0);
        MessageServiceOuterClass.ClientRequest request3 = createTransferRequest("client3", 300L, "C", "A", 2.0);

        MessageServiceOuterClass.ClientReply reply1 = operator.executeOperation(request1, 1L).get();
        assertNotNull(reply1, "First operation should execute");

        MessageServiceOuterClass.ClientReply reply2 = operator.executeOperation(request2, 2L).get();
        assertNotNull(reply2, "Second operation should execute");

        MessageServiceOuterClass.ClientReply reply3 = operator.executeOperation(request3, 3L).get();
        assertNotNull(reply3, "Third operation should execute");
    }

    @Test
    void testExecuteOperation_outOfOrderThenFillGap_executesAll() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);
        MessageServiceOuterClass.ClientRequest request2 = createTransferRequest("client2", 200L, "B", "C", 3.0);
        MessageServiceOuterClass.ClientRequest request3 = createTransferRequest("client3", 300L, "C", "A", 2.0);

        // Execute seqNum 3 first (out of order - should be pending)
        MessageServiceOuterClass.ClientReply reply3 = operator.executeOperation(request3, 3L).get();
        assertNull(reply3, "Out-of-order operation should return null");

        // Execute seqNum 1 (should succeed)
        MessageServiceOuterClass.ClientReply reply1 = operator.executeOperation(request1, 1L).get();
        assertNotNull(reply1, "First operation should execute");

        // Execute seqNum 2 (should succeed and trigger pending operations)
        MessageServiceOuterClass.ClientReply reply2 = operator.executeOperation(request2, 2L).get();
        assertNotNull(reply2, "Second operation should execute");

        // At this point, seqNum 3 should have been executed automatically
        // Try to execute it again - should return null because it was already executed
        MessageServiceOuterClass.ClientReply reply3Again = operator.executeOperation(request3, 3L).get();
        assertNull(reply3Again, "Operation already executed should return null");
    }

    @Test
    void testExecuteOperation_multipleOutOfOrderThenFillGaps_executesAll() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 1.0);
        MessageServiceOuterClass.ClientRequest request2 = createTransferRequest("client2", 200L, "B", "C", 1.0);
        MessageServiceOuterClass.ClientRequest request3 = createTransferRequest("client3", 300L, "C", "D", 1.0);
        MessageServiceOuterClass.ClientRequest request4 = createTransferRequest("client4", 400L, "D", "E", 1.0);
        MessageServiceOuterClass.ClientRequest request5 = createTransferRequest("client5", 500L, "E", "A", 1.0);

        // Execute out of order: 3, 5, 1, 4, 2
        assertNull(operator.executeOperation(request3, 3L).get(), "SeqNum 3 should be pending");
        assertNull(operator.executeOperation(request5, 5L).get(), "SeqNum 5 should be pending");

        assertNotNull(operator.executeOperation(request1, 1L).get(), "SeqNum 1 should execute");

        assertNull(operator.executeOperation(request4, 4L).get(), "SeqNum 4 should be pending");

        // Executing seqNum 2 should trigger execution of 3, 4, and 5
        assertNotNull(operator.executeOperation(request2, 2L).get(), "SeqNum 2 should execute");

        // All should now be executed - trying to execute any should return null
        assertNull(operator.executeOperation(request3, 3L).get(), "SeqNum 3 should already be executed");
        assertNull(operator.executeOperation(request4, 4L).get(), "SeqNum 4 should already be executed");
        assertNull(operator.executeOperation(request5, 5L).get(), "SeqNum 5 should already be executed");
    }

    @Test
    void testExecuteOperation_duplicateAttemptsDifferentRequests_onlyFirstExecutes() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);
        MessageServiceOuterClass.ClientRequest request2 = createTransferRequest("client2", 200L, "A", "B", 50.0);

        // Execute first request with seqNum 1
        MessageServiceOuterClass.ClientReply reply1 = operator.executeOperation(request1, 1L).get();
        assertNotNull(reply1, "First execution should succeed");
        assertEquals("client1", reply1.getClientId());

        // Try to execute different request with same seqNum 1
        MessageServiceOuterClass.ClientReply reply2 = operator.executeOperation(request2, 1L).get();
        assertNull(reply2, "Duplicate seqNum should not execute even with different request");
    }

    @Test
    void testMarkExecutedUpTo_advancesExecution() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);
        MessageServiceOuterClass.ClientRequest request2 = createTransferRequest("client2", 200L, "B", "C", 3.0);

        // Execute seqNum 1
        assertNotNull(operator.executeOperation(request1, 1L).get(), "SeqNum 1 should execute");

        // Manually mark as executed up to 5
        operator.markExecutedUpTo(5L);

        // Now seqNum 2 should not execute because it's <= lastExecutedSeqNum
        assertNull(operator.executeOperation(request2, 2L).get(), "SeqNum 2 should not execute after marking up to 5");

        // But seqNum 6 should execute
        MessageServiceOuterClass.ClientRequest request6 = createTransferRequest("client6", 600L, "A", "B", 1.0);
        assertNotNull(operator.executeOperation(request6, 6L).get(), "SeqNum 6 should execute");
    }

    @Test
    void testReset_clearsExecutionState() throws Exception {
        MessageServiceOuterClass.ClientRequest request1 = createTransferRequest("client1", 100L, "A", "B", 5.0);

        // Execute an operation
        operator.executeOperation(request1, 1L).get();

        // Reset
        operator.reset();

        // Now seqNum 1 should be executable again
        MessageServiceOuterClass.ClientReply reply = operator.executeOperation(request1, 1L).get();
        assertNotNull(reply, "After reset, seqNum 1 should be executable again");
    }

    @Test
    void testExecuteOperation_verifyStateChanges() throws Exception {
        // Get initial balance of A
        double initialBalanceA = Config.getClientBalances().get("A");
        double initialBalanceB = Config.getClientBalances().get("B");

        // Execute a transfer
        MessageServiceOuterClass.ClientRequest transferRequest = createTransferRequest("client1", 100L, "A", "B", 5.0);
        MessageServiceOuterClass.ClientReply transferReply = operator.executeOperation(transferRequest, 1L).get();

        assertNotNull(transferReply, "Transfer should execute");
        assertTrue(transferReply.getResult().getResult(), "Transfer should succeed");

        // Check balances after transfer
        MessageServiceOuterClass.ClientRequest balanceRequestA = createBalanceRequest("client1", 101L, "A");
        MessageServiceOuterClass.ClientReply balanceReplyA = operator.executeOperation(balanceRequestA, 2L).get();

        assertNotNull(balanceReplyA, "Balance request should execute");
        assertEquals(initialBalanceA - 5.0, balanceReplyA.getResult().getBalance(), 0.001,
                     "Balance of A should be reduced by 5");

        MessageServiceOuterClass.ClientRequest balanceRequestB = createBalanceRequest("client1", 102L, "B");
        MessageServiceOuterClass.ClientReply balanceReplyB = operator.executeOperation(balanceRequestB, 3L).get();

        assertNotNull(balanceReplyB, "Balance request should execute");
        assertEquals(initialBalanceB + 5.0, balanceReplyB.getResult().getBalance(), 0.001,
                     "Balance of B should be increased by 5");
    }

    @Test
    void testExecuteOperation_duplicatePreventsDoubleSpending() throws Exception {
        double initialBalanceA = Config.getClientBalances().get("A");
        double initialBalanceB = Config.getClientBalances().get("B");

        // Create a transfer request
        MessageServiceOuterClass.ClientRequest transferRequest = createTransferRequest("client1", 100L, "A", "B", 5.0);

        // Execute once
        MessageServiceOuterClass.ClientReply reply1 = operator.executeOperation(transferRequest, 1L).get();
        assertNotNull(reply1, "First execution should succeed");

        // Try to execute the same seqNum again (simulating duplicate)
        MessageServiceOuterClass.ClientReply reply2 = operator.executeOperation(transferRequest, 1L).get();
        assertNull(reply2, "Duplicate execution should return null");

        // Verify balance was only deducted once
        MessageServiceOuterClass.ClientRequest balanceRequest = createBalanceRequest("client1", 101L, "A");
        MessageServiceOuterClass.ClientReply balanceReply = operator.executeOperation(balanceRequest, 2L).get();

        assertNotNull(balanceReply, "Balance check should execute");
        assertEquals(initialBalanceA - 5.0, balanceReply.getResult().getBalance(), 0.001,
                     "Balance should only be deducted once, not twice");
    }

    @Test
    void testSnapshot_capturesCurrentState() throws Exception {
        // Execute some operations to change state
        MessageServiceOuterClass.ClientRequest request = createTransferRequest("client1", 100L, "A", "B", 10.0);
        operator.executeOperation(request, 1L).get();

        // Take a snapshot
        Object snapshot = operator.snapshot();

        assertNotNull(snapshot, "Snapshot should not be null");
        // The snapshot should be a Map of balances
        assertTrue(snapshot instanceof java.util.Map, "Snapshot should be a Map");
    }
}

