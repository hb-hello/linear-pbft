package org.example.crypto.tss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.Config;
import supranational.blst.P1;
import supranational.blst.P1_Affine;
import supranational.blst.SecretKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ThresholdKeyManager {

    private static final String DEFAULT_PRIVATE_KEY_DIR = "keys/private/tss";
    private static final String DEFAULT_PUBLIC_KEY_MANIFEST = "keys/public/tss/manifest.json";

    private final String selfId;
    private final Map<String, P1> publicKeys;
    private volatile SecretKey privateKey;

    // Remember locations and parsed metadata
    private volatile String privateKeyDir = DEFAULT_PRIVATE_KEY_DIR;
    private volatile String publicKeyManifestPath = DEFAULT_PUBLIC_KEY_MANIFEST;
    private volatile P1 masterPublicKey;
    private volatile int thresholdT;
    private volatile int totalN;

    public ThresholdKeyManager(String selfId) {
        this.selfId = selfId;
        this.publicKeys = new ConcurrentHashMap<>();
    }

    public String getSelfId() {
        return selfId;
    }

    public void load() {
        String priv = DEFAULT_PRIVATE_KEY_DIR;
        String pubm = DEFAULT_PUBLIC_KEY_MANIFEST;

        // Prefer Config overrides if Config is initialized
        if (Config.isInitialized()) {
            try {
                String cPriv = Config.getTssPrivateKeyDir();
                String cPubm = Config.getTssPublicKeyPath();
                if (cPriv != null && !cPriv.isBlank()) priv = cPriv;
                if (cPubm != null && !cPubm.isBlank()) pubm = cPubm;
            } catch (Exception ignored) { }
        }

        // Fallback for module test execution where working dir might be common/
        Path privPath = Paths.get(priv);
        Path pubmPath = Paths.get(pubm);
        if (!Files.exists(privPath) || !Files.exists(pubmPath)) {
            String altPriv = Paths.get("..", "keys", "private", "tss").toString();
            String altPubm = Paths.get("..", "keys", "public", "tss", "manifest.json").toString();
            if (Files.exists(Paths.get(altPriv)) && Files.exists(Paths.get(altPubm))) {
                priv = altPriv;
                pubm = altPubm;
            }
        }
        load(priv, pubm);
    }

    public void load(String privateKeyDir, String publicKeyManifestPath) {
        try {
            this.privateKeyDir = privateKeyDir;
            this.publicKeyManifestPath = publicKeyManifestPath;

            // Load private key for this node as SecretKey
            Path privateKeyPath = Paths.get(privateKeyDir).resolve(selfId + ".pem");
            if (!Files.exists(privateKeyPath)) {
                throw new IllegalStateException("Missing private key for " + selfId + " at " + privateKeyPath);
            }
            byte[] skBytes = readPem(privateKeyPath, true);
            SecretKey sk = new SecretKey();
            sk.from_bendian(skBytes);
            this.privateKey = sk;

            // Load public keys manifest (structured JSON via ThresholdArtifacts.Manifest)
            Path manifestPath = Paths.get(publicKeyManifestPath);
            if (!Files.exists(manifestPath)) {
                throw new IllegalStateException("Missing public key manifest at " + manifestPath);
            }
            ThresholdArtifacts.Manifest manifest = loadManifest(manifestPath);

            // Update internal state from manifest
            this.thresholdT = manifest.t;
            this.totalN = manifest.n;
            this.masterPublicKey = manifest.masterPublicKey == null ? null : new P1(decodePublicPemString(manifest.masterPublicKey));

            Map<String, P1> pubs = new HashMap<>();
            if (manifest.sharePublicKeys != null) {
                for (Map.Entry<String, String> e : manifest.sharePublicKeys.entrySet()) {
                    pubs.put(e.getKey(), new P1(decodePublicPemString(e.getValue())));
                }
            }
            publicKeys.clear();
            publicKeys.putAll(pubs);

            if (!publicKeys.containsKey(selfId)) {
                throw new IllegalStateException("Missing public key entry for selfId " + selfId + " in manifest " + manifestPath);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load keys", e);
        }
    }

    // Return share public key as P1_Affine for a node
    public P1_Affine getPublicKeyShare(String nodeId) {
        P1 p = publicKeys.get(nodeId);
        if (p == null) {
            throw new IllegalStateException("No public key loaded for " + nodeId);
        }
        return new P1_Affine(p);
    }

    public Map<String, P1> getAllPublicKeys() {
        return Collections.unmodifiableMap(publicKeys);
    }

    // Return master public key as P1_Affine
    public P1_Affine getMasterPublicKey() {
        if (masterPublicKey == null) {
            throw new IllegalStateException("Master public key not loaded from manifest " + publicKeyManifestPath);
        }
        return new P1_Affine(masterPublicKey);
    }

    // Convenience to read private key share for a given node from configured dir, as SecretKey
    public SecretKey getPrivateKeyShare(String nodeId) {
        try {
            Path p = Paths.get(privateKeyDir).resolve(nodeId + ".pem");
            if (!Files.exists(p)) {
                throw new IllegalStateException("Missing private key share for " + nodeId + " at " + p);
            }
            byte[] skBytes = readPem(p, true);
            SecretKey sk = new SecretKey();
            sk.from_bendian(skBytes);
            return sk;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load private key share for " + nodeId, e);
        }
    }

    // Return SecretKey for self
    public SecretKey getPrivateKey() {
        if (privateKey == null) {
            throw new IllegalStateException("Private key not loaded for " + selfId);
        }
        return privateKey;
    }

    // Manifest metadata getters
    public int getThresholdT() { return thresholdT; }
    public int getTotalN() { return totalN; }

    private static byte[] readPem(Path pemPath, boolean isPrivate) {
        try {
            String s = Files.readString(pemPath);
            String base64 = s
                    .replaceAll("-----BEGIN\\s*" + (isPrivate ? "PRIVATE" : "PUBLIC") + "\\s*KEY-----", "")
                    .replaceAll("-----END\\s*" + (isPrivate ? "PRIVATE" : "PUBLIC") + "\\s*KEY-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + (isPrivate ? "private" : "public") + " key from " + pemPath, e);
        }
    }

    private static byte[] decodePublicPemString(String pem) {
        try {
            String base64 = pem
                    .replace("\\n", "\n")
                    .replaceAll("-----BEGIN\\s*PUBLIC\\s*KEY-----", "")
                    .replaceAll("-----END\\s*PUBLIC\\s*KEY-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse public key PEM string", e);
        }
    }

    // Parse manifest strictly as ThresholdArtifacts.Manifest (no legacy flat-map)
    private static ThresholdArtifacts.Manifest loadManifest(Path manifestPath) {
        try {
            String json = Files.readString(manifestPath);
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, ThresholdArtifacts.Manifest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load public keys from manifest " + manifestPath, e);
        }
    }
}
