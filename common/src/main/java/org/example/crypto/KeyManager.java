package org.example.crypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KeyManager {

    private final String selfId;
    private final Map<String, PublicKey> publicKeys;
    private volatile PrivateKey privateKey;

    public KeyManager(String selfId) {
        this.selfId = selfId;
        this.publicKeys = new ConcurrentHashMap<>();
    }

    public String getSelfId() {
        return selfId;
    }

    public void load(String privateKeyDir, String publicKeyManifestPath) {

        Path privateKeyPath = privateKeyDir == null ? null : Path.of(privateKeyDir).resolve(selfId + ".pem");

        if (privateKeyPath != null && Files.exists(privateKeyPath)) {
            this.privateKey = Pem.readEd25519Private(privateKeyPath);
        }
        if (publicKeyManifestPath != null && Files.exists(Path.of(publicKeyManifestPath))) {
            Map<String, PublicKey> loadedPubs = Pem.loadEd25519PublicKeys(Path.of(publicKeyManifestPath));
            publicKeys.putAll(loadedPubs);
        }

        PublicKey mine = publicKeys.get(selfId);
        if (mine == null) throw new IllegalStateException("Missing public key for " + selfId);
    }

    public PublicKey getPublicKey(String serverId) {
        if (!publicKeys.containsKey(serverId)) {
            throw new IllegalStateException("No public key loaded for " + serverId);
        }
        return publicKeys.get(serverId);
    }

    public PrivateKey getPrivateKey() {
        if (privateKey == null) {
            throw new IllegalStateException("Private key not loaded for " + selfId);
        }
        return privateKey;
    }

}
