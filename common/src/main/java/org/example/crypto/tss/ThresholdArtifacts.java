package org.example.crypto.tss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.config.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public final class ThresholdArtifacts {

    private static final Logger logger = LogManager.getLogger(ThresholdArtifacts.class);

    private final int t;
    private final int n;
    private final byte[] masterPkG1;
    private final Map<String, byte[]> shareSk32;
    private final Map<String, byte[]> sharePkG1;

    private final static String PUBLIC_KEY_MANIFEST_PATH = "keys/public/tss/manifest.json";
    private final static String PRIVATE_KEY_DIR = "keys/private/tss/";

    public ThresholdArtifacts(
            int t, int n, byte[] masterPkG1,
            Map<String, byte[]> shareSk32,
            Map<String, byte[]> sharePkG1
    ) {
        this.t = t;
        this.n = n;
        this.masterPkG1 = masterPkG1;
        this.shareSk32 = shareSk32;
        this.sharePkG1 = sharePkG1;
    }

    public int t() {
        return t;
    }

    public int n() {
        return n;
    }

    public byte[] masterPkG1() {
        return masterPkG1;
    }

    public Map<String, byte[]> shareSk32() {
        return shareSk32;
    }

    public Map<String, byte[]> sharePkG1() {
        return sharePkG1;
    }

    // Save t, n, masterPkG1 and sharePkG1 into a JSON manifest at PUBLIC_KEY_MANIFEST_PATH using Jackson
    public void saveManifest() {
        // Build nodeIndex map and sharePublicKeys map
        List<String> nodeNames = new ArrayList<>(sharePkG1.keySet());
        Collections.sort(nodeNames);

        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        Map<String, String> sharePublicKeys = new LinkedHashMap<>();

        int seq = 1;
        for (String name : nodeNames) {
            Integer idx = Config.getServerNumberFromId(name);
            if (idx == null) {
                idx = seq;
            }
            nodeIndex.put(name, idx);
            seq++;
            sharePublicKeys.put(name, toPem("PUBLIC KEY", sharePkG1.get(name)));
        }

        Manifest manifest = new Manifest();
        manifest.t = this.t;
        manifest.n = this.n;
        manifest.masterPublicKey = toPem("PUBLIC KEY", this.masterPkG1);
        manifest.nodeIndex = nodeIndex;
        manifest.sharePublicKeys = sharePublicKeys;

        try {
            Path manifestPath = Paths.get(PUBLIC_KEY_MANIFEST_PATH);
            Path dir = manifestPath.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(new File(PUBLIC_KEY_MANIFEST_PATH), manifest);
        } catch (IOException e) {
            logger.error("Failed to save threshold key manifest", e);
            throw new RuntimeException(e);
        }
    }

    // Save each private key share as a separate PEM file under PRIVATE_KEY_DIR
    public void savePrivateKeys() {
        try {
            Path dir = Paths.get(PRIVATE_KEY_DIR);
            Files.createDirectories(dir);

            for (Map.Entry<String, byte[]> e : shareSk32.entrySet()) {
                String nodeId = e.getKey();
                if (nodeId == null || nodeId.isEmpty()) continue;
                String fileName = nodeId.endsWith(".pem") ? nodeId : nodeId + ".pem";
                Path out = dir.resolve(fileName);
                String pem = toPem("PRIVATE KEY", e.getValue());
                Files.writeString(out, pem, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException ex) {
            logger.error("Failed to save private key shares", ex);
            throw new RuntimeException(ex);
        }
    }

    // Helper to convert raw key bytes into simple PEM with 64-char line wrapping
    private static String toPem(String type, byte[] data) {
        if (data == null) return null;
        String b64 = Base64.getEncoder().encodeToString(data);
        StringBuilder body = new StringBuilder(b64.length() + (b64.length() / 64) * 2);
        for (int i = 0; i < b64.length(); i += 64) {
            int end = Math.min(i + 64, b64.length());
            body.append(b64, i, end).append('\n');
        }
        return "-----BEGIN " + type + "-----\n" + body + "-----END " + type + "-----\n";
    }

    // DTO for Jackson serialization
    static final class Manifest {
        public int t;
        public int n;
        public String masterPublicKey;
        public Map<String, Integer> nodeIndex;
        public Map<String, String> sharePublicKeys;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ThresholdArtifacts) obj;
        return this.t == that.t &&
                this.n == that.n &&
                Arrays.equals(this.masterPkG1, that.masterPkG1) &&
                Objects.equals(this.shareSk32, that.shareSk32) &&
                Objects.equals(this.sharePkG1, that.sharePkG1);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(t, n, shareSk32, sharePkG1);
        result = 31 * result + Arrays.hashCode(masterPkG1);
        return result;
    }

    @Override
    public String toString() {
        return "ThresholdArtifacts[" +
                "t=" + t + ", " +
                "n=" + n + ", " +
                "masterPkG1=" + (masterPkG1 == null ? null : Base64.getEncoder().encodeToString(masterPkG1)) + ", " +
                "shareSk32=" + shareSk32 + ", " +
                "sharePkG1=" + sharePkG1 + ']';
    }
}

