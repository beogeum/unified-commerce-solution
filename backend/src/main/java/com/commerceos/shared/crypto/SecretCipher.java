package com.commerceos.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 연동 자격증명·계좌번호 등 서버측 암호화(AES-256-GCM). 키는 설정(운영은 Secret Store/KMS, OQ-027)에서 주입.
 * 암호문 형식: [12바이트 IV][암호문+태그]. 키 ID 는 별도 컬럼에 저장해 교체 가능하게 한다.
 */
@Component
public class SecretCipher {
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKeySpec> keys = new HashMap<>();
    private final String activeKeyId;
    private final SecretKeySpec hmacKey;

    public SecretCipher(CryptoProperties props) {
        if (props.getKeys() != null) {
            props.getKeys().forEach((id, b64) -> {
                if (b64 != null && !b64.isBlank()) {
                    keys.put(id, aesKey(b64));
                }
            });
        }
        if (keys.isEmpty() || props.getActiveKeyId() == null || props.getActiveKeyId().isBlank()) {
            throw new IllegalStateException("commerce.crypto.keys / active-key-id must be configured");
        }
        if (!keys.containsKey(props.getActiveKeyId())) {
            throw new IllegalStateException("active crypto key not found: " + props.getActiveKeyId());
        }
        this.activeKeyId = props.getActiveKeyId();
        if (props.getHmacKey() == null || props.getHmacKey().isBlank()) {
            throw new IllegalStateException("commerce.crypto.hmac-key must be configured");
        }
        this.hmacKey = new SecretKeySpec(Base64.getDecoder().decode(props.getHmacKey()), "HmacSHA256");
    }

    public record Encrypted(byte[] cipherText, String keyId) {
    }

    public Encrypted encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(ByteBuffer.allocate(IV_LEN + ct.length).put(iv).put(ct).array(), activeKeyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] data, String keyId) {
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("unknown crypto key id");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_LEN));
            return new String(c.doFinal(data, IV_LEN, data.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    /** 원문을 저장하지 않고 중복 여부만 판단하기 위한 키 기반 해시. */
    public byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private static SecretKeySpec aesKey(String b64) {
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length != 32) {
            throw new IllegalStateException("AES key must be 256-bit");
        }
        return new SecretKeySpec(raw, "AES");
    }

    @Component
    @ConfigurationProperties(prefix = "commerce.crypto")
    public static class CryptoProperties {
        private Map<String, String> keys = new HashMap<>();
        private String activeKeyId;
        private String hmacKey;

        public Map<String, String> getKeys() {
            return keys;
        }

        public void setKeys(Map<String, String> keys) {
            this.keys = keys;
        }

        public String getActiveKeyId() {
            return activeKeyId;
        }

        public void setActiveKeyId(String activeKeyId) {
            this.activeKeyId = activeKeyId;
        }

        public String getHmacKey() {
            return hmacKey;
        }

        public void setHmacKey(String hmacKey) {
            this.hmacKey = hmacKey;
        }
    }
}
