package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.serverstate.ServerState;

public class PrepareHandler {
    private static final Logger logger = LogManager.getLogger(PrepareHandler.class);

    private final ServerState state;

    public PrepareHandler(ServerState state) {
        this.state = state;
    }

    public void handle(MessageServiceOuterClass.PrepareMessage prepareMessage) {
        
    }
}
