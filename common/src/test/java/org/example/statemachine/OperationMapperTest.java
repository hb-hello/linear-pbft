package org.example.statemachine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperationMapperTest {

    @Test
    void roundTrip_transfer_operation() {
        TransferOp input = new TransferOp("A", "B", 10.5);

        // toProto
        org.example.MessageServiceOuterClass.Operation proto = OperationMapper.toProto(input);
        assertEquals(org.example.MessageServiceOuterClass.Operation.OpCase.TRANSFER, proto.getOpCase(), "oneof case should be TRANSFER");
        assertEquals("A", proto.getTransfer().getSender());
        assertEquals("B", proto.getTransfer().getReceiver());
        assertEquals(10.5, proto.getTransfer().getAmount(), 1e-9);

        // fromProto
        StateMachineOperation roundTripped = OperationMapper.fromProto(proto);
        assertTrue(roundTripped instanceof TransferOp, "Should map back to TransferOp");
        TransferOp out = (TransferOp) roundTripped;
        assertEquals("A", out.sender());
        assertEquals("B", out.receiver());
        assertEquals(10.5, out.amount(), 1e-9);
    }

    @Test
    void roundTrip_balanceRequest_operation() {
        BalanceRequestOp input = new BalanceRequestOp("clientA");

        // toProto
        org.example.MessageServiceOuterClass.Operation proto = OperationMapper.toProto(input);
        assertEquals(org.example.MessageServiceOuterClass.Operation.OpCase.BALANCE_REQUEST, proto.getOpCase(), "oneof case should be BALANCE_REQUEST");
        assertEquals("clientA", proto.getBalanceRequest().getAccountId());

        // fromProto
        StateMachineOperation roundTripped = OperationMapper.fromProto(proto);
        assertTrue(roundTripped instanceof BalanceRequestOp, "Should map back to BalanceRequestOp");
        BalanceRequestOp out = (BalanceRequestOp) roundTripped;
        assertEquals("clientA", out.accountId());
    }

    @Test
    void fromProto_throws_on_unset_oneof() {
        org.example.MessageServiceOuterClass.Operation unset = org.example.MessageServiceOuterClass.Operation.newBuilder().build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> OperationMapper.fromProto(unset));
        assertTrue(ex.getMessage().toLowerCase().contains("not set"));
    }
}
