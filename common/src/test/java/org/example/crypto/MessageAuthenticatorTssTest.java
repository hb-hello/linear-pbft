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

    @Test
    void tss_client_sign_and_verify_client_request() {
        // Test that a client can sign a ClientRequest and it can be verified
        String clientId = "A";
        MessageAuthenticator clientAuth = new MessageAuthenticator(clientId);

        // Create a sample client request
        MessageServiceOuterClass.Operation operation = MessageServiceOuterClass.Operation.newBuilder()
                .setTransfer(MessageServiceOuterClass.Transfer.newBuilder()
                        .setSender("A")
                        .setReceiver("B")
                        .setAmount(5.0)
                        .build())
                .build();

        MessageServiceOuterClass.ClientRequest request = MessageServiceOuterClass.ClientRequest.newBuilder()
                .setOperation(operation)
                .setTimestamp(System.currentTimeMillis())
                .setClientId(clientId)
                .build();

        // Sign the request
        MessageServiceOuterClass.ClientRequest signedRequest = (MessageServiceOuterClass.ClientRequest) clientAuth.signWithTSS(request);

        // Verify the signature fields are populated
        assertEquals(clientId, signedRequest.getSignerId());
        assertFalse(signedRequest.getSignature().isEmpty());

        // Verify the signature
        assertTrue(clientAuth.verifyWithTss(signedRequest), "Client signature should verify");

        // Test that a mutated request fails verification
        MessageServiceOuterClass.ClientRequest mutatedRequest = signedRequest.toBuilder()
                .setTimestamp(signedRequest.getTimestamp() + 1000)
                .build();
        assertFalse(clientAuth.verifyWithTss(mutatedRequest), "Mutated client request should not verify");
    }

    @Test
    void tss_client_sign_and_verify_client_reply() {
        // Test that a server can sign a ClientReply and it can be verified
        String serverId = "n1";
        MessageAuthenticator serverAuth = new MessageAuthenticator(serverId);

        // Create a sample client reply
        MessageServiceOuterClass.ClientReply reply = MessageServiceOuterClass.ClientReply.newBuilder()
                .setViewNumber(1L)
                .setTimestamp(System.currentTimeMillis())
                .setClientId("A")
                .setServerId(serverId)
                .setResult(MessageServiceOuterClass.OperationResult.newBuilder()
                        .setResult(true)
                        .build())
                .build();

        // Sign the reply
        MessageServiceOuterClass.ClientReply signedReply = (MessageServiceOuterClass.ClientReply) serverAuth.signWithTSS(reply);

        // Verify the signature fields are populated
        assertEquals(serverId, signedReply.getSignerId());
        assertFalse(signedReply.getSignature().isEmpty());

        // Verify the signature
        assertTrue(serverAuth.verifyWithTss(signedReply), "Server signature on client reply should verify");

        // Test that a mutated reply fails verification
        MessageServiceOuterClass.ClientReply mutatedReply = signedReply.toBuilder()
                .setViewNumber(2L)
                .build();
        assertFalse(serverAuth.verifyWithTss(mutatedReply), "Mutated client reply should not verify");
    }

    @Test
    void tss_multiple_clients_can_sign_independently() {
        // Test that multiple different clients can sign their own requests
        String[] clientIds = {"A", "B", "C"};

        for (String clientId : clientIds) {
            MessageAuthenticator clientAuth = new MessageAuthenticator(clientId);

            MessageServiceOuterClass.Operation operation = MessageServiceOuterClass.Operation.newBuilder()
                    .setBalanceRequest(MessageServiceOuterClass.BalanceRequest.newBuilder()
                            .setAccountId(clientId)
                            .build())
                    .build();

            MessageServiceOuterClass.ClientRequest request = MessageServiceOuterClass.ClientRequest.newBuilder()
                    .setOperation(operation)
                    .setTimestamp(System.currentTimeMillis())
                    .setClientId(clientId)
                    .build();

            // Sign the request with this client's key
            MessageServiceOuterClass.ClientRequest signedRequest = (MessageServiceOuterClass.ClientRequest) clientAuth.signWithTSS(request);

            // Verify it's signed by the correct client
            assertEquals(clientId, signedRequest.getSignerId(), "Request should be signed by " + clientId);
            assertFalse(signedRequest.getSignature().isEmpty(), "Signature should not be empty for " + clientId);

            // Verify the signature using the same client's authenticator
            assertTrue(clientAuth.verifyWithTss(signedRequest), "Client " + clientId + " should verify its own signature");
        }
    }

    @Test
    void tss_client_signature_verified_by_server() {
        // Test that a client's signed request can be verified by a server
        String clientId = "A";
        String serverId = "n1";

        MessageAuthenticator clientAuth = new MessageAuthenticator(clientId);
        MessageAuthenticator serverAuth = new MessageAuthenticator(serverId);

        // Client creates and signs a request
        MessageServiceOuterClass.Operation operation = MessageServiceOuterClass.Operation.newBuilder()
                .setTransfer(MessageServiceOuterClass.Transfer.newBuilder()
                        .setSender(clientId)
                        .setReceiver("B")
                        .setAmount(10.0)
                        .build())
                .build();

        MessageServiceOuterClass.ClientRequest request = MessageServiceOuterClass.ClientRequest.newBuilder()
                .setOperation(operation)
                .setTimestamp(System.currentTimeMillis())
                .setClientId(clientId)
                .build();

        MessageServiceOuterClass.ClientRequest signedRequest = (MessageServiceOuterClass.ClientRequest) clientAuth.signWithTSS(request);

        // Server should be able to verify the client's signature
        assertTrue(serverAuth.verifyWithTss(signedRequest), "Server should verify client's signature");

        // Client should also be able to verify (sanity check)
        assertTrue(clientAuth.verifyWithTss(signedRequest), "Client should verify its own signature");
    }

    @Test
    void tss_client_signature_fails_for_wrong_signer() {
        // Test that verification fails if signer_id doesn't match the actual signer
        String clientId = "A";
        MessageAuthenticator clientAuth = new MessageAuthenticator(clientId);

        MessageServiceOuterClass.ClientRequest request = MessageServiceOuterClass.ClientRequest.newBuilder()
                .setOperation(MessageServiceOuterClass.Operation.newBuilder()
                        .setBalanceRequest(MessageServiceOuterClass.BalanceRequest.newBuilder()
                                .setAccountId(clientId)
                                .build())
                        .build())
                .setTimestamp(System.currentTimeMillis())
                .setClientId(clientId)
                .build();

        // Sign with client A
        MessageServiceOuterClass.ClientRequest signedRequest = (MessageServiceOuterClass.ClientRequest) clientAuth.signWithTSS(request);

        // Tamper with the signer_id to claim it's from client B
        MessageServiceOuterClass.ClientRequest tamperedRequest = signedRequest.toBuilder()
                .setSignerId("B")
                .build();

        // Verification should fail because the signature doesn't match the claimed signer
        MessageAuthenticator verifierAuth = new MessageAuthenticator("n1");
        assertFalse(verifierAuth.verifyWithTss(tamperedRequest),
                "Verification should fail when signer_id doesn't match the actual signer");
    }
}

