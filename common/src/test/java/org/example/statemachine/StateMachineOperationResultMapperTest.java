package org.example.statemachine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateMachineOperationResultMapperTest {

    @Test
    void roundTrip_result_true() {
        StateMachineOperationResult input = StateMachineOperationResult.result(true);
        org.example.MessageServiceOuterClass.OperationResult proto = StateMachineOperationResultMapper.toProto(input);
        assertEquals(org.example.MessageServiceOuterClass.OperationResult.OpCase.RESULT, proto.getOpCase());
        assertTrue(proto.getResult());
        StateMachineOperationResult out = StateMachineOperationResultMapper.fromProto(proto);
        assertTrue(out.isResult());
        assertEquals(Boolean.TRUE, out.result());
        assertNull(out.balance());
    }

    @Test
    void roundTrip_balance_value() {
        StateMachineOperationResult input = StateMachineOperationResult.balance(42.0);
        org.example.MessageServiceOuterClass.OperationResult proto = StateMachineOperationResultMapper.toProto(input);
        assertEquals(org.example.MessageServiceOuterClass.OperationResult.OpCase.BALANCE, proto.getOpCase());
        assertEquals(42.0, proto.getBalance(), 1e-9);
        StateMachineOperationResult out = StateMachineOperationResultMapper.fromProto(proto);
        assertTrue(out.isBalance());
        assertEquals(Double.valueOf(42.0), out.balance());
        assertNull(out.result());
    }

    @Test
    void fromProto_throws_on_unset_oneof() {
        org.example.MessageServiceOuterClass.OperationResult unset = org.example.MessageServiceOuterClass.OperationResult.newBuilder().build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> StateMachineOperationResultMapper.fromProto(unset));
        assertTrue(ex.getMessage().toLowerCase().contains("not set"));
    }
}

