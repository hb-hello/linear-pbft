package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;
import org.example.messaging.CommunicationLogger;
import org.example.messaging.MessageReceiver;
import org.example.messaging.MessageSender;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Node {

    private static final Logger logger = LogManager.getLogger(Node.class);

    protected final String nodeId;

    protected final CommunicationLogger commLogger;

    protected final MessageAuthenticator auth;

    protected final ExecutorManager executorManager;

    protected Node(String nodeId) {
        this.nodeId = nodeId;
        this.commLogger = new CommunicationLogger();
        this.auth = new MessageAuthenticator(nodeId);

        this.executorManager = new ExecutorManager(10);
    }

    public String getNodeId() {
        return nodeId;
    }
}
