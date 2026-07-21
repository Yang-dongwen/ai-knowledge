package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import com.dwcode.okxbot.storage.dto.MediaUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * PR5：按 {@code storage.serve-mode} 签发 R2 预签名读 URL，或返回代理路径。
 * <p>仅当 provider=r2 且 path 已是 object key 时才 presign；本地路径 / local 存储一律 proxy。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaUrlService {

    private final ObjectStoragePort objectStorage;
    private final StorageProperties storageProperties;

    public enum ServeMode {
        PROXY, PRESIGN, HYBRID;

        static ServeMode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return PROXY;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "presign" -> PRESIGN;
                case "hybrid" -> HYBRID;
                default -> PROXY;
            };
        }
    }

    /**
     * @param objectKeyOrPath 对象 key 或本地绝对路径
     * @param proxyPath       同源代理 API 路径（以 /api 开头），presign 失败或本地时使用
     * @param attachment      true 时 Content-Disposition=attachment
     * @param downloadName    下载文件名
     */
    public MediaUrlResponse resolve(String objectKeyOrPath, String proxyPath,
                                    boolean attachment, String downloadName) {
        if (proxyPath == null || proxyPath.isBlank()) {
            throw new BusinessException(400, "proxyPath 不能为空");
        }
        ServeMode mode = ServeMode.from(storageProperties.getServeMode());
        int ttl = Math.max(60, storageProperties.getR2().getPresignTtlSeconds());

        boolean canPresign = "r2".equalsIgnoreCase(objectStorage.providerId())
                && objectKeyOrPath != null
                && !objectKeyOrPath.isBlank()
                && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(objectKeyOrPath)
                && (mode == ServeMode.PRESIGN || mode == ServeMode.HYBRID);

        if (canPresign) {
            try {
                if (!objectStorage.exists(objectKeyOrPath)) {
                    throw new BusinessException(404, "对象不存在: " + objectKeyOrPath);
                }
                URI uri = objectStorage.presignGet(
                        objectKeyOrPath,
                        Duration.ofSeconds(ttl),
                        attachment,
                        downloadName);
                long exp = System.currentTimeMillis() + ttl * 1000L;
                log.debug("media presign: key={}, ttl={}s", objectKeyOrPath, ttl);
                return MediaUrlResponse.builder()
                        .url(uri.toString())
                        .mode("presign")
                        .expiresAtMs(exp)
                        .ttlSeconds(ttl)
                        .objectKey(objectKeyOrPath)
                        .proxyPath(proxyPath)
                        .build();
            } catch (BusinessException e) {
                if (mode == ServeMode.PRESIGN && e.getCode() == 404) {
                    throw e;
                }
                log.warn("presign 失败，回退 proxy: key={} — {}", objectKeyOrPath, e.getMessage());
            } catch (Exception e) {
                log.warn("presign 异常，回退 proxy: key={} — {}", objectKeyOrPath, e.getMessage());
            }
        }

        return MediaUrlResponse.builder()
                .url(proxyPath)
                .mode("proxy")
                .expiresAtMs(0L)
                .ttlSeconds(0)
                .objectKey(ObjectKeyBuilder.looksLikeLocalAbsolutePath(objectKeyOrPath) ? null : objectKeyOrPath)
                .proxyPath(proxyPath)
                .build();
    }

    public boolean preferPresign() {
        ServeMode mode = ServeMode.from(storageProperties.getServeMode());
        return mode == ServeMode.PRESIGN || mode == ServeMode.HYBRID;
    }
}
