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

    // Typed generators that return the formatted string instead of directly logging.
    public String generateString(MessageServiceOuterClass.ClientRequest request, boolean sending) {
        if (request == null) return null;

        String action = sending ? "sent" : String.format("received from client %s", request.getClientId());

        switch(request.getOperation().getOpCase()) {
            case TRANSFER:
                MessageServiceOuterClass.Transfer transfer = request.getOperation().getTransfer();
                return String.format("<REQUEST, TRANSFER (%s -> %s, %f), %d, %s> %s",
                        transfer.getSender(),
                        transfer.getReceiver(),
                        transfer.getAmount(),
                        request.getTimestamp(),
                        request.getClientId(),
                        action);
            case BALANCE_REQUEST:
                MessageServiceOuterClass.BalanceRequest balanceRequest = request.getOperation().getBalanceRequest();
                return String.format("<REQUEST, BALANCE_REQUEST (%s), %d, %s> %s",
                        balanceRequest.getAccountId(),
                        request.getTimestamp(),
                        request.getClientId(),
                        action);
            case OP_NOT_SET:
                return String.format("<REQUEST, UNKNOWN OPERATION, %d, %s> %s",
                            request.getTimestamp(),
                            request.getClientId(),
                            action);
            default:
                return null;
        }
    }

    public String generateString(MessageServiceOuterClass.ClientReply reply, boolean sending) {
        if (reply == null) return null;

        String action = sending
                ? String.format("sent to client %s", reply.getClientId())
                : String.format("received from server %s", reply.getServerId());

        switch (reply.getResult().getOpCase()) {
            case BALANCE:
                double balance = reply.getResult().getBalance();
                return String.format("<REPLY, BALANCE (%f), %d, %s> %s",
                        balance,
                        reply.getTimestamp(),
                        reply.getClientId(),
                        action);
            case RESULT:
                boolean result = reply.getResult().getResult();
                return String.format("<REPLY, RESULT (%b), %d, %s> %s",
                        result,
                        reply.getTimestamp(),
                        reply.getClientId(),
                        action);
            case OP_NOT_SET:
                return String.format("<REPLY, UNKNOWN RESULT, %d, %s> %s",
                        reply.getTimestamp(),
                        reply.getClientId(),
                        action);
            default:
                return null;
        }
    }

    // New generic generator that dispatches to typed generators.
    public String generateString(Message msg, boolean sending) {
        if (msg == null) return null;

        if (msg instanceof MessageServiceOuterClass.ClientReply) {
            return generateString((MessageServiceOuterClass.ClientReply) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.ClientRequest) {
            return generateString((MessageServiceOuterClass.ClientRequest) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.PrePrepareRequest) {
            return generateString(((MessageServiceOuterClass.PrePrepareRequest) msg).getPrePrepareMessage(), sending);
        }
        if (msg instanceof MessageServiceOuterClass.PrePrepareMessage) {
            return generateString((MessageServiceOuterClass.PrePrepareMessage) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.PrepareMessage) {
            return generateString((MessageServiceOuterClass.PrepareMessage) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.CommitMessage) {
            return generateString((MessageServiceOuterClass.CommitMessage) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.CheckpointMessage) {
            return generateString((MessageServiceOuterClass.CheckpointMessage) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.ViewChangeMessage) {
            return generateString((MessageServiceOuterClass.ViewChangeMessage) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.NewViewMessage) {
            return generateString((MessageServiceOuterClass.NewViewMessage) msg, sending);
        }
        if (msg instanceof MessageServiceOuterClass.StateMessage) {
            return generateString((MessageServiceOuterClass.StateMessage) msg, sending);
        }

        return null;
    }

    // New overload that annotates generated messages with targetNodeId when sending
    public String generateString(Message msg, boolean sending, String targetNodeId) {
        if (msg == null) return null;

        // If we're sending and a target is provided, build explicit strings for server-to-server types
        if (sending && targetNodeId != null && !targetNodeId.isEmpty()) {
            // PrePrepare
            if (msg instanceof MessageServiceOuterClass.PrePrepareMessage) {
                MessageServiceOuterClass.PrePrepareMessage m = (MessageServiceOuterClass.PrePrepareMessage) msg;
                return String.format("MESSAGE: <PRE-PREPARE, %d, %d> sent to %s",
                        m.getViewNumber(),
                        m.getSequenceNumber(),
                        targetNodeId);
            }
            // Prepare
            if (msg instanceof MessageServiceOuterClass.PrepareMessage) {
                MessageServiceOuterClass.PrepareMessage m = (MessageServiceOuterClass.PrepareMessage) msg;
                return String.format("MESSAGE: <PREPARE, <%d, %d>> sent to %s",
                        m.getViewNumber(),
                        m.getSequenceNumber(),
                        targetNodeId);
            }
            // Commit
            if (msg instanceof MessageServiceOuterClass.CommitMessage) {
                MessageServiceOuterClass.CommitMessage m = (MessageServiceOuterClass.CommitMessage) msg;
                return String.format("MESSAGE: <COMMIT, %d, %d> sent to %s",
                        m.getViewNumber(),
                        m.getSequenceNumber(),
                        targetNodeId);
            }
            // Checkpoint
            if (msg instanceof MessageServiceOuterClass.CheckpointMessage) {
                MessageServiceOuterClass.CheckpointMessage m = (MessageServiceOuterClass.CheckpointMessage) msg;
                return String.format("MESSAGE: <CHECKPOINT, %d> sent to %s",
                        m.getSequenceNumber(),
                        targetNodeId);
            }
            // ViewChange
            if (msg instanceof MessageServiceOuterClass.ViewChangeMessage) {
                MessageServiceOuterClass.ViewChangeMessage m = (MessageServiceOuterClass.ViewChangeMessage) msg;
                return String.format("MESSAGE: <VIEW CHANGE, %d, %d, C(%d), P(%d)> sent to %s",
                        m.getViewNumber(),
                        m.getLastStableSequenceNumber(),
                        m.getCheckpointMessagesCount(),
                        m.getPreparedCertificatesCount(),
                        targetNodeId);
            }
            // NewView
            if (msg instanceof MessageServiceOuterClass.NewViewMessage) {
                MessageServiceOuterClass.NewViewMessage m = (MessageServiceOuterClass.NewViewMessage) msg;
                return String.format("MESSAGE: <NEW VIEW, %d, V(%d), P(%d)> sent to %s",
                        m.getViewNumber(),
                        m.getViewChangeMessagesCount(),
                        m.getPrePrepareMessagesCount(),
                        targetNodeId);
            }
            // StateMessage
            if (msg instanceof MessageServiceOuterClass.StateMessage) {
                MessageServiceOuterClass.StateMessage m = (MessageServiceOuterClass.StateMessage) msg;
                return String.format("MESSAGE: <%s> sent to %s from %s",
                        m.getDescriptorForType().getName(),
                        targetNodeId,
                        m.getSignerId());
            }
            // Client messages or fallback - annotate generated base if available
            String base = generateString(msg, sending);
            if (base != null && !base.isEmpty()) {
                if (base.contains("sent to")) return base; // already annotated
                if (base.contains("sent")) return base.replaceFirst("sent", "sent to " + targetNodeId);
                return base + " sent to " + targetNodeId;
            }

            String descriptor = msg.getDescriptorForType() != null ? msg.getDescriptorForType().getName() : msg.getClass().getSimpleName();
            return String.format("MESSAGE: <%s> sent to %s", descriptor, targetNodeId);
        }

        // Otherwise fall back to the base generator
        return generateString(msg, sending);
    }

    // Generic dispatcher that accepts any protobuf Message and a `sending` flag, routing to the
    // specific typed generators when possible. For messages without a specific generator, it will
    // log a simple descriptor-based entry indicating send/receive.
    public void add(Message msg, boolean sending) {
        if (msg == null) return;

        String generated = generateString(msg, sending);

        if (generated != null && !generated.isEmpty()) {
            add(generated);
            return;
        }

        // Fallback generic log
//        String action = sending ? "sent" : "received";
//        String descriptor = msg.getDescriptorForType() != null ? msg.getDescriptorForType().getName() : msg.getClass().getSimpleName();
//        add(String.format("MESSAGE: <%s> %s", descriptor, action));
    }

    // Overload that accepts a targetNodeId for sending cases. When sending is true and
    // targetNodeId is provided, it will include the target in the generic descriptor log.
    public void add(Message msg, boolean sending, String targetNodeId) {
        if (msg == null) return;

        String generated = generateString(msg, sending, targetNodeId);

        if (generated != null && !generated.isEmpty()) {
            add(generated);
            return;
        }

        String action = sending ? "sent" : "received";
        String descriptor = msg.getDescriptorForType() != null ? msg.getDescriptorForType().getName() : msg.getClass().getSimpleName();
        add(String.format("MESSAGE: <%s> %s", descriptor, action));
    }

    // Typed generators for server-to-server messages
    public String generateString(MessageServiceOuterClass.PrePrepareMessage msg, boolean sending) {
        if (msg == null) return null;
        // Keep action separate from signer id to avoid duplicating the signer in logs
        String action = sending ? "sent" : "received from server";
        return String.format("MESSAGE: <PRE-PREPARE, %d, %d> %s %s",
                msg.getViewNumber(),
                msg.getSequenceNumber(),
                action,
                msg.getSignerId());
    }

    public String generateString(MessageServiceOuterClass.PrepareMessage msg, boolean sending) {
        if (msg == null) return null;
        String action = sending ? "sent" : "received from server";
        return String.format("MESSAGE: <PREPARE, <%d, %d>> %s %s",
                msg.getViewNumber(),
                msg.getSequenceNumber(),
                action,
                msg.getSignerId());
    }

    public String generateString(MessageServiceOuterClass.CommitMessage msg, boolean sending) {
        if (msg == null) return null;
        String action = sending ? "sent" : "received from server";
        return String.format("MESSAGE: <COMMIT, %d, %d> %s %s",
                msg.getViewNumber(),
                msg.getSequenceNumber(),
                action,
                msg.getSignerId());
    }

    public String generateString(MessageServiceOuterClass.CheckpointMessage msg, boolean sending) {
        if (msg == null) return null;
        String action = sending ? "sent" : "received from server";
        return String.format("MESSAGE: <CHECKPOINT, %d> %s %s",
                msg.getSequenceNumber(),
                action,
                msg.getSignerId());
    }

    public String generateString(MessageServiceOuterClass.ViewChangeMessage msg, boolean sending) {
        if (msg == null) return null;
        String action = sending ? "sent" : "received from server";
        // Don't embed signerId inside the angle brackets — keep it once after the action
        return String.format("MESSAGE: <VIEW CHANGE, %d, %d, C, P> %s %s",
                msg.getViewNumber(),
                msg.getLastStableSequenceNumber(),
                action,
                msg.getSignerId());
    }

    public String generateString(MessageServiceOuterClass.NewViewMessage msg, boolean sending) {
        if (msg == null) return null;
        String action = sending ? "sent" : "received from server";
        return String.format("MESSAGE: <NEW VIEW, %d> %s %s",
                msg.getViewNumber(),
                action,
                msg.getSignerId());
    }

    public String generateString(MessageServiceOuterClass.StateMessage msg, boolean sending) {
        if (msg == null) return null;
        String action = sending ? "sent" : "received";
        return String.format("MESSAGE: <%s> %s from %s",
                msg.getDescriptorForType().getName(),
                action,
                msg.getSignerId());
    }

    public List<String> getLogs() {
        return logs;
    }

    public void reset() {
        logs.clear();
    }
}
