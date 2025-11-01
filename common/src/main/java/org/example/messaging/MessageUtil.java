package org.example.messaging;

import com.google.protobuf.Message;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MessageUtil {

    public static byte[] generateDigest(Message message) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(message.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    public static boolean verifyDigest(Message message, byte[] digestToCheck) {
        byte[] actualDigest = generateDigest(message);
        if (actualDigest.length != digestToCheck.length) {
            return false;
        }
        for (int i = 0; i < actualDigest.length; i++) {
            if (actualDigest[i] != digestToCheck[i]) {
                return false;
            }
        }
        return true;
    }
}
