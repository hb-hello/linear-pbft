package org.example.messaging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;

public class ClientMessageSender extends MessageSender {

    private static final Logger logger = LogManager.getLogger(ClientMessageSender.class);

    public ClientMessageSender(String nodeId, CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(nodeId, commLogger, auth);
    }

    public void sendRequest(String targetNodeId, MessageServiceOuterClass.ClientRequest request) {
        signAndSend(targetNodeId, request, (stub, signed) -> stub.request((MessageServiceOuterClass.ClientRequest) signed));
    }
}
