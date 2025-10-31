package org.example.crypto;

import com.google.protobuf.ByteString;
import org.example.MessageServiceOuterClass;
import org.example.config.Config;
import org.example.crypto.tss.ThresholdKeyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MessageAuthenticatorTssTest {

    @BeforeAll
    static void init() {
        Config.initialize("src/test/resources/config.properties");
    }

    private static MessageServiceOuterClass.PrepareMessage basePrepare() {
        return MessageServiceOuterClass.PrepareMessage.newBuilder()
                .setViewNumber(1L)
                .setSequenceNumber(1L)
                .setDigest(ByteString.copyFromUtf8("digest-abc"))
                .setIsAggregated(false)
                .build();
    }

    @Test
    void tss_partial_sign_and_verify_via_authenticator() {
        String nodeId = "n1";
        // MessageAuthenticator internally constructs ThresholdKeyManager and SignerVerifierTSS
        MessageAuthenticator auth = new MessageAuthenticator(nodeId);

        var msg = basePrepare();
        MessageServiceOuterClass.PrepareMessage signed = (MessageServiceOuterClass.PrepareMessage) auth.signWithTSS(msg);

        assertEquals(nodeId, signed.getSignerId());
        assertFalse(signed.getIsAggregated());
        assertFalse(signed.getSignature().isEmpty());

        assertTrue(auth.verifyWithTss(signed));

        // Mutated digest should fail verification
        var mutated = signed.toBuilder().setDigest(ByteString.copyFromUtf8("different")).build();
        assertFalse(auth.verifyWithTss(mutated));
    }

    @Test
    void tss_aggregate_sign_and_verify_via_authenticator() {
        String aggregatorId = "n1";
        // Ensure ThresholdKeyManager can load and get threshold t
        ThresholdKeyManager tkm = new ThresholdKeyManager(aggregatorId);
        tkm.load();
        int t = tkm.getThresholdT();
        assertTrue(t >= 2);

        MessageAuthenticator authAgg = new MessageAuthenticator(aggregatorId);
        var msg = basePrepare();

        Map<String, ByteString> partials = new HashMap<>();
        for (int i = 1; i <= t; i++) {
            String nodeId = "n" + i;
            MessageAuthenticator authNode = new MessageAuthenticator(nodeId);
            MessageServiceOuterClass.PrepareMessage partMsg = (MessageServiceOuterClass.PrepareMessage) authNode.signWithTSS(msg);
            partials.put(nodeId, partMsg.getSignature());
        }

        MessageServiceOuterClass.PrepareMessage aggregated = (MessageServiceOuterClass.PrepareMessage) authAgg.signWithAggregateTss(msg, partials);
        assertTrue(aggregated.getIsAggregated());
        assertEquals(aggregatorId, aggregated.getSignerId());
        assertFalse(aggregated.getSignature().isEmpty());

        assertTrue(authAgg.verifyWithTss(aggregated));

        // Mutated payload should fail
        var mutated = aggregated.toBuilder().setDigest(ByteString.copyFromUtf8("different")).build();
        assertFalse(authAgg.verifyWithTss(mutated));
    }
}

