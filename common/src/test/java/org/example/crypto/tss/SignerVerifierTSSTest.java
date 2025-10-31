package org.example.crypto.tss;

import com.google.protobuf.ByteString;
import org.example.PbftService;
import org.example.config.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SignerVerifierTSSTest {

    @BeforeAll
    static void initConfig() {
        // Config methods we use for TSS (DST/R and server number parsing) do not require initialization,
        // but calling initialize is harmless and provides logging if desired.
        Config.initialize("src/test/resources/config.properties");
    }

    private static PbftService.PrepareMessage basePrepare() {
        return PbftService.PrepareMessage.newBuilder()
                .setViewNumber("1")
                .setSequenceNumber("1")
                .setDigest(ByteString.copyFromUtf8("digest-abc"))
                .setIsAggregated(false)
                .build();
    }

    @Test
    void partialSignature_sign_and_verify_ok() {
        String nodeId = "n1";
        ThresholdKeyManager km = new ThresholdKeyManager(nodeId);
        km.load();
        SignerVerifierTSS sv = new SignerVerifierTSS(nodeId, km, Config.getTssDst(), Config.getTssR());

        var msg = basePrepare();
        ByteString partial = sv.partialSign(msg);
        assertNotEquals(ByteString.EMPTY, partial, "Partial signature should not be empty");

        boolean ok = sv.verifyPartial(msg, partial, nodeId);
        assertTrue(ok, "Partial signature should verify for signer " + nodeId);

        // Negative: verify against a mutated message should fail
        var mutated = msg.toBuilder().setDigest(ByteString.copyFromUtf8("different")).build();
        assertFalse(sv.verifyPartial(mutated, partial, nodeId), "Partial should not verify on mutated message");
    }

    @Test
    void aggregateSignature_combine_and_verify_ok() {
        // Load three nodes: use t from manifest and pick the first t nodes n1..n{t}
        String aggregatorId = "n1";
        ThresholdKeyManager kmAgg = new ThresholdKeyManager(aggregatorId);
        kmAgg.load();
        int t = kmAgg.getThresholdT();
        assertTrue(t >= 2, "Threshold t should be at least 2 for aggregation test");

        var msg = basePrepare();

        Map<Integer, ByteString> partials = new HashMap<>();
        for (int i = 1; i <= t; i++) {
            String nodeId = "n" + i;
            ThresholdKeyManager km = new ThresholdKeyManager(nodeId);
            km.load();
            SignerVerifierTSS sv = new SignerVerifierTSS(nodeId, km, Config.getTssDst(), Config.getTssR());
            ByteString part = sv.partialSign(msg);
            int idx = Config.getServerNumberFromId(nodeId);
            partials.put(idx, part);
        }

        SignerVerifierTSS collector = new SignerVerifierTSS(aggregatorId, kmAgg, Config.getTssDst(), Config.getTssR());
        ByteString aggregate = collector.combine(partials);
        assertNotEquals(ByteString.EMPTY, aggregate, "Aggregate signature should not be empty");

        boolean ok = collector.verifyFinal(msg, aggregate);
        assertTrue(ok, "Aggregate signature should verify with master public key");

        // Negative: mutated message should fail verification
        var mutated = msg.toBuilder().setDigest(ByteString.copyFromUtf8("different")) .build();
        assertFalse(collector.verifyFinal(mutated, aggregate), "Aggregate should not verify on mutated message");
    }
}
