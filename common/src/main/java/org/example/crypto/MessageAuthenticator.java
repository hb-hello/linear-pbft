package org.example.crypto;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;
import org.example.crypto.tss.SignerVerifierTSS;
import org.example.crypto.tss.ThresholdKeyManager;

import java.util.HashMap;
import java.util.Map;

public class MessageAuthenticator {

    private static final Logger logger = LogManager.getLogger(MessageAuthenticator.class);

    private final String selfId;
    private final KeyManager keyManager;
    private final boolean ed25519Enabled;

    private final ThresholdKeyManager tssKeyManager;
    private final SignerVerifierTSS tssSignerVerifier;

    public MessageAuthenticator(String selfId) {
        this.selfId = selfId;
        this.keyManager = new KeyManager(selfId);

        boolean ed25519Ok;
        try {
            String privateKeyDir = Config.getPrivateKeyDir();
            String publicKeyManifestPath = Config.getPublicKeyPath();
            keyManager.load(privateKeyDir, publicKeyManifestPath);
            ed25519Ok = true;
        } catch (Exception e) {
            ed25519Ok = false;
            logger.warn("Ed25519 keys not available; Ed25519 sign/verify disabled. Cause: {}", e.getMessage());
        }
        this.ed25519Enabled = ed25519Ok;

        //Threshold Signature Scheme init
        this.tssKeyManager = new ThresholdKeyManager(selfId);
        tssKeyManager.load();
        tssSignerVerifier = new SignerVerifierTSS(
                selfId,
                tssKeyManager,
                Config.getTssDst(),
                Config.getTssR()
        );
    }

    public Message removeSignature(Message message) {
        Message clearedMessage = message.toBuilder()
                .clearField(message.getDescriptorForType().findFieldByName("signer_id"))
                .clearField(message.getDescriptorForType().findFieldByName("signature"))
                .build();

        if (message.getDescriptorForType().findFieldByName("is_aggregated") != null) {
            return clearedMessage.toBuilder()
                    .clearField(message.getDescriptorForType().findFieldByName("is_aggregated"))
                    .build();
        }
        return clearedMessage;
    }

    public Message sign(Message message) {
        return signWithTSS(message);
    }

    public boolean verify(Message message) {
        try {
            return verifyWithTss(message);
        } catch (IllegalStateException e) {
            logger.error("Verification failed due to missing fields: {}", e.getMessage());
            return false;
        }
    }

    public Message signWithTSS(Message message) {
//        logger.info("Signing message with TSS: {}", message.getClass().getSimpleName());
        Descriptors.FieldDescriptor fSig = message.getDescriptorForType().findFieldByName("signature");
        if (fSig == null) throw new IllegalStateException("Message missing signature field");

        Descriptors.FieldDescriptor fId = message.getDescriptorForType().findFieldByName("signer_id");
        if (fId == null) throw new IllegalStateException("Message missing signer_id field");

        byte[] partialSig = tssSignerVerifier.partialSign(message);

        Descriptors.FieldDescriptor fIsAggregated = message.getDescriptorForType().findFieldByName("is_aggregated");
        if (fIsAggregated == null) {
            return message.toBuilder()
                    .setField(fSig, ByteString.copyFrom(partialSig))
                    .setField(fId, selfId)
                    .build();
        }

        return message.toBuilder()
                .setField(fSig, ByteString.copyFrom(partialSig))
                .setField(fId, selfId)
                .setField(fIsAggregated, false)
                .build();
    }

    public boolean verifyWithTss(Message message) {
        Descriptors.FieldDescriptor fSig = message.getDescriptorForType().findFieldByName("signature");
        if (fSig == null) throw new IllegalStateException("Message missing signature field");

        Descriptors.FieldDescriptor fId = message.getDescriptorForType().findFieldByName("signer_id");
        if (fId == null) throw new IllegalStateException("Message missing signer_id field");
        String signerId = message.getField(fId) instanceof String id ? id : "";

        byte[] sig = message.getField(fSig) instanceof ByteString bs ? bs.toByteArray() : new byte[0];

        Descriptors.FieldDescriptor fIsAggregated = message.getDescriptorForType().findFieldByName("is_aggregated");
//        logger.info("Checking isAggregated while verifying signature for message of type {}: {}",
//                message.getClass().getSimpleName(), fIsAggregated != null ? message.getField(fIsAggregated) : "null");
        // if aggregated signature, verify final
        if (fIsAggregated != null) {
            boolean isAggregated = message.getField(fIsAggregated) instanceof Boolean b ? b : false;
            if (isAggregated) {
                return tssSignerVerifier.verifyFinal(message, sig);
            }
        }

        // if partial signature, verify partial
        return tssSignerVerifier.verifyPartial(message, sig, signerId);
    }

    public Message signWithAggregateTss(Message message, Map<String, ByteString> partialSigs) {
        Descriptors.FieldDescriptor fAggSig = message.getDescriptorForType().findFieldByName("signature");
        if (fAggSig == null) throw new IllegalStateException("Message missing signature field");

        Descriptors.FieldDescriptor fId = message.getDescriptorForType().findFieldByName("signer_id");
        if (fId == null) throw new IllegalStateException("Message missing signer_id field");

        Descriptors.FieldDescriptor fIsAggregated = message.getDescriptorForType().findFieldByName("is_aggregated");
        if (fIsAggregated == null) throw new IllegalStateException("Message missing is_aggregated field");
        logger.info("Found all fields needed to attach sign to message : {}, {}, {}",
            fAggSig.getName(), fId.getName(), fIsAggregated.getName());

        Map<Integer, byte[]> parts = new HashMap<>();
        for (Map.Entry<String, ByteString> e : partialSigs.entrySet()) {
            parts.put(Config.getServerNumberFromId(e.getKey()), e.getValue().toByteArray());
        }

        logger.info("Combining {} partial signatures to create aggregate signature", parts.size());

        byte[] aggregateSig = tssSignerVerifier.combine(parts);
        return message.toBuilder()
                .setField(fAggSig, ByteString.copyFrom(aggregateSig))
                .setField(fId, selfId)
                .setField(fIsAggregated, true)
                .build();
    }
}
