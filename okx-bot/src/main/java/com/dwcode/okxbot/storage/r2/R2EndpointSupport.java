package com.dwcode.okxbot.storage.r2;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;

import java.net.URI;
import java.util.Locale;

/**
 * Cloudflare R2 endpoint / 配置校验（无 SDK 依赖，便于单测）。
 */
public final class R2EndpointSupport {

    private R2EndpointSupport() {
    }

    public static void validate(StorageProperties.R2 r2) {
        if (r2 == null) {
            throw new BusinessException(500, "storage.r2 未配置");
        }
        if (isBlank(r2.getBucket())) {
            throw new BusinessException(500, "storage.r2.bucket 不能为空");
        }
        if (isBlank(r2.getAccessKeyId()) || isBlank(r2.getSecretAccessKey())) {
            throw new BusinessException(500,
                    "storage.r2.access-key-id / secret-access-key 不能为空（请用环境变量 R2_* 注入）");
        }
        String endpoint = resolveEndpoint(r2);
        if (isBlank(endpoint)) {
            throw new BusinessException(500,
                    "storage.r2.endpoint 为空且无法从 account-id 推导，请配置 endpoint 或 account-id");
        }
        try {
            URI.create(endpoint);
        } catch (Exception e) {
            throw new BusinessException(500, "storage.r2.endpoint 非法: " + endpoint);
        }
    }

    /**
     * 解析 R2 S3 API endpoint。
     * 优先 {@code storage.r2.endpoint}，否则
     * {@code https://{accountId}.r2.cloudflarestorage.com}
     */
    public static String resolveEndpoint(StorageProperties.R2 r2) {
        if (r2 == null) {
            return "";
        }
        if (!isBlank(r2.getEndpoint())) {
            String ep = r2.getEndpoint().trim();
            while (ep.endsWith("/")) {
                ep = ep.substring(0, ep.length() - 1);
            }
            return ep;
        }
        if (isBlank(r2.getAccountId())) {
            return "";
        }
        return "https://" + r2.getAccountId().trim() + ".r2.cloudflarestorage.com";
    }

    public static URI resolveEndpointUri(StorageProperties.R2 r2) {
        return URI.create(resolveEndpoint(r2));
    }

    public static String regionOrAuto(StorageProperties.R2 r2) {
        if (r2 == null || isBlank(r2.getRegion())) {
            return "auto";
        }
        return r2.getRegion().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
