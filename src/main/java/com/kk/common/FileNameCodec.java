package com.kk.common;

import com.kk.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class FileNameCodec {
    private final OssProperties ossProperties;

    private byte[] keyBytes() {
        String secret = ossProperties.getFilenameSecret();
        if (secret == null || secret.isBlank()) secret = "default-filename-secret";
        byte[] src = secret.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16]; // AES-128
        for (int i = 0; i < key.length; i++) key[i] = i < src.length ? src[i] : 0;
        return key;
    }

    public String encrypt(String filename) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes(), "AES"));
            byte[] enc = cipher.doFinal(filename.getBytes(StandardCharsets.UTF_8));
            return "f_" + Base64.getUrlEncoder().withoutPadding().encodeToString(enc);
        } catch (Exception e) {
            // fallback: url-safe base64 without AES
            return "b_" + Base64.getUrlEncoder().withoutPadding().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return encrypted;
        try {
            if (encrypted.startsWith("f_")) {
                String b64 = encrypted.substring(2);
                byte[] enc = Base64.getUrlDecoder().decode(b64);
                Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes(), "AES"));
                byte[] dec = cipher.doFinal(enc);
                return new String(dec, StandardCharsets.UTF_8);
            }
            if (encrypted.startsWith("b_")) {
                String b64 = encrypted.substring(2);
                return new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
            }
            return encrypted;
        } catch (Exception e) {
            return encrypted;
        }
    }
}

