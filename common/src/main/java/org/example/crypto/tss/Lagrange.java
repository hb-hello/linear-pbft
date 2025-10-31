package org.example.crypto.tss;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class Lagrange {
    private static final BigInteger R = new BigInteger(
            "73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001", 16);

    public static Map<Integer,BigInteger> lambdasAtZero(Set<Integer> S) {
        Map<Integer,BigInteger> out = new HashMap<>();
        for (int i : S) {
            BigInteger num = BigInteger.ONE;
            BigInteger den = BigInteger.ONE;
            BigInteger xi = BigInteger.valueOf(i);
            for (int j : S) if (j != i) {
                BigInteger xj = BigInteger.valueOf(j);
                num = num.multiply(xj.negate().mod(R)).mod(R);          // -xj
                den = den.multiply(xi.subtract(xj).mod(R)).mod(R);       // (xi - xj)
            }
            out.put(i, num.multiply(den.modInverse(R)).mod(R));
        }
        return out;
    }
}