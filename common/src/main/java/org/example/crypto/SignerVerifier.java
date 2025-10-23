package org.example.crypto;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public final class SignerVerifier {
    private SignerVerifier() {
    }

    public static byte[] signEd25519(byte[] message, PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(message);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Signing failed", e);
        }
    }

    public static boolean verifyEd25519(byte[] message, byte[] signature, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
