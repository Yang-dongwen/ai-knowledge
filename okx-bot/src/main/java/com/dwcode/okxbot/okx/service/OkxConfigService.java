package com.dwcode.okxbot.okx.service;

import cn.hutool.crypto.symmetric.AES;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.okx.client.OkxRestClient;
import com.dwcode.okxbot.okx.config.OkxConfigEntity;
import com.dwcode.okxbot.okx.config.OkxConfigMapper;
import com.dwcode.okxbot.okx.dto.OkxConfigRequest;
import com.dwcode.okxbot.okx.dto.OkxConfigResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * OKX 配置服务。
 *
 * 职责：
 * 1. 保存 OKX 配置（加密存储）
 * 2. 测试 OKX 连接
 * 3. 查询 OKX 余额
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxConfigService {

    private final OkxConfigMapper okxConfigMapper;
    private final OkxRestClient okxRestClient;

    // 加密密钥，生产环境应从配置中心或环境变量获取
    private static final byte[] AES_KEY = "okxbot2024secret".getBytes(StandardCharsets.UTF_8);
    private final AES aes = new AES(AES_KEY);

    /**
     * 保存 OKX 配置。
     */
    public void saveOkxConfig(OkxConfigRequest request) {
        log.info("保存OKX配置, simulated={}", request.getSimulated());

        OkxConfigEntity entity = getOrCreateConfig();
        entity.setApiKeyMasked(maskApiKey(request.getApiKey()));
        entity.setApiKeyEncrypted(aes.encryptHex(request.getApiKey()));
        entity.setSecretKeyEncrypted(aes.encryptHex(request.getSecretKey()));
        entity.setPassphraseEncrypted(aes.encryptHex(request.getPassphrase()));
        entity.setSimulated(request.getSimulated());
        entity.setStatus("ENABLED");
        entity.setUpdatedAt(LocalDateTime.now());

        if (entity.getId() == null) {
            entity.setCreatedAt(LocalDateTime.now());
            okxConfigMapper.insert(entity);
        } else {
            okxConfigMapper.updateById(entity);
        }
    }

    /**
     * 查询 OKX 配置（脱敏）。
     */
    public OkxConfigResponse getOkxConfig() {
        OkxConfigEntity entity = getExistingConfig();
        if (entity == null) {
            return null;
        }
        OkxConfigResponse response = new OkxConfigResponse();
        response.setApiKeyMasked(entity.getApiKeyMasked());
        response.setSimulated(entity.getSimulated());
        response.setStatus(entity.getStatus());
        response.setLastCheckAt(entity.getLastCheckAt());
        response.setLastError(entity.getLastError());
        return response;
    }

    /**
     * 测试 OKX 连接。
     */
    public String testConnection() {
        OkxConfigEntity config = getExistingConfig();
        if (config == null) {
            throw new BusinessException(10001, "OKX配置不存在，请先保存配置");
        }

        String apiKey = aes.decryptStr(config.getApiKeyEncrypted());
        String secretKey = aes.decryptStr(config.getSecretKeyEncrypted());
        String passphrase = aes.decryptStr(config.getPassphraseEncrypted());

        log.debug("解密后 apiKey 长度={}, apiKey前4位={}", apiKey != null ? apiKey.length() : 0, apiKey != null && apiKey.length() >= 4 ? apiKey.substring(0, 4) : "null");

        try {
            JsonNode result = okxRestClient.get("/api/v5/account/balance", apiKey, secretKey, passphrase);
            config.setStatus("ENABLED");
            config.setLastCheckAt(LocalDateTime.now());
            config.setLastError(null);
            okxConfigMapper.updateById(config);
            log.info("OKX连接测试成功");
            return "连接成功";
        } catch (Exception e) {
            config.setStatus("ERROR");
            config.setLastCheckAt(LocalDateTime.now());
            config.setLastError(e.getMessage());
            okxConfigMapper.updateById(config);
            log.error("OKX连接测试失败: {}", e.getMessage());
            throw new BusinessException(10002, "OKX连接失败: " + e.getMessage());
        }
    }

    /**
     * 查询 OKX 余额。
     */
    public JsonNode queryBalance() {
        OkxConfigEntity config = getExistingConfig();
        if (config == null) {
            throw new BusinessException(10001, "OKX配置不存在");
        }

        String apiKey = aes.decryptStr(config.getApiKeyEncrypted());
        String secretKey = aes.decryptStr(config.getSecretKeyEncrypted());
        String passphrase = aes.decryptStr(config.getPassphraseEncrypted());

        JsonNode result = okxRestClient.get("/api/v5/account/balance", apiKey, secretKey, passphrase);
        return result.path("data");
    }

    /**
     * 获取解密后的 API 凭证（内部使用）。
     */
    public String[] getDecryptedCredentials() {
        OkxConfigEntity config = getExistingConfig();
        if (config == null) {
            throw new BusinessException(10001, "OKX配置不存在");
        }
        return new String[]{
                aes.decryptStr(config.getApiKeyEncrypted()),
                aes.decryptStr(config.getSecretKeyEncrypted()),
                aes.decryptStr(config.getPassphraseEncrypted())
        };
    }

    private OkxConfigEntity getOrCreateConfig() {
        OkxConfigEntity entity = getExistingConfig();
        return entity != null ? entity : new OkxConfigEntity();
    }

    private OkxConfigEntity getExistingConfig() {
        return okxConfigMapper.selectOne(new LambdaQueryWrapper<OkxConfigEntity>().last("LIMIT 1"));
    }

    /**
     * API Key 脱敏：只显示前4位和后4位。
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
