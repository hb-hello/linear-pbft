package org.example.messaging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.crypto.MessageAuthenticator;

import java.util.concurrent.ExecutorService;

public class ClientMessageSender extends MessageSender {

    private static final Logger logger = LogManager.getLogger(ClientMessageSender.class);

    public ClientMessageSender(String nodeId, CommunicationLogger commLogger, MessageAuthenticator auth, ExecutorService networkExecutor) {
        super(nodeId, commLogger, auth, networkExecutor);
    }

    public void sendRequest(String targetNodeId, MessageServiceOuterClass.ClientRequest request) {
        signAndSend(targetNodeId, false, request, (stub, signed) -> stub.request((MessageServiceOuterClass.ClientRequest) signed));
    }
}
