package org.example.crypto.tss;

import supranational.blst.P1;
import supranational.blst.SecretKey;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

public final class ThresholdKeyGenerator {
    private static final BigInteger R = new BigInteger(
            "73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001", 16);
    private static final SecureRandom RNG = new SecureRandom();

    static final class Poly {
        final List<BigInteger> a; // a0..a_{t-1}
        Poly(List<BigInteger> coeffs) { this.a = coeffs; }
        BigInteger eval(BigInteger x) {
            BigInteger y = BigInteger.ZERO, pow = BigInteger.ONE;
            for (BigInteger c : a) {
                y = y.add(c.multiply(pow)).mod(R);
                pow = pow.multiply(x).mod(R);
            }
            return y;
        }
    }

    private static BigInteger rnd() {
        byte[] b = new byte[48]; // 384-bit to reduce/reject > R
        BigInteger v;
        do { RNG.nextBytes(b); v = new BigInteger(1, b).mod(R); } while (v.signum()==0);
        return v;
    }

    public static ThresholdArtifacts generate(int t, Map<String,Integer> nodeIndex) {
        if (t < 1 || t > nodeIndex.size()) throw new IllegalArgumentException("bad t");
        List<BigInteger> coeffs = new ArrayList<>(t);
        for (int k=0; k<t; k++) coeffs.add(rnd());
        Poly f = new Poly(coeffs);
        BigInteger s = coeffs.get(0);

        byte[] sBytes = to32be(s);
        SecretKey skMaster = new SecretKey();
        skMaster.from_bendian(sBytes);
        byte[] masterPk = new P1(skMaster).serialize(); // compressed G1

        Map<String,byte[]> shareSk = new HashMap<>();
        Map<String,byte[]> sharePk = new HashMap<>();
        for (var e : nodeIndex.entrySet()) {
            BigInteger x = BigInteger.valueOf(e.getValue().longValue());
            BigInteger si = f.eval(x);
            byte[] siBytes = to32be(si);
            SecretKey skShare = new SecretKey();
            skShare.from_bendian(siBytes);
            shareSk.put(e.getKey(), siBytes);
            sharePk.put(e.getKey(), new P1(skShare).serialize());
        }
        return new ThresholdArtifacts(t, nodeIndex.size(), masterPk, shareSk, sharePk);
    }

    private static byte[] to32be(BigInteger v) {
        byte[] tmp = v.toByteArray();
        byte[] out = new byte[32];
        // copy least-significant 32 bytes big-endian
        int srcPos = Math.max(0, tmp.length - 32);
        int len = Math.min(32, tmp.length);
        System.arraycopy(tmp, srcPos, out, 32 - len, len);
        return out;
    }
}