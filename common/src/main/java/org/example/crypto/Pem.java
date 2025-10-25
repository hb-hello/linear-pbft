package org.example.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

final class Pem {
    static PrivateKey readEd25519Private(Path pemPath) {
        try {
            String s = Files.readString(pemPath);
            String base64 = s.replaceAll("-----BEGIN PRIVATE KEY-----", "")
                    .replaceAll("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load private key " + pemPath, e);
        }
    }

    static PublicKey readEd25519Public(Path pemPath) {
        try {
            String s = Files.readString(pemPath);
            String base64 = s.replaceAll("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load public key " + pemPath, e);
        }
    }

    static Map<String, PublicKey> loadEd25519PublicKeys(Path manifestPath) {
        try {
            String json = Files.readString(manifestPath);
            ObjectMapper mapper = new ObjectMapper();
            PublicKeyManifest manifest = mapper.readValue(json, PublicKeyManifest.class);
            Map<String, PublicKey> result = new HashMap<>();
            for (Map.Entry<String, String> entry : manifest.getKeys().entrySet()) {
                String id = entry.getKey();
                String pem = entry.getValue()
                        .replace("\\n", "\n");
                // Remove PEM header/footer first (allow optional spaces within labels)
                pem = pem.replaceAll("-----BEGIN\\s*PUBLIC\\s*KEY-----", "")
                         .replaceAll("-----END\\s*PUBLIC\\s*KEY-----", "");
                // Then remove all whitespace to get a contiguous Base64 string
                pem = pem.replaceAll("\\s", "");
                byte[] der = Base64.getDecoder().decode(pem);
                PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
                result.put(id, pub);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load public keys from manifest " + manifestPath, e);
        }
    }
}
