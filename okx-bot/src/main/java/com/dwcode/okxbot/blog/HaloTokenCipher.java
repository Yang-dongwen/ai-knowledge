package com.dwcode.okxbot.blog;

import com.dwcode.okxbot.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户 PAT 落库加密。格式 v1.{iv}.{ciphertext}，AES-256-GCM。
 */
public final class HaloTokenCipher {

    private static final String PREFIX = "v1.";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public HaloTokenCipher(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new BusinessException(503, "服务器未配置博客令牌加密密钥（HALO_TOKEN_SECRET）");
        }
        this.key = new SecretKeySpec(sha256(secret.trim()), "AES");
    }

    public String encrypt(String plain) {
        if (!StringUtils.hasText(plain)) {
            throw new BusinessException(400, "令牌不能为空");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            return PREFIX + enc.encodeToString(iv) + "." + enc.encodeToString(ct);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "加密博客令牌失败");
        }
    }

    public String decrypt(String cipherText) {
        if (!StringUtils.hasText(cipherText) || !cipherText.startsWith(PREFIX)) {
            throw new BusinessException(500, "博客令牌密文损坏，请重新关联");
        }
        try {
            String rest = cipherText.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) {
                throw new BusinessException(500, "博客令牌密文损坏，请重新关联");
            }
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] iv = dec.decode(rest.substring(0, dot));
            byte[] ct = dec.decode(rest.substring(dot + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "解密博客令牌失败，请重新关联");
        }
    }

    public static String mask(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        String t = token.trim();
        if (t.length() <= 8) {
            return "****";
        }
        return t.substring(0, 4) + "****" + t.substring(t.length() - 4);
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
