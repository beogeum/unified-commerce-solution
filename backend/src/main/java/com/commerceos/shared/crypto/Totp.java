package com.commerceos.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP(HMAC-SHA1, 30초, 6자리). 일반 OTP 앱(Google Authenticator 등)과 호환된다. */
public final class Totp {
    public static final int PERIOD_SECONDS = 30;
    public static final int DIGITS = 6;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static String newSecret(SecureRandom random) {
        byte[] b = new byte[20];
        random.nextBytes(b);
        return base32Encode(b);
    }

    public static long step(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), PERIOD_SECONDS);
    }

    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("totp failed", e);
        }
    }

    /**
     * 앞뒤 한 구간(±30초)까지 허용한다. 이미 사용한 구간 이하의 코드는 거부(재사용 방지).
     * @return 일치한 구간 번호, 없으면 -1
     */
    public static long verify(String base32Secret, String code, Instant now, long lastUsedStep) {
        if (code == null) {
            return -1;
        }
        String c = code.replace(" ", "");
        if (!c.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        long current = step(now);
        for (long s = current - 1; s <= current + 1; s++) {
            if (s <= lastUsedStep) {
                continue;
            }
            if (MessageDigest.isEqual(code(base32Secret, s).getBytes(StandardCharsets.US_ASCII), c.getBytes(StandardCharsets.US_ASCII))) {
                return s;
            }
        }
        return -1;
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String in = s.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(in.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char ch : in.toCharArray()) {
            int v = ALPHABET.indexOf(ch);
            if (v < 0) {
                throw new IllegalArgumentException("invalid base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] r = new byte[out.position()];
        out.flip();
        out.get(r);
        return r;
    }
}
