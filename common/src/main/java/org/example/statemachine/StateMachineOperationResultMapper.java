package org.example.statemachine;

public final class StateMachineOperationResultMapper {
    private StateMachineOperationResultMapper() {}

    public static org.example.MessageServiceOuterClass.OperationResult toProto(StateMachineOperationResult result) {
        var builder = org.example.MessageServiceOuterClass.OperationResult.newBuilder();
        if (result.isResult()) {
            builder.setResult(result.result());
        } else if (result.isBalance()) {
            builder.setBalance(result.balance());
        } else {
            throw new IllegalArgumentException("SimpleOperationResult must have exactly one branch set");
        }
        return builder.build();
    }

    public static StateMachineOperationResult fromProto(org.example.MessageServiceOuterClass.OperationResult proto) {
        return switch (proto.getOpCase()) {
            case RESULT -> StateMachineOperationResult.result(proto.getResult());
            case BALANCE -> StateMachineOperationResult.balance(proto.getBalance());
            case OP_NOT_SET -> throw new IllegalArgumentException("OperationResult.oneof 'op' not set");
        };
    }
}

