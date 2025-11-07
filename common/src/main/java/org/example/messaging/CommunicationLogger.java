package org.example.messaging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.lang.System.currentTimeMillis;

public class CommunicationLogger {

    private static final Logger logger = LogManager.getLogger(CommunicationLogger.class);

    private final List<String> logs;

    public CommunicationLogger() {
        this.logs = Collections.synchronizedList(new ArrayList<>());
    }

    public void add(String message) {
        //add ISO timestamp to start of message
        logger.info(message);
        String messageWithTimestamp = String.format("[%tFT%<tT.%<tLZ] - %s", currentTimeMillis(), message);
        logs.add(messageWithTimestamp);
    }

    public void add(MessageServiceOuterClass.ClientRequest request) {

        switch(request.getOperation().getOpCase()) {
            case TRANSFER:
                MessageServiceOuterClass.Transfer transfer = request.getOperation().getTransfer();
                add(String.format("<REQUEST, TRANSFER (%s -> %s, %f), %d, %s> received from client %s",
                        transfer.getSender(),
                        transfer.getReceiver(),
                        transfer.getAmount(),
                        request.getTimestamp(),
                        request.getClientId(),
                        request.getClientId()));
                break;
            case BALANCE_REQUEST:
                MessageServiceOuterClass.BalanceRequest balanceRequest = request.getOperation().getBalanceRequest();
                add(String.format("<REQUEST, BALANCE_REQUEST (%s), %d, %s> received from client %s",
                        balanceRequest.getAccountId(),
                        request.getTimestamp(),
                        request.getClientId(),
                        request.getClientId()));
                break;
            case OP_NOT_SET:
                add(String.format("<REQUEST, UNKNOWN OPERATION, %d, %s> received from client %s",
                            request.getTimestamp(),
                            request.getClientId(),
                            request.getClientId()));
                break;
            default:
                break;
        }
    }

    public void clearLogs() {
        logs.clear();
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }

    // New overloaded method to log ClientReply messages. The `sending` flag indicates whether
    // this node is sending the reply (true) or receiving the reply (false). The log line mirrors
    // the REQUEST format: "<REPLY, ..., timestamp, clientId> ..." with an action suffix.
    public void add(MessageServiceOuterClass.ClientReply reply, boolean sending) {
        if (reply == null) return;

        String action = sending
                ? String.format("sent to client %s", reply.getClientId())
                : String.format("received from server %s", reply.getServerId());

        switch (reply.getResult().getOpCase()) {
            case BALANCE:
                double balance = reply.getResult().getBalance();
                add(String.format("<REPLY, BALANCE (%f), %d, %s> %s",
                        balance,
                        reply.getTimestamp(),
                        reply.getClientId(),
                        action));
                break;
            case RESULT:
                boolean result = reply.getResult().getResult();
                add(String.format("<REPLY, RESULT (%b), %d, %s> %s",
                        result,
                        reply.getTimestamp(),
                        reply.getClientId(),
                        action));
                break;
            case OP_NOT_SET:
                add(String.format("<REPLY, UNKNOWN RESULT, %d, %s> %s",
                        reply.getTimestamp(),
                        reply.getClientId(),
                        action));
                break;
            default:
                break;
        }
    }

    // Generic dispatcher that accepts any protobuf Message and a `sending` flag, routing to the
    // specific typed add methods when possible. For messages without a specific overload, it will
    // log a simple descriptor-based entry indicating send/receive.
    public void add(Message msg, boolean sending) {
        if (msg == null) return;

        // Client/server messages
        if (msg instanceof MessageServiceOuterClass.ClientReply) {
            add((MessageServiceOuterClass.ClientReply) msg, sending);
            return;
        }

        if (msg instanceof MessageServiceOuterClass.ClientRequest) {
            add((MessageServiceOuterClass.ClientRequest) msg);
            return;
        }

        if (msg instanceof MessageServiceOuterClass.PrePrepareMessage) {
            add((MessageServiceOuterClass.PrePrepareMessage) msg);
            return;
        }

        if (msg instanceof MessageServiceOuterClass.PrePrepareRequest) {
            add(((MessageServiceOuterClass.PrePrepareRequest) msg).getPrePrepareMessage());
            return;
        }

        if (msg instanceof MessageServiceOuterClass.CommitMessage) {
            add((MessageServiceOuterClass.CommitMessage) msg);
            return;
        }

        if (msg instanceof MessageServiceOuterClass.CheckpointMessage) {
            add((MessageServiceOuterClass.CheckpointMessage) msg);
            return;
        }

        if (msg instanceof MessageServiceOuterClass.ViewChangeMessage) {
            add((MessageServiceOuterClass.ViewChangeMessage) msg);
            return;
        }

        if (msg instanceof MessageServiceOuterClass.NewViewMessage) {
            add((MessageServiceOuterClass.NewViewMessage) msg);
            return;
        }

        if (msg instanceof MessageServiceOuterClass.StateMessage) {
            // StateMessage doesn't have a specific add overload; log descriptor + action
            String action = sending ? "sent" : "received";
            MessageServiceOuterClass.StateMessage sm = (MessageServiceOuterClass.StateMessage) msg;
            add(String.format("MESSAGE: <%s> %s from %s",
                    msg.getDescriptorForType().getName(),
                    action,
                    sm.getSignerId()));
            return;
        }

        // Fallback generic log
        String action = sending ? "sent" : "received";
        String descriptor = msg.getDescriptorForType() != null ? msg.getDescriptorForType().getName() : msg.getClass().getSimpleName();
        add(String.format("MESSAGE: <%s> %s", descriptor, action));
    }

    // Overloaded loggers for server-to-server messages so callers can pass messages directly
    public void add(MessageServiceOuterClass.PrePrepareMessage msg) {
        if (msg == null) return;
        add(String.format("MESSAGE: <PRE-PREPARE, %d, %d> received from server %s",
                msg.getViewNumber(),
                msg.getSequenceNumber(),
                msg.getSignerId()));
    }

    public void add(MessageServiceOuterClass.PrepareMessage msg) {
        if (msg == null) return;
        add(String.format("MESSAGE: <PREPARE, <%d, %d, d>> received from server %s",
                msg.getViewNumber(),
                msg.getSequenceNumber(),
                msg.getSignerId()));
    }

    public void add(MessageServiceOuterClass.CommitMessage msg) {
        if (msg == null) return;
        add(String.format("MESSAGE: <COMMIT, %d, %d, d> received from server %s",
                msg.getViewNumber(),
                msg.getSequenceNumber(),
                msg.getSignerId()));
    }

    public void add(MessageServiceOuterClass.CheckpointMessage msg) {
        if (msg == null) return;
        add(String.format("MESSAGE: <CHECKPOINT, %d> received from server %s",
                msg.getSequenceNumber(),
                msg.getSignerId()));
    }

    public void add(MessageServiceOuterClass.ViewChangeMessage msg) {
        if (msg == null) return;
        add(String.format("MESSAGE: <VIEW CHANGE, %d, %d, C, P, %s> received from server %s",
                msg.getViewNumber(),
                msg.getLastStableSequenceNumber(),
                msg.getSignerId(),
                msg.getSignerId()));
    }

    public void add(MessageServiceOuterClass.NewViewMessage msg) {
        if (msg == null) return;
        add(String.format("MESSAGE: <NEW VIEW, %d> received from server %s",
                msg.getViewNumber(),
                msg.getSignerId()));
    }
}
