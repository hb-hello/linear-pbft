package org.example.messaging;

import org.example.ServerNode;
import org.example.crypto.MessageAuthenticator;

public class ServerMessageReceiver extends MessageReceiver {
    public ServerMessageReceiver(ServerNode serverNode,
                                 CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverNode.getNodeId(), new ServerMessageService(serverNode, commLogger, auth), new ServerActivityInterceptor());
    }
}
