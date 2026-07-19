package com.yonagi.verse.common.util;

import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 对称加密工具
 * 提供双向加解密（用于 email/phone 存储）和 SHA-256 查找哈希（用于等值查询）
 *
 * @author Yonagi
 */
@Component
public class AesUtil {

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH = 12; // bytes
    private static final int GCM_TAG_LENGTH = 128;  // bits

    private final SecretKey secretKey;
    private final byte[] pepper;
    private final SecureRandom secureRandom;

    public AesUtil(
            @Value("${verse.security.encryption.aes-key}") String base64Key,
            @Value("${verse.security.encryption.hash-pepper}") String base64Pepper) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES key must be 32 bytes (256 bits), got " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.pepper = Base64.getDecoder().decode(base64Pepper);
        this.secureRandom = new SecureRandom();
    }

    /**
     * AES-256-GCM 加密
     * @param plaintext 明文
     * @return Base64(nonce[12B] + ciphertext + tag[16B])
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // nonce + ciphertext (includes tag at end)
            byte[] combined = new byte[GCM_NONCE_LENGTH + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, GCM_NONCE_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_NONCE_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new ServerException(UserErrorCodeEnum.USER_ENCRYPT_ERROR.message(), e, UserErrorCodeEnum.USER_ENCRYPT_ERROR);
        }
    }

    /**
     * AES-256-GCM 解密
     * @param ciphertext Base64 编码的密文
     * @return 明文
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < GCM_NONCE_LENGTH) {
                throw new IllegalArgumentException("Invalid ciphertext: too short");
            }

            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            System.arraycopy(combined, 0, nonce, 0, GCM_NONCE_LENGTH);

            byte[] encrypted = new byte[combined.length - GCM_NONCE_LENGTH];
            System.arraycopy(combined, GCM_NONCE_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ServerException(UserErrorCodeEnum.USER_DECRYPT_ERROR.message(), e, UserErrorCodeEnum.USER_DECRYPT_ERROR);
        }
    }

    /**
     * SHA-256(plaintext + pepper)，用于数据库等值查询
     * @param plaintext 明文
     * @return 64 字符的十六进制哈希值
     */
    public String hashForLookup(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(plaintext.getBytes(StandardCharsets.UTF_8));
            md.update(pepper);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ServerException(UserErrorCodeEnum.USER_HASH_ERROR.message(), e, UserErrorCodeEnum.USER_HASH_ERROR);
        }
    }
}
