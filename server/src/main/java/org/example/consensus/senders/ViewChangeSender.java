package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.messaging.ServerMessage;
import org.example.serverstate.ServerState;

import java.util.Iterator;
import java.util.List;

public class ViewChangeSender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(ViewChangeSender.class);

    private final int quorumSize;

    public ViewChangeSender(String serverId, int quorumSize,
                         CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
        this.quorumSize = quorumSize;
    }

    public void broadcastViewChange(ServerState state) {
        if (!state.isViewChangeInProgress()) {
            logger.info("No view change in progress, not sending ViewChange message");
            return;
        }

        logger.info("Preparing ViewChange message for view {}", state.getViewNumber() + 1);

        long currentView = state.getViewNumber();

        MessageServiceOuterClass.ViewChangeMessage.Builder viewChangeBuilder = MessageServiceOuterClass.ViewChangeMessage.newBuilder();

        // fetch last stable checkpoint seq num
        long lastStableCheckpointSeqNum = state.getLatestStableCheckpointSeqNum();
        viewChangeBuilder.setLastStableSequenceNumber(lastStableCheckpointSeqNum);

        // fetch checkpoint certificate
        logger.info("Fetching checkpoint messages for view {} and seq num {}",
                state.getViewNumber(), lastStableCheckpointSeqNum);
        List<ServerMessage> checkpointMessages = state.getQuorumMessages(ServerMessage.CHECKPOINT, state.getViewNumber(), lastStableCheckpointSeqNum);
        viewChangeBuilder.addAllCheckpointMessages(checkpointMessages.stream().map((msg) -> (MessageServiceOuterClass.CheckpointMessage) msg.getMessage()).toList());

        // fetch prepared certificate
        logger.info("Fetching prepared certificates between watermarks for view {}",
                state.getViewNumber());
        for (Iterator<Long> it = state.getSeqNumsBetweenWatermarks(); it.hasNext();) {
            long seqNum = it.next();
            MessageServiceOuterClass.PreparedCertificate preparedCertificate = state.getPreparedCertificate(currentView, seqNum);
            if (preparedCertificate != null) {
                viewChangeBuilder.addPreparedCertificates(preparedCertificate);
            }
        }

        // increment view
        logger.info("Incrementing view from {} to {}", currentView, currentView + 1);
        long newViewNumber = state.nextViewAndUpdatePrimary();
        viewChangeBuilder.setViewNumber(newViewNumber);

        MessageServiceOuterClass.ViewChangeMessage viewChangeMessage = viewChangeBuilder.build();

        MessageServiceOuterClass.ViewChangeMessage signedViewChange = (MessageServiceOuterClass.ViewChangeMessage) auth.sign(viewChangeMessage);

        if (!state.appendServerMessage(signedViewChange, quorumSize)) {
            logger.info("Failed to append ViewChange message to state for new view {}, likely due to duplicate send",
                    newViewNumber);
            return;
        }

        logger.info("Broadcasting ViewChange message for new view {}", newViewNumber);
        broadcast(signedViewChange, (stub, signed) -> stub.viewChange((MessageServiceOuterClass.ViewChangeMessage) signed));
        logger.info("Broadcasted ViewChange message for new view {}", newViewNumber);
    }
}
