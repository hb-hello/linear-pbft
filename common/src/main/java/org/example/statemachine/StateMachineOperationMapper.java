package org.example.statemachine;

/**
 * Utility for converting between sealed StateMachineOperation values and protobuf Operation messages.
 */
public final class StateMachineOperationMapper {
    private StateMachineOperationMapper() {}

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
        org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(StateMachineOperationMapper.class);
        logger.info("fromProto: Converting operation, opCase: {}", op.getOpCase());

        return switch (op.getOpCase()) {
            case TRANSFER -> {
                var t = op.getTransfer();
                logger.info("fromProto: TRANSFER - sender='{}', receiver='{}', amount={}",
                    t.getSender(), t.getReceiver(), t.getAmount());
                yield new TransferOp(t.getSender(), t.getReceiver(), t.getAmount());
            }
            case BALANCE_REQUEST -> {
                String accountId = op.getBalanceRequest().getAccountId();
                logger.info("fromProto: BALANCE_REQUEST - accountId='{}'", accountId);
                yield new BalanceRequestOp(accountId);
            }
            case OP_NOT_SET -> {
                logger.error("fromProto: Operation.oneof 'op' not set");
                throw new IllegalArgumentException("Operation.oneof 'op' not set");
            }
        };
    }
}
