package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.MessageUtil;
import org.example.serverstate.ServerState;
import org.example.statemachine.BankStateMachine;

public class StateMessageSender  extends MessageSender {
    private static final Logger logger = LogManager.getLogger(StateMessageSender.class);

    private final ServerState state;

    public StateMessageSender(String serverId, ServerState state,
                         CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
        this.state = state;
    }

    public void sendStateMessage(String targetServerId) {
        logger.info("Sending state message to server {}", targetServerId);

        Object snapshot = state.getLatestStableCheckpointSnapshot();

        if (snapshot == null) {
            logger.warn("No stable checkpoint snapshot available to send to server {}", targetServerId);
            return;
        }

        ByteString digest = ByteString.copyFrom(MessageUtil.generateDigest(snapshot));

        MessageServiceOuterClass.StateMessage stateMessage = MessageServiceOuterClass.StateMessage.newBuilder()
                .putAllStateSnapshot(BankStateMachine.convertSnapshot(snapshot))
                .setDigest(digest)
                .build();

        signAndSend(targetServerId, stateMessage, (stub, signed) -> stub.stateResponse((MessageServiceOuterClass.StateMessage) signed));
        logger.info("Sent state message to server {}", targetServerId);
    }
}
