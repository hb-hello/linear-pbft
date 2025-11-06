package org.example.consensus.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.MessageServiceOuterClass;
import org.example.consensus.LivenessTimer;
import org.example.consensus.senders.NewViewSender;
import org.example.consensus.senders.ViewChangeSender;
import org.example.crypto.MessageAuthenticator;
import org.example.serverstate.ServerState;

public class NewViewHandler {
    private static final Logger logger = LogManager.getLogger(NewViewHandler.class);

    private final ServerState state;
    private final MessageAuthenticator auth;

    private final LivenessTimer viewChangeTimer;

    public NewViewHandler(ServerState state, MessageAuthenticator auth, LivenessTimer viewChangeTimer) {
        this.state = state;
        this.auth = auth;
        this.viewChangeTimer = viewChangeTimer;
    }

    public void handle(MessageServiceOuterClass.NewViewMessage newView) {

        long currentView = state.getViewNumber();

        if (newView.getViewNumber() <= currentView) {
            logger.warn("Received NewViewMessage for view {} which is not greater than current view {}. Ignoring.",
                    newView.getViewNumber(), currentView);
            return;
        }


    }


}
