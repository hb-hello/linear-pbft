package org.example;

import org.example.crypto.MessageAuthenticator;

public class NodeMessageReceiver extends MessageReceiver {
    public NodeMessageReceiver(Node node,
                               CommunicationLogger commLogger, MessageAuthenticator auth) {
        super(node.getNodeId(), new NodeMessageService(node, commLogger, auth), new ServerActivityInterceptor());
    }
}
