package org.example.messaging;

import org.example.ClientNode;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;

public class ClientMessageReceiver extends MessageReceiver {

    public ClientMessageReceiver(ClientNode clientNode, MessageAuthenticator auth) {
        super(clientNode.getNodeId(), Config.getClientPort(clientNode.getNodeId()), new ClientMessageService(clientNode, auth));
    }
}
