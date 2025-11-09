package org.example.crypto.tss;

import com.google.protobuf.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.crypto.MessageAuthenticator;
import supranational.blst.*;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Threshold BLS (G1 pubkeys, G2 signatures) helper for PBFT messages.
 * - partialSign/verifyPartial are used on Prepare by backups and the collector.
 * - combine/verifyFinal are used by the collector for Commit and by recipients.
 */
public final class SignerVerifierTSS {

    private static final Logger logger = LogManager.getLogger(SignerVerifierTSS.class);

    private final ThresholdKeyManager km;          // org.example.crypto.tss.KeyManager that loads SecretKey and P1 pubkeys
    private final String selfId;
    private final String dst;             // fixed DST, e.g., "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_NUL_"
    private final BigInteger R;

    public SignerVerifierTSS(String selfId, ThresholdKeyManager thresholdKeyManager, String dst, BigInteger R) {
        this.selfId = Objects.requireNonNull(selfId);
        this.km = Objects.requireNonNull(thresholdKeyManager);
        this.dst = Objects.requireNonNull(dst);
        this.R = Objects.requireNonNull(R);
    }

    // Canonicalize by clearing signature-related fields present in our proto
    public Message clearForSigning(Message msg) {
//        logger.info("SignerVerifierTSS.clearForSigning: clearing message of type {}", msg.getDescriptorForType().getFullName());
        var b = msg.toBuilder();
        var d = msg.getDescriptorForType();
        // Common fields
        var fSignerId = d.findFieldByName("signer_id");          // current
        var fSignature = d.findFieldByName("signature");
        var fIsAggregated = d.findFieldByName("is_aggregated");

        if (fSignerId != null) b.clearField(fSignerId);
        if (fSignature != null) b.clearField(fSignature);
        if (fIsAggregated != null) b.clearField(fIsAggregated);

        return b.build();
    }

    // Prepare: create a partial signature for this node's share
    public byte[] partialSign(Message msg) {
        SecretKey sk = km.getPrivateKey();
        byte[] bytes = clearForSigning(msg).toByteArray();
        // Sign with this node's share secret key
//        logger.info("SignerVerifierTSS.partialSign: signing message of {} bytes", bytes.length);
        P2 partialSig = new P2();
        return partialSig.hash_to(bytes, dst).sign_with(sk).serialize();
    }

    // Prepare: verify a partial signature from signerId using its share pubkey (from manifest)
    public boolean verifyPartial(Message msg, byte[] partialSig, String signerId) {
        try {
            P1_Affine pkShareAffine = km.getPublicKeyShare(signerId);
            if(!pkShareAffine.in_group()) return false;
            byte[] bytes = clearForSigning(msg).toByteArray();
            P2_Affine partialSigAffine = new P2_Affine(partialSig);
            if(!partialSigAffine.in_group()) return false;

            Pairing ctx = new Pairing(true, dst);
            ctx.aggregate(pkShareAffine, partialSigAffine, bytes);
            ctx.commit();
            return ctx.finalverify();
        } catch (Exception e) {
            logger.error("SignerVerifierTSS.verifyPartial: exception verifying partial from {}: {}",
                    signerId, e.getMessage());
            return false;
        }
    }

    // Commit (collector): combine partials from indices -> partialSig to a single G2 aggregated signature
    public byte[] combine(Map<Integer, byte[]> indexToPartial) {

        // Compute Lagrange coefficients λ_i(0) over Fr for the provided indices.
        Set<Integer> S = indexToPartial.keySet();
        Map<Integer, BigInteger> lambdas = Lagrange.lambdasAtZero(S);

        P2 acc = null;
        for (int idx : S) {
            byte[] sigBytes = indexToPartial.get(idx);
            if (sigBytes == null || sigBytes.length == 0)
                throw new IllegalArgumentException("missing partial for index " + idx);

            // Deserialize and group-check each partial in G2.
            P2 sig = new P2(sigBytes);
            if (!(new P2_Affine(sig)).in_group())
                throw new IllegalArgumentException("partial not on G2 for index " + idx);

            // Scale σ_i by λ_i(0) in Fr, then add to the accumulator.
            BigInteger lam = lambdas.get(idx);
            if (lam == null) throw new IllegalStateException("missing lambda for index " + idx);
            SecretKey lamSk = new SecretKey();
            lamSk.from_bendian(frTo32be(lam)); // interpret λ as Fr element
            P2 scaled = sig.sign_with(lamSk);                                    // λ_i * σ_i in G2
            acc = (acc == null) ? scaled : acc.add(scaled);                // point-add aggregation
        }

        if (acc == null) throw new IllegalStateException("no accumulator");
        return acc.compress(); // compressed G2 aggregate
    }

    // Reduce to Fr and encode as 32-byte big-endian for SecretKey.from_bendian.
    private byte[] frTo32be(BigInteger v) {
        BigInteger x = v.mod(R);
        byte[] tmp = x.toByteArray(); // big-endian, may have leading 0x00
        byte[] out = new byte[32];
        int srcPos = Math.max(0, tmp.length - 32);
        int len = Math.min(32, tmp.length);
        System.arraycopy(tmp, srcPos, out, 32 - len, len);
        return out;
    }

    // Commit (recipient): verify the aggregate against the master public key
    public boolean verifyFinal(Message msg, byte[] aggregateSig) {
        P1_Affine masterPublicKeyAffine = km.getMasterPublicKey();
        if(!masterPublicKeyAffine.in_group()) return false;

        P2_Affine aggregateSigAffine = new P2_Affine(aggregateSig);
        if(!aggregateSigAffine.in_group()) return false;

        byte[] bytes = clearForSigning(msg).toByteArray();

        Pairing ctx = new Pairing(true, dst);
        ctx.aggregate(masterPublicKeyAffine, aggregateSigAffine, bytes);
        ctx.commit();
        return ctx.finalverify();
    }

    // New helper: combine partials and verify against master key. Returns aggregate if valid; throws otherwise.
//    public byte[] combineAndVerify(Message msg, Map<Integer, byte[]> indexToPartial) {
//        byte[] agg = combine(indexToPartial);
//        boolean ok = verifyFinal(msg, agg);
//        if (!ok) throw new IllegalStateException("Aggregate signature failed verification");
//        return agg;
//    }
}