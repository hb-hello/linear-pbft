package org.example;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class MaliceInjectorTest {

    @AfterEach
    public void teardown() {
        // Reset MaliceInjector between tests
        MaliceInjector.init(Collections.emptySet());
    }

    @Test
    public void testInjectTimingAttack_delaysWhenEnabled() {
        // Configure a small delay via Properties and initialize Config
        Properties props = new Properties();
        props.setProperty("malice.time.delay.millis", "50");
        props.setProperty("server.details.path", "../src/main/resources/serverDetails.json");
        props.setProperty("client.details.path", "../src/main/resources/clientDetails.json");
        org.example.config.Config.initialize(props);

        // Enable time malice and mark n1 as malicious
        MessageServiceOuterClass.Malice timeMalice = MessageServiceOuterClass.Malice.newBuilder()
                .setMaliceType("time")
                .addMaliciousServerId("n1")
                .build();
        MaliceInjector.init(Collections.singleton(timeMalice));

        long start = System.currentTimeMillis();
        MaliceInjector.injectTimingAttack("n1");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 40, "injectTimingAttack should sleep at least the configured delay");
    }

    @Test
    public void testInjectInDarkAttack_checksTargetMembership() {
        // Create dark malice targeting n2 and mark n1 as malicious
        MessageServiceOuterClass.Malice darkMalice = MessageServiceOuterClass.Malice.newBuilder()
                .setMaliceType("dark")
                .addTargetServerId("n2")
                .addMaliciousServerId("n1")
                .build();
        MaliceInjector.init(Collections.singleton(darkMalice));

        // For server n1 (malicious), target n2 should be considered dark (true), n3 should not (false)
        assertTrue(MaliceInjector.injectInDarkAttack("n1", "n2"));
        assertFalse(MaliceInjector.injectInDarkAttack("n1", "n3"));

        // For a non-malicious server, dark attack should not apply
        assertFalse(MaliceInjector.injectInDarkAttack("n0", "n2"));
    }

    @Test
    public void testInjectSignAttack_replacesSignature() {
        // Enable sign malice and mark n1 as malicious
        MessageServiceOuterClass.Malice signMalice = MessageServiceOuterClass.Malice.newBuilder()
                .setMaliceType("sign")
                .addMaliciousServerId("n1")
                .build();
        MaliceInjector.init(Collections.singleton(signMalice));

        // Build a PrePrepareMessage with a signature
        MessageServiceOuterClass.PrePrepareMessage msg = MessageServiceOuterClass.PrePrepareMessage.newBuilder()
                .setViewNumber(1)
                .setSequenceNumber(10)
                .setSignerId("n1")
                .setSignature(ByteString.copyFromUtf8("ORIGINAL"))
                .build();

        MessageServiceOuterClass.PrePrepareMessage out = (MessageServiceOuterClass.PrePrepareMessage) MaliceInjector.injectSignAttack("n1", msg);
        assertNotNull(out);
        assertEquals("INCORRECT_SIGNATURE", out.getSignature().toStringUtf8());
    }

    @Test
    public void testInjectEquivocationAttack_checksTargetMembership() {
        // Enable equivocation malice targeting n1 and mark n0 as malicious
        MessageServiceOuterClass.Malice eqMalice = MessageServiceOuterClass.Malice.newBuilder()
                .setMaliceType("equivocation")
                .addTargetServerId("n1")
                .addMaliciousServerId("n0")
                .build();
        MaliceInjector.init(Collections.singleton(eqMalice));

        // For server n0 (malicious), target n1 should return true, n2 should return false
        assertTrue(MaliceInjector.injectEquivocationAttack("n0", "n1"));
        assertFalse(MaliceInjector.injectEquivocationAttack("n0", "n2"));

        // For a non-malicious server, equivocation attack should not apply
        assertFalse(MaliceInjector.injectEquivocationAttack("n1", "n0"));
    }
}
