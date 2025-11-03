package org.example.consensus.senders;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageSender;

public class ClientReplySender extends MessageSender {
    private static final Logger logger = LogManager.getLogger(ClientReplySender.class);

    public ClientReplySender(String serverId,
                             CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverId, commLogger, auth);
    }

    public void sendClientReply(String clientId, MessageServiceOuterClass.ClientReply reply) {
        logger.info("Sending ClientReply to client {}: {}", clientId, reply.getResult());
        signAndSend(clientId, reply, (stub, signed) -> stub.reply((MessageServiceOuterClass.ClientReply) signed));
    }

}
