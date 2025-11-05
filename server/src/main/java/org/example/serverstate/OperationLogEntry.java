package org.example.serverstate;

import org.example.MessageServiceOuterClass;

public class OperationLogEntry {
    private MessageServiceOuterClass.ClientRequest request;
    private OperationStatus status;

    public OperationLogEntry(MessageServiceOuterClass.ClientRequest request, OperationStatus status) {
        this.request = request;
        this.status = status;
    }

    public MessageServiceOuterClass.ClientRequest getRequest() {
        return request;
    }

    public void setRequest(MessageServiceOuterClass.ClientRequest request) {
        this.request = request;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public void setStatus(OperationStatus status) {
        this.status = status;
    }

    private String printOperation() {
        if (request == null || request.getOperation() == null) {
            return "null";
        }
        if (request.getOperation().hasTransfer()) {
            String sender = request.getOperation().getTransfer().getSender();
            String receiver = request.getOperation().getTransfer().getReceiver();
            double amount = request.getOperation().getTransfer().getAmount();
            return String.format("%s (%s, %s, %.1f)", request.getOperation().getOpCase(), sender, receiver, amount);
        } else if (request.getOperation().hasBalanceRequest()) {
            String accountId = request.getOperation().getBalanceRequest().getAccountId();
            return String.format("%s (%s)", request.getOperation().getOpCase(), accountId);
        } else {
            return "unknown operation";
        }
    }

    @Override
    public String toString() {
        return "OperationLogEntry{request=" + printOperation() + ", status=" + status + "}";
    }
}

