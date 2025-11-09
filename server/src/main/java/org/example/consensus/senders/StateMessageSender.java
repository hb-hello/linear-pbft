package org.example.consensus.senders;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MaliceInjector;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.MessageUtil;
import org.example.serverstate.ServerState;
import org.example.statemachine.BankStateMachine;

import java.util.concurrent.ExecutorService;

public class StateMessageSender  extends MessageSender {
    private static final Logger logger = LogManager.getLogger(StateMessageSender.class);

    private final ServerState state;

    public StateMessageSender(String serverId, ServerState state,
                         CommunicationLogger commLogger, MessageAuthenticator auth, ExecutorService networkExecutor) {
        super(serverId, commLogger, auth, networkExecutor);
        this.state = state;
    }

    public void sendStateMessage(String targetServerId) {
        logger.info("Sending state message to server {}", targetServerId);

        Object snapshot = state.getLatestStableCheckpointSnapshot();
        byte[] digest = state.getLatestStableCheckpointSnapshotDigest();

        if (snapshot == null) {
            logger.warn("No stable checkpoint snapshot available to send to server {}", targetServerId);
            return;
        }

        MessageServiceOuterClass.StateMessage stateMessage = MessageServiceOuterClass.StateMessage.newBuilder()
                .putAllStateSnapshot(BankStateMachine.convertSnapshot(snapshot))
                .setDigest(ByteString.copyFrom(digest))
                .build();

        signAndSend(targetServerId, state.isPrimary(), stateMessage, (stub, signed) -> stub.stateResponse((MessageServiceOuterClass.StateMessage) signed));
        logger.info("Sent state message to server {}", targetServerId);
    }
}
