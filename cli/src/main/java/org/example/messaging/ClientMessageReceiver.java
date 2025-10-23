package org.example.messaging;

import org.example.ClientNode;
import org.example.crypto.MessageAuthenticator;

public class ClientMessageReceiver extends MessageReceiver {

    public ClientMessageReceiver(ClientNode clientNode, MessageAuthenticator auth) {
        super(clientNode.getNodeId(), new ClientMessageService(clientNode, auth));
    }
}
