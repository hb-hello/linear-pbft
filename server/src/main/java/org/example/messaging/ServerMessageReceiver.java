package org.example.messaging;

import org.example.ServerNode;
import org.example.config.Config;
import org.example.crypto.MessageAuthenticator;

public class ServerMessageReceiver extends MessageReceiver {
    public ServerMessageReceiver(ServerNode serverNode,
                                 CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(serverNode.getNodeId(), Config.getServerPort(serverNode.getNodeId()), new ServerMessageService(serverNode, commLogger, auth), new ServerActivityInterceptor());
    }
}
