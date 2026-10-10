package io.xuanji.server.asset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证列加密（AES-256-GCM）：model_provider api_key / 连接器 credential 落库用。
 *
 * <p>密钥来源：环境变量 XUANJI_CRYPTO_KEY（任意非空字符串，SHA-256 派生 32 字节）；
 * 未配置时使用开发默认值——生产部署必须显式配置。密文格式：IV(12B) || ciphertext，Base64 存储。</p>
 */
@Component
public class CryptoUtil {

    private static final String ENV_KEY = "XUANJI_CRYPTO_KEY";
    private static final String DEV_FALLBACK = "xuanji-dev-only-key";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public CryptoUtil(@Value("${xuanji.crypto-key:}") String configuredKey) {
        String material = (configuredKey == null || configuredKey.isBlank())
                ? System.getenv(ENV_KEY) : configuredKey;
        if (material == null || material.isBlank()) {
            material = DEV_FALLBACK;
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("crypto key init failed", e);
        }
    }

    /** 明文 → Base64(IV || ciphertext) */
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** Base64(IV || ciphertext) → 明文；损坏返回 null */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] plain = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
