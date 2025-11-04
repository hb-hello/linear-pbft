package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.serverstate.ServerState;

public class ClientReplySender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(ClientReplySender.class);

    private final ServerState state;

    public ClientReplySender(String serverId, ServerState state,
                             CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
        this.state = state;
    }

    public void sendClientReply(MessageServiceOuterClass.ClientRequest request, MessageServiceOuterClass.ClientReply reply) {
        String clientId = request.getClientId();

        logger.info("Sending ClientReply to client {}: {} {}", clientId, reply.getResult().getOpCase(), reply.getResult().getResult());
        signAndSend(clientId, reply, (stub, signed) -> stub.reply((MessageServiceOuterClass.ClientReply) signed));

        state.rememberReply(reply);
    }

    public void resendCachedReply(MessageServiceOuterClass.ClientRequest request) {
        String clientId = request.getClientId();
        long timestamp = request.getTimestamp();
        MessageServiceOuterClass.ClientReply cachedReply = state.cachedReply(clientId, timestamp);

        if (cachedReply != null) {
            logger.info("Resending cached ClientReply to client {}: {} {}", clientId,
                    cachedReply.getResult().getOpCase(), cachedReply.getResult().getResult());
            signAndSend(clientId, cachedReply, (stub, signed) -> stub.reply((MessageServiceOuterClass.ClientReply) signed));
        } else {
            logger.warn("No cached ClientReply found for client {} and timestamp {}, cannot resend", clientId, timestamp);
        }
    }

}
