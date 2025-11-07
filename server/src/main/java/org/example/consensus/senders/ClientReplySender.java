package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MaliceInjector;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;
import org.example.serverstate.ServerState;

import java.util.concurrent.ExecutorService;

public class ClientReplySender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(ClientReplySender.class);

    private final String serverId;

    public ClientReplySender(String serverId, CommunicationLogger commLogger, MessageAuthenticator auth, ExecutorService networkExecutor) {
        super(serverId, commLogger, auth, networkExecutor);
        this.serverId = serverId;
    }

    public void sendClientReply(MessageServiceOuterClass.ClientRequest request, MessageServiceOuterClass.ClientReply reply) {
        String clientId = request.getClientId();

        // injecting crash attack
        if(MaliceInjector.injectCrashAttack(serverId)) {
            logger.info("MaliceInjector crash attack activated - refraining from sending ClientReply to client {}", clientId);
            return;
        }

        logger.info("Sending ClientReply to client {}: {}", clientId, reply.getResult().getOpCase());
        signAndSend(clientId, reply, (stub, signed) -> stub.reply((MessageServiceOuterClass.ClientReply) signed));
    }

    public void resendCachedReply(ServerState state, MessageServiceOuterClass.ClientRequest request) {
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
