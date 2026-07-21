package com.dwcode.okxbot.common.web;

import com.dwcode.okxbot.common.exception.BusinessException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * HTTP Range（字节）支持：浏览器 &lt;video&gt; 边下边播 / 拖动进度条依赖 206 + Accept-Ranges。
 */
public final class MediaRangeSupport {

    private MediaRangeSupport() {
    }

    public record ByteRange(long start, long endInclusive, long total) {
        public long length() {
            return endInclusive - start + 1;
        }
    }

    /**
     * 解析 {@code Range: bytes=start-end}。仅支持单段；无法解析则 empty（按全量 200 响应）。
     */
    public static Optional<ByteRange> parse(String rangeHeader, long totalSize) {
        if (rangeHeader == null || rangeHeader.isBlank() || totalSize <= 0) {
            return Optional.empty();
        }
        String h = rangeHeader.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6)) {
            return Optional.empty();
        }
        String spec = h.substring(6).trim();
        // 多段不支持
        if (spec.contains(",")) {
            spec = spec.split(",")[0].trim();
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startStr.isEmpty()) {
                // bytes=-N → 最后 N 字节
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) {
                    return Optional.empty();
                }
                start = Math.max(0, totalSize - suffix);
                end = totalSize - 1;
            } else {
                start = Long.parseLong(startStr);
                end = endStr.isEmpty() ? totalSize - 1 : Long.parseLong(endStr);
            }
            if (start < 0 || start >= totalSize) {
                throw new BusinessException(416, "Requested Range Not Satisfiable");
            }
            end = Math.min(end, totalSize - 1);
            if (end < start) {
                throw new BusinessException(416, "Requested Range Not Satisfiable");
            }
            return Optional.of(new ByteRange(start, end, totalSize));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * @param openRange (startInclusive, endInclusive) → stream covering that range
     */
    public static ResponseEntity<Resource> build(
            String rangeHeader,
            long totalSize,
            String contentType,
            String filename,
            BiFunction<Long, Long, InputStream> openRange) {

        String ct = contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream";
        String safeName = filename != null && !filename.isBlank() ? filename : "media.bin";

        Optional<ByteRange> range = parse(rangeHeader, totalSize);
        if (range.isEmpty()) {
            long end = totalSize > 0 ? totalSize - 1 : Long.MAX_VALUE - 1;
            InputStream in = openRange.apply(0L, end);
            Resource body = resource(in, totalSize, safeName);
            ResponseEntity.BodyBuilder bb = ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(ct))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeName + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60");
            if (totalSize > 0) {
                bb = bb.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(totalSize));
            }
            return bb.body(body);
        }

        ByteRange r = range.get();
        InputStream in = openRange.apply(r.start(), r.endInclusive());
        Resource body = resource(in, r.length(), safeName);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(ct))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + r.start() + "-" + r.endInclusive() + "/" + r.total())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(r.length()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .body(body);
    }

    private static Resource resource(InputStream in, long contentLength, String filename) {
        final long len = contentLength;
        final String name = filename;
        return new InputStreamResource(in) {
            @Override
            public long contentLength() {
                return len > 0 ? len : -1;
            }

            @Override
            public String getFilename() {
                return name;
            }
        };
    }
}
