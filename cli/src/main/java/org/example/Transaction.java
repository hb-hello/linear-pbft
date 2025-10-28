package org.example;

public record Transaction(String sender, String receiver, double amount) implements TransactionEvent {

    @Override
    public String getEventType() {
        return "TRANSACTION";
    }

    @Override
    public String toString() {
        return String.format("Transaction(%s -> %s: %.0f)", sender, receiver, amount);
    }

    public MessageServiceOuterClass.Transfer toProtoTransaction() {
        return MessageServiceOuterClass.Transfer.newBuilder()
                .setSender(sender)
                .setReceiver(receiver)
                .setAmount(amount)
                .build();
    }

    public static Transaction fromProtoTransaction(MessageServiceOuterClass.Transfer proto) {
        return new Transaction(proto.getSender(), proto.getReceiver(), proto.getAmount());
    }
}
