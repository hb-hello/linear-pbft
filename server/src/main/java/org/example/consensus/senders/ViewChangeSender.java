package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MaliceInjector;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class ViewChangeSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(ViewChangeSender.class);

    private final int quorumSize;

    public ViewChangeSender(String serverId, int quorumSize,
                         CommunicationLogger commLogger, MessageAuthenticator auth, ExecutorService networkExecutor) {
        super(serverId, commLogger, auth, networkExecutor);
        this.quorumSize = quorumSize;
    }

    public void broadcastViewChange(ServerState state) {
        long currentView = state.getViewNumber();
        long newViewNumber = currentView + 1;
        broadcastViewChange(state, currentView, newViewNumber);
    }

    public void broadcastViewChange(ServerState state, long currentView, long newViewNumber) {

        logger.info("Attempting to broadcast ViewChange message for new view {} with current view {}", newViewNumber, currentView);

        if (!state.isViewChangeInProgress()) {
            logger.info("No view change in progress, not sending ViewChange message");
            return;
        }

        if (newViewNumber < currentView) {
            logger.info("Not sending ViewChange message for view {} because current view is {}",
                    newViewNumber, currentView);
            return;
        }

        logger.info("Preparing ViewChange message for view {}", newViewNumber);

        MessageServiceOuterClass.ViewChangeMessage.Builder viewChangeBuilder = MessageServiceOuterClass.ViewChangeMessage.newBuilder();

        // fetch last stable checkpoint seq num
        long lastStableCheckpointSeqNum = state.getLatestStableCheckpointSeqNum();
        viewChangeBuilder.setLastStableSequenceNumber(lastStableCheckpointSeqNum);

        // fetch checkpoint certificate
        logger.info("Fetching checkpoint messages for view {} and seq num {}",
                state.getViewNumber(), lastStableCheckpointSeqNum);
        List<ServerMessage> checkpointMessages = state.getQuorumMessages(ServerMessage.CHECKPOINT, currentView, lastStableCheckpointSeqNum);
        viewChangeBuilder.addAllCheckpointMessages(checkpointMessages.stream().map((msg) -> (MessageServiceOuterClass.CheckpointMessage) msg.getMessage()).toList());

        // fetch prepared certificate
        logger.info("Fetching prepared certificates between watermarks for view {}",
                state.getViewNumber());
        for (long seqNum : state.getUniqueSeqNumsSeen()) {
            MessageServiceOuterClass.PreparedCertificate preparedCertificate = state.getPreparedCertificate(seqNum);
            if (preparedCertificate != null) {
                viewChangeBuilder.addPreparedCertificates(preparedCertificate);
            }
        }

        // set view

        if (!state.setViewAndPrimary(newViewNumber)) {
            logger.info("Failed to set new view {} and primary, another view change may have completed first",
                    newViewNumber);
            return;
        }

        logger.info("Incrementing view from {} to {}", currentView, newViewNumber);
        viewChangeBuilder.setViewNumber(newViewNumber);

        MessageServiceOuterClass.ViewChangeMessage viewChangeMessage = viewChangeBuilder.build();

        MessageServiceOuterClass.ViewChangeMessage signedViewChange = (MessageServiceOuterClass.ViewChangeMessage) auth.sign(viewChangeMessage);

        if (!state.appendServerMessage(signedViewChange, quorumSize)) {
            logger.info("Failed to append ViewChange message to state for new view {}, likely due to duplicate send",
                    newViewNumber);
            return;
        }

        logger.info("Broadcasting ViewChange message for new view {}", newViewNumber);


        broadcast(signedViewChange, state.isPrimary(), (stub, signed) -> stub.viewChange((MessageServiceOuterClass.ViewChangeMessage) signed));
        logger.info("Broadcasted ViewChange message for new view {}", newViewNumber);
    }
}
