package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;

public class ClientMessageService extends MessageServiceGrpc.MessageServiceImplBase {

    private static final Logger logger = LogManager.getLogger(ClientMessageService.class);
    private final ClientNode clientNode;
    private final MessageAuthenticator auth;

    public ClientMessageService(ClientNode clientNode, MessageAuthenticator auth) {
        this.clientNode = clientNode;
        this.auth = auth;
    }
}
