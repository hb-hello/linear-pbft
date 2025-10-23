package org.example.crypto;

import com.google.protobuf.Message;
import org.example.config.Config;

public class MessageAuthenticator {

    private final String selfId;
    private final KeyManager keyManager;

    public MessageAuthenticator(String selfId) {
        this.selfId = selfId;
        this.keyManager = new KeyManager(selfId);

        String privateKeyDir = Config.getPrivateKeyDir();
        String publicKeyManifestPath = Config.getPublicKeyPath();
        keyManager.load(privateKeyDir, publicKeyManifestPath);
    }

    private Message clearMessage(Message message) {
        return message.toBuilder()
                .clearField(message.getDescriptorForType().findFieldByName("signer_id"))
                .clearField(message.getDescriptorForType().findFieldByName("signature"))
                .build();
    }

    public Message sign(Message message) {
        byte[] signature = SignerVerifier.signEd25519(clearMessage(message).toByteArray(), keyManager.getPrivateKey());

        return message.toBuilder()
                .setField(message.getDescriptorForType().findFieldByName("signer_id"), selfId)
                .setField(message.getDescriptorForType().findFieldByName("signature"), com.google.protobuf.ByteString.copyFrom(signature))
                .build();
    }

    public boolean verify(Message message) {
        byte[] signature = message.getField(message.getDescriptorForType().findFieldByName("signature")) instanceof com.google.protobuf.ByteString bs
                ? bs.toByteArray()
                : new byte[0];
        String signerId = message.getField(message.getDescriptorForType().findFieldByName("signer_id")) instanceof String id
                ? id
                : "";
        return SignerVerifier.verifyEd25519(clearMessage(message).toByteArray(), signature, keyManager.getPublicKey(signerId));
    }






}
