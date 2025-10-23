package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;

public class ClientMessageService extends MessageServiceGrpc.MessageServiceImplBase {

    private static final Logger logger = LogManager.getLogger(ClientMessageService.class);
    private final Client client;
    private final MessageAuthenticator auth;

    public ClientMessageService(Client client, MessageAuthenticator auth) {
        this.client = client;
        this.auth = auth;
    }
}
