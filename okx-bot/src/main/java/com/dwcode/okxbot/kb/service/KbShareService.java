package com.dwcode.okxbot.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.dto.PublicNoteResponse;
import com.dwcode.okxbot.kb.dto.ShareStatusResponse;
import com.dwcode.okxbot.kb.entity.KbFileEntity;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.entity.KbTagEntity;
import com.dwcode.okxbot.kb.mapper.KbFileMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbShareService {

    private static final Pattern FILE_ID_IN_CONTENT =
            Pattern.compile("(?:https?://[^/\"'\\s)]+)?/api/v1/kb/files/(\\d+)/content",
                    Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KbNoteMapper noteMapper;
    private final KbFileMapper fileMapper;
    private final SysUserMapper sysUserMapper;
    private final KbTagService tagService;
    private final ObjectStoragePort objectStorage;

    public ShareStatusResponse status(Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwnedActive(noteId, userId);
        return toStatus(e);
    }

    @Transactional
    public ShareStatusResponse enable(Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwnedActive(noteId, userId);
        String token = e.getShareToken();
        if (!StringUtils.hasText(token)) {
            token = newShareToken();
        }
        LocalDateTime now = LocalDateTime.now();
        noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, noteId)
                .eq(KbNoteEntity::getUserId, userId)
                .set(KbNoteEntity::getShareToken, token)
                .set(KbNoteEntity::getShareEnabled, 1)
                .set(KbNoteEntity::getShareEnabledAt, now)
                .set(KbNoteEntity::getUpdatedAt, now));
        e.setShareToken(token);
        e.setShareEnabled(1);
        e.setShareEnabledAt(now);
        log.info("kb share enabled noteId={} userId={}", noteId, userId);
        return toStatus(e);
    }

    @Transactional
    public ShareStatusResponse disable(Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        requireOwnedActive(noteId, userId);
        noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, noteId)
                .eq(KbNoteEntity::getUserId, userId)
                .set(KbNoteEntity::getShareEnabled, 0)
                .set(KbNoteEntity::getUpdatedAt, LocalDateTime.now()));
        KbNoteEntity e = noteMapper.selectById(noteId);
        log.info("kb share disabled noteId={} userId={}", noteId, userId);
        return toStatus(e);
    }

    @Transactional
    public ShareStatusResponse rotate(Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwnedActive(noteId, userId);
        String token = newShareToken();
        LocalDateTime now = LocalDateTime.now();
        boolean keepOn = Objects.equals(e.getShareEnabled(), 1);
        noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, noteId)
                .eq(KbNoteEntity::getUserId, userId)
                .set(KbNoteEntity::getShareToken, token)
                .set(KbNoteEntity::getShareEnabled, keepOn ? 1 : 0)
                .set(KbNoteEntity::getShareEnabledAt, keepOn ? now : e.getShareEnabledAt())
                .set(KbNoteEntity::getUpdatedAt, now));
        e.setShareToken(token);
        if (keepOn) {
            e.setShareEnabledAt(now);
        }
        log.info("kb share rotated noteId={} userId={}", noteId, userId);
        return toStatus(e);
    }

    public PublicNoteResponse getPublicByToken(String token) {
        KbNoteEntity e = requirePublicNote(token);
        String author = "作者";
        SysUserEntity user = sysUserMapper.selectById(e.getUserId());
        if (user != null) {
            if (StringUtils.hasText(user.getNickname())) {
                author = user.getNickname().trim();
            } else if (StringUtils.hasText(user.getEmail())) {
                String email = user.getEmail();
                int at = email.indexOf('@');
                author = at > 0 ? email.substring(0, at) : email;
            }
        }
        List<KbTagEntity> tags = tagService.tagsByNoteIds(e.getUserId(), List.of(e.getId()))
                .getOrDefault(e.getId(), List.of());
        // 公开页媒体改为公开路径，前端无需登录 token
        String content = rewriteContentForPublic(e.getContent(), token);

        return PublicNoteResponse.builder()
                .title(StringUtils.hasText(e.getTitle()) ? e.getTitle() : "未命名笔记")
                .content(content == null ? "" : content)
                .contentFormat(StringUtils.hasText(e.getContentFormat()) ? e.getContentFormat() : "html")
                .authorName(author)
                .updatedAt(e.getUpdatedAt())
                .publishedAt(e.getShareEnabledAt() != null ? e.getShareEnabledAt() : e.getUpdatedAt())
                .tags(tags.stream().map(KbTagEntity::getName).filter(StringUtils::hasText).toList())
                .build();
    }

    public ResponseEntity<InputStreamResource> streamPublicFile(String token, Long fileId) {
        KbNoteEntity note = requirePublicNote(token);
        KbFileEntity file = fileMapper.selectById(fileId);
        if (file == null || !Objects.equals(file.getUserId(), note.getUserId())) {
            log.warn("public file deny: not found or owner mismatch token={} fileId={}", token, fileId);
            throw new BusinessException(404, "文件不存在或未公开");
        }
        if (!isFileAllowedForNote(note, file)) {
            log.warn("public file deny: not linked/referenced noteId={} fileId={} fileNoteId={}",
                    note.getId(), fileId, file.getNoteId());
            throw new BusinessException(404, "文件不存在或未公开");
        }
        if (!StringUtils.hasText(file.getObjectKey()) || "pending".equals(file.getObjectKey())) {
            log.warn("public file deny: pending key fileId={}", fileId);
            throw new BusinessException(404, "文件内容不存在");
        }
        if (!objectStorage.exists(file.getObjectKey())) {
            log.warn("public file deny: object missing fileId={} key={}", fileId, file.getObjectKey());
            throw new BusinessException(404, "文件内容不存在");
        }
        InputStream raw = objectStorage.openStream(file.getObjectKey());
        java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw);
        byte[] header = peekHeader(in, 32);
        MediaType resolved = KbMediaTypes.resolve(file, header);
        // 与私有流一致：仅白名单类型 inline，活动内容强制 attachment + octet-stream
        boolean inline = KbMediaTypes.isSafeInline(resolved);
        MediaType mediaType = KbMediaTypes.responseMediaType(resolved);
        String safeName = file.getOriginalName() == null ? "file" : file.getOriginalName().replace("\"", "");
        String encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
        long len = file.getSizeBytes() != null ? file.getSizeBytes() : -1;
        var builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment")
                                + "; filename=\"" + safeName + "\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType);
        if (len >= 0) {
            builder = builder.contentLength(len);
        }
        return builder.body(new InputStreamResource(in));
    }

    private static byte[] peekHeader(java.io.BufferedInputStream in, int n) {
        try {
            in.mark(n + 8);
            byte[] buf = in.readNBytes(n);
            in.reset();
            return buf;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private boolean isFileAllowedForNote(KbNoteEntity note, KbFileEntity file) {
        if (file == null || note == null) {
            return false;
        }
        Long fileId = file.getId();
        // 1) 附件挂在该笔记上
        if (Objects.equals(file.getNoteId(), note.getId())) {
            return true;
        }
        String content = note.getContent();
        if (!StringUtils.hasText(content) || fileId == null) {
            return false;
        }
        // 2) 正文中引用文件 id（字符串包含，兼容各种 URL 形态，避免正则漏匹配）
        String idStr = String.valueOf(fileId);
        if (content.contains("/files/" + idStr + "/")
                || content.contains("/files/" + idStr + "/content")
                || content.contains("files/" + idStr + "/content")) {
            return true;
        }
        // 3) 正则再扫一遍
        return extractFileIds(content).contains(fileId);
    }

    private static Set<Long> extractFileIds(String content) {
        if (!StringUtils.hasText(content)) {
            return Set.of();
        }
        Matcher m = FILE_ID_IN_CONTENT.matcher(content);
        return m.results()
                .map(r -> {
                    try {
                        return Long.parseLong(r.group(1));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 将正文中的私有媒体路径改写为公开分享路径。
     * 覆盖：相对路径、带 host 的绝对路径、带 access_token 的 query、Markdown ![]() / HTML src/href。
     */
    static String rewriteContentForPublic(String content, String shareToken) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(shareToken)) {
            return content;
        }
        String pub = "/api/v1/kb/public/s/" + Matcher.quoteReplacement(shareToken) + "/files/$1/content";
        String s = content;
        // 绝对 URL：http(s)://host/api/v1/kb/files/{id}/content?...
        s = s.replaceAll(
                "https?://[^/\"'\\s)]+/api/v1/kb/files/(\\d+)/content(?:\\?[^\"'\\s)]*)?",
                pub);
        // 相对路径：/api/v1/kb/files/{id}/content?...
        s = s.replaceAll(
                "/api/v1/kb/files/(\\d+)/content(?:\\?[^\"'\\s)]*)?",
                pub);
        return s;
    }

    private KbNoteEntity requirePublicNote(String token) {
        if (!StringUtils.hasText(token) || token.length() < 8 || token.length() > 64) {
            throw new BusinessException(404, "分享不存在或已关闭");
        }
        // 仅允许 URL-safe 字符，防注入
        if (!token.matches("^[A-Za-z0-9_-]+$")) {
            throw new BusinessException(404, "分享不存在或已关闭");
        }
        KbNoteEntity e = noteMapper.selectOne(new LambdaQueryWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getShareToken, token.trim())
                .eq(KbNoteEntity::getShareEnabled, 1)
                .eq(KbNoteEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (e == null) {
            throw new BusinessException(404, "分享不存在或已关闭");
        }
        return e;
    }

    private KbNoteEntity requireOwnedActive(Long noteId, Long userId) {
        KbNoteEntity e = noteMapper.selectById(noteId);
        if (e == null || !Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
        if (Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(400, "回收站中的笔记无法分享，请先恢复");
        }
        return e;
    }

    private ShareStatusResponse toStatus(KbNoteEntity e) {
        boolean on = Objects.equals(e.getShareEnabled(), 1) && StringUtils.hasText(e.getShareToken());
        String path = on ? "/s/" + e.getShareToken() : null;
        return ShareStatusResponse.builder()
                .noteId(e.getId())
                .enabled(on)
                .shareToken(on ? e.getShareToken() : e.getShareToken())
                .sharePath(path)
                .enabledAt(e.getShareEnabledAt())
                .build();
    }

    private static String newShareToken() {
        byte[] buf = new byte[18];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

}
