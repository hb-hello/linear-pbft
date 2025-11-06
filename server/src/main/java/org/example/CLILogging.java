package org.example;

import com.google.protobuf.Message;
import org.example.messaging.MessageUtil;
import org.example.serverstate.OperationStatus;

import java.util.List;

public class CLILogging {

    public static String formatLog(List<String> communicationLog) {
        // create prettified string out of list communicationLog
        StringBuilder sb = new StringBuilder();
        for (String logEntry : communicationLog) {
            sb.append(logEntry).append("\n");
        }
        return sb.toString();
    }

//    public static String formatState(ClientState state) {
//        // create JSON string out of hashmap state.getClientState() using jackson
//
//        ObjectMapper objectMapper = new ObjectMapper();
//        try {
//            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state.getClientState());
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }

    public static String mapStatus(OperationStatus status) {
        return switch (status) {
            case PREPREPARED -> "PP";
            case PREPARED -> "P";
            case COMMITTED -> "C";
            case EXECUTED, CHECKPOINTED -> "E";
            default -> "X";
        };
    }

    public static String formatNewViews(List<Message> newViews) {
        StringBuilder sb = new StringBuilder();
        for (MessageServiceOuterClass.NewViewMessage newView : newViews.stream().map(m -> (MessageServiceOuterClass.NewViewMessage) m).toList()) {
            sb.append(String.format("\n\nNewView: view=%s\n",
                    newView.getViewNumber()));
            for (MessageServiceOuterClass.PrePrepareMessage prePrepareMessage : newView.getPrePrepareMessagesList()) {
                sb.append(String.format("  PrePrepare: view=%s, seq=%s, digest=%s\n",
                        prePrepareMessage.getViewNumber(),
                        prePrepareMessage.getSequenceNumber(),
                        MessageUtil.digestToString(prePrepareMessage.getDigest().toByteArray())));
            }
        }
        return sb.toString();
    }
}
