package org.example.testutil;

import com.google.protobuf.Message;
import org.example.crypto.MessageAuthenticator;

/**
 * Mock MessageAuthenticator for testing.
 * Returns a configurable boolean for verify() without requiring key infrastructure.
 */
public class MockMessageAuthenticator extends MessageAuthenticator {
    private final boolean verifyResult;

    public MockMessageAuthenticator() {
        this(true); // Default to accepting all signatures
    }

    public MockMessageAuthenticator(boolean verifyResult) {
        super("n1");
        this.verifyResult = verifyResult;
    }

    @Override
    public boolean verify(Message message) {
        return verifyResult;
    }

    @Override
    public boolean verifyWithTss(Message message) {
        return verifyResult;
    }
}

