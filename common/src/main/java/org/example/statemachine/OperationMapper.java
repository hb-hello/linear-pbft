package org.example.statemachine;

/**
 * Utility for converting between sealed StateMachineOperation values and protobuf Operation messages.
 */
public final class OperationMapper {
    private OperationMapper() {}

    public static org.example.MessageServiceOuterClass.Operation toProto(StateMachineOperation op) {
        return switch (op) {
            case TransferOp t -> org.example.MessageServiceOuterClass.Operation.newBuilder()
                    .setTransfer(org.example.MessageServiceOuterClass.Transfer.newBuilder()
                            .setSender(t.sender())
                            .setReceiver(t.receiver())
                            .setAmount(t.amount())
                            .build())
                    .build();
            case BalanceRequestOp b -> org.example.MessageServiceOuterClass.Operation.newBuilder()
                    .setBalanceRequest(org.example.MessageServiceOuterClass.BalanceRequest.newBuilder()
                            .setAccountId(b.accountId())
                            .build())
                    .build();
        };
    }

    public static StateMachineOperation fromProto(org.example.MessageServiceOuterClass.Operation op) {
        return switch (op.getOpCase()) {
            case TRANSFER -> {
                var t = op.getTransfer();
                yield new TransferOp(t.getSender(), t.getReceiver(), t.getAmount());
            }
            case BALANCE_REQUEST -> new BalanceRequestOp(op.getBalanceRequest().getAccountId());
            case OP_NOT_SET -> throw new IllegalArgumentException("Operation.oneof 'op' not set");
        };
    }
}
