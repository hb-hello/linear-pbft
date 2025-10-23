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
        add(String.format("<REQUEST, (%s, %s, %f), %d, %s> received from client %s",
                        request.getTransaction().getSender(),
                        request.getTransaction().getReceiver(),
                        request.getTransaction().getAmount(),
                        request.getTimestamp(),
                        request.getClientId(),
                        request.getClientId()));
    }

    public void clearLogs() {
        logs.clear();
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }
}
