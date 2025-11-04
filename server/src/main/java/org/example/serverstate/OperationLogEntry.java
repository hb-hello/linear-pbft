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
}

