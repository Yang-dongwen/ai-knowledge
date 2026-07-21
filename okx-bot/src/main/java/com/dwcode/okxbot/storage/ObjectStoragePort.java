package com.dwcode.okxbot.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * 统一对象存储端口。
 * <p>业务模块通过本接口读写<strong>持久</strong>媒体；流水线临时文件用 {@link ScratchWorkspace}。
 */
public interface ObjectStoragePort {

    /**
     * 上传本地文件。
     *
     * @param key         对象 key（已由 ObjectKeyBuilder 生成）
     * @param localFile   本地文件
     * @param contentType MIME，可空则按扩展名猜测
     */
    void put(String key, Path localFile, String contentType);

    void putBytes(String key, byte[] data, String contentType);

    /**
     * 下载到本地文件（父目录自动创建）。
     */
    void getToFile(String key, Path localFile);

    /**
     * 打开只读流（全对象）；调用方负责关闭。
     */
    InputStream openStream(String key);

    /**
     * 打开字节区间流（含起止），用于 HTTP Range / 边下边播。
     * <p>{@code endInclusive} 可大于实际末字节，实现应截断到对象末尾。
     */
    InputStream openStream(String key, long startInclusive, long endInclusive);

    boolean exists(String key);

    void delete(String key);

    /**
     * 删除某前缀下全部对象（如整个 task）。
     *
     * @return 删除个数（约）
     */
    int deletePrefix(String prefix);

    Optional<ObjectMeta> head(String key);

    /**
     * 预签名 GET。local 实现不支持，抛业务异常。
     *
     * @param attachment   true 时倾向下载（Content-Disposition）
     * @param downloadName 下载文件名，可空
     */
    URI presignGet(String key, Duration ttl, boolean attachment, String downloadName);

    /**
     * 预签名 PUT（浏览器直传）。local 不支持。
     */
    URI presignPut(String key, Duration ttl, String contentType);

    /** 当前实现标识：local / r2 */
    String providerId();
}
