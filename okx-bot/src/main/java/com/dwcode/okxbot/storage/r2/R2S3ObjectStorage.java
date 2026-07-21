package com.dwcode.okxbot.storage.r2;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectMeta;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cloudflare R2 实现（S3 兼容 API + AWS SDK v2）。
 */
@Slf4j
public class R2S3ObjectStorage implements ObjectStoragePort {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties.R2 r2;

    public R2S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties.R2 r2) {
        this.s3 = s3;
        this.presigner = presigner;
        this.r2 = r2;
    }

    @Override
    public String providerId() {
        return "r2";
    }

    @Override
    public void put(String key, Path localFile, String contentType) {
        String k = normalizeKey(key);
        if (localFile == null || !Files.isRegularFile(localFile)) {
            throw new BusinessException(400, "put 源文件不存在: " + localFile);
        }
        try {
            long size = Files.size(localFile);
            String ct = contentType != null && !contentType.isBlank()
                    ? contentType
                    : LocalObjectStorage.guessContentType(localFile.getFileName().toString());
            long threshold = r2.getMultipartThresholdBytes();
            if (threshold > 0 && size >= threshold) {
                putMultipart(k, localFile, size, ct);
            } else {
                PutObjectRequest req = PutObjectRequest.builder()
                        .bucket(r2.getBucket())
                        .key(k)
                        .contentType(ct)
                        .contentLength(size)
                        .build();
                s3.putObject(req, RequestBody.fromFile(localFile));
                log.info("r2 put: key={}, bytes={}", k, size);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("R2 put 失败: " + k + " — " + e.getMessage());
        }
    }

    @Override
    public void putBytes(String key, byte[] data, String contentType) {
        String k = normalizeKey(key);
        if (data == null) {
            throw new BusinessException(400, "putBytes data 不能为 null");
        }
        try {
            String ct = contentType != null && !contentType.isBlank()
                    ? contentType
                    : LocalObjectStorage.guessContentType(k);
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(k)
                    .contentType(ct)
                    .contentLength((long) data.length)
                    .build();
            s3.putObject(req, RequestBody.fromBytes(data));
            log.info("r2 putBytes: key={}, bytes={}", k, data.length);
        } catch (Exception e) {
            throw new BusinessException("R2 putBytes 失败: " + k + " — " + e.getMessage());
        }
    }

    private void putMultipart(String key, Path localFile, long size, String contentType) throws IOException {
        long partSize = Math.max(5L * 1024 * 1024, r2.getMultipartPartSizeBytes());
        CreateMultipartUploadResponse created = s3.createMultipartUpload(b -> b
                .bucket(r2.getBucket())
                .key(key)
                .contentType(contentType));
        String uploadId = created.uploadId();
        List<CompletedPart> completed = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(localFile.toFile(), "r")) {
            int partNumber = 1;
            long position = 0;
            byte[] buffer = new byte[(int) Math.min(partSize, Integer.MAX_VALUE)];
            while (position < size) {
                int len = (int) Math.min(buffer.length, size - position);
                raf.seek(position);
                raf.readFully(buffer, 0, len);
                final int pn = partNumber;
                UploadPartResponse partResp = s3.uploadPart(UploadPartRequest.builder()
                        .bucket(r2.getBucket())
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(pn)
                        .contentLength((long) len)
                        .build(), RequestBody.fromBytes(copyOf(buffer, len)));
                completed.add(CompletedPart.builder()
                        .partNumber(pn)
                        .eTag(partResp.eTag())
                        .build());
                position += len;
                partNumber++;
            }
            s3.completeMultipartUpload(b -> b
                    .bucket(r2.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()));
            log.info("r2 multipart put: key={}, bytes={}, parts={}", key, size, completed.size());
        } catch (Exception e) {
            try {
                s3.abortMultipartUpload(b -> b
                        .bucket(r2.getBucket())
                        .key(key)
                        .uploadId(uploadId));
            } catch (Exception abortEx) {
                log.warn("abort multipart 失败: key={} — {}", key, abortEx.getMessage());
            }
            throw e;
        }
    }

    private static byte[] copyOf(byte[] buf, int len) {
        if (len == buf.length) {
            return buf;
        }
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }

    @Override
    public void getToFile(String key, Path localFile) {
        String k = normalizeKey(key);
        try {
            if (localFile.getParent() != null) {
                Files.createDirectories(localFile.getParent());
            }
            s3.getObject(GetObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(k)
                    .build(), ResponseTransformer.toFile(localFile));
        } catch (NoSuchKeyException e) {
            throw new BusinessException(404, "对象不存在: " + k);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BusinessException(404, "对象不存在: " + k);
            }
            throw new BusinessException("R2 getToFile 失败: " + k + " — " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("R2 getToFile 失败: " + k + " — " + e.getMessage());
        }
    }

    @Override
    public InputStream openStream(String key) {
        String k = normalizeKey(key);
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(k)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new BusinessException(404, "对象不存在: " + k);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BusinessException(404, "对象不存在: " + k);
            }
            throw new BusinessException("R2 openStream 失败: " + k + " — " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("R2 openStream 失败: " + k + " — " + e.getMessage());
        }
    }

    @Override
    public InputStream openStream(String key, long startInclusive, long endInclusive) {
        String k = normalizeKey(key);
        long start = Math.max(0, startInclusive);
        long end = Math.max(start, endInclusive);
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(k)
                    .range("bytes=" + start + "-" + end)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new BusinessException(404, "对象不存在: " + k);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BusinessException(404, "对象不存在: " + k);
            }
            if (e.statusCode() == 416) {
                throw new BusinessException(416, "Requested Range Not Satisfiable");
            }
            throw new BusinessException("R2 openStream(range) 失败: " + k + " — " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("R2 openStream(range) 失败: " + k + " — " + e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        return head(key).isPresent();
    }

    @Override
    public void delete(String key) {
        String k = normalizeKey(key);
        try {
            s3.deleteObject(b -> b.bucket(r2.getBucket()).key(k));
        } catch (Exception e) {
            throw new BusinessException("R2 delete 失败: " + k + " — " + e.getMessage());
        }
    }

    @Override
    public int deletePrefix(String prefix) {
        String pfx = normalizePrefix(prefix);
        int deleted = 0;
        String token = null;
        try {
            do {
                ListObjectsV2Request.Builder lb = ListObjectsV2Request.builder()
                        .bucket(r2.getBucket())
                        .prefix(pfx)
                        .maxKeys(1000);
                if (token != null) {
                    lb.continuationToken(token);
                }
                ListObjectsV2Response page = s3.listObjectsV2(lb.build());
                List<S3Object> objects = page.contents();
                if (objects != null && !objects.isEmpty()) {
                    List<ObjectIdentifier> ids = new ArrayList<>(objects.size());
                    for (S3Object o : objects) {
                        ids.add(ObjectIdentifier.builder().key(o.key()).build());
                    }
                    s3.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(r2.getBucket())
                            .delete(Delete.builder().objects(ids).quiet(true).build())
                            .build());
                    deleted += ids.size();
                }
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (token != null);
            log.info("r2 deletePrefix: prefix={}, count={}", pfx, deleted);
            return deleted;
        } catch (Exception e) {
            throw new BusinessException("R2 deletePrefix 失败: " + pfx + " — " + e.getMessage());
        }
    }

    @Override
    public Optional<ObjectMeta> head(String key) {
        String k = normalizeKey(key);
        try {
            HeadObjectResponse resp = s3.headObject(HeadObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(k)
                    .build());
            long lastMod = 0L;
            if (resp.lastModified() != null) {
                lastMod = resp.lastModified().toEpochMilli();
            }
            return Optional.of(ObjectMeta.builder()
                    .key(k)
                    .sizeBytes(resp.contentLength() != null ? resp.contentLength() : 0L)
                    .contentType(resp.contentType())
                    .lastModifiedEpochMs(lastMod)
                    .build());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new BusinessException("R2 head 失败: " + k + " — " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("R2 head 失败: " + k + " — " + e.getMessage());
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl, boolean attachment, String downloadName) {
        String k = normalizeKey(key);
        Duration useTtl = ttl != null ? ttl : Duration.ofSeconds(Math.max(60, r2.getPresignTtlSeconds()));
        try {
            GetObjectRequest.Builder gb = GetObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(k);
            if (attachment) {
                String name = (downloadName != null && !downloadName.isBlank())
                        ? downloadName.trim()
                        : k.substring(k.lastIndexOf('/') + 1);
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                gb.responseContentDisposition("attachment; filename*=UTF-8''" + encoded);
            }
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(useTtl)
                    .getObjectRequest(gb.build())
                    .build();
            return presigner.presignGetObject(presignRequest).url().toURI();
        } catch (Exception e) {
            throw new BusinessException("R2 presignGet 失败: " + k + " — " + e.getMessage());
        }
    }

    @Override
    public URI presignPut(String key, Duration ttl, String contentType) {
        String k = normalizeKey(key);
        Duration useTtl = ttl != null ? ttl : Duration.ofSeconds(Math.max(60, r2.getPresignTtlSeconds()));
        try {
            PutObjectRequest.Builder pb = PutObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(k);
            if (contentType != null && !contentType.isBlank()) {
                pb.contentType(contentType);
            }
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(useTtl)
                    .putObjectRequest(pb.build())
                    .build();
            return presigner.presignPutObject(presignRequest).url().toURI();
        } catch (Exception e) {
            throw new BusinessException("R2 presignPut 失败: " + k + " — " + e.getMessage());
        }
    }

    static String normalizeKey(String key) {
        return LocalObjectStorage.normalizeKey(key);
    }

    static String normalizePrefix(String prefix) {
        String p = normalizeKey(prefix);
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }
}
