package org.example.messaging;

import org.example.MessageServiceOuterClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.lang.System.currentTimeMillis;

public class CommunicationLogger {

    private final List<String> logs;

    public CommunicationLogger() {
        this.logs = Collections.synchronizedList(new ArrayList<>());
    }

    public void add(String message) {
        //add ISO timestamp to start of message
        String messageWithTimestamp = String.format("[%tFT%<tT.%<tLZ] - %s", currentTimeMillis(), message);
        logs.add(messageWithTimestamp);
    }

    public void add(MessageServiceOuterClass.ClientRequest request) {

        switch(request.getOperation().getOpCase()) {
            case TRANSFER:
                MessageServiceOuterClass.Transaction transfer = request.getOperation().getTransfer();
                add(String.format("<REQUEST, TRANSFER (%s -> %s, %f), %d, %s> received from client %s",
                        transfer.getSender(),
                        transfer.getReceiver(),
                        transfer.getAmount(),
                        request.getTimestamp(),
                        request.getClientId(),
                        request.getClientId()));
                return;
            case BALANCE_REQUEST:
                MessageServiceOuterClass.BalanceRequest balanceRequest = request.getOperation().getBalanceRequest();
                add(String.format("<REQUEST, BALANCE_REQUEST (%s), %d, %s> received from client %s",
                        balanceRequest.getAccountId(),
                        request.getTimestamp(),
                        request.getClientId(),
                        request.getClientId()));
                return;
            case OP_NOT_SET:
                add(String.format("<REQUEST, UNKNOWN OPERATION, %d, %s> received from client %s",
                            request.getTimestamp(),
                            request.getClientId(),
                            request.getClientId()));
                return;
            default:
                return;
        }
    }

    public void clearLogs() {
        logs.clear();
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }
}
