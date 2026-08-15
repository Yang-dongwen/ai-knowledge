package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.blog.SlugUtil;
import com.dwcode.okxbot.blog.port.HaloAttachment;
import com.dwcode.okxbot.blog.port.HaloPostTerms;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.blog.port.HaloTerm;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.dto.BlogPublishOptionsResponse;
import com.dwcode.okxbot.kb.dto.BlogPublishRequest;
import com.dwcode.okxbot.kb.dto.HaloTermResponse;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.entity.KbFileEntity;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把知识库笔记发到 Halo，只回写弱引用字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbBlogPublishService {

    static final String KB_FILE_MARKER = "/api/v1/kb/files/";

    private final KbNoteMapper noteMapper;
    private final KbNoteService noteService;
    private final KbFileService fileService;
    private final HaloPublishPort haloPublishPort;

    public BlogPublishOptionsResponse options(Long id) {
        KbNoteEntity e = requireOwnedNote(id);
        List<HaloTermResponse> categories = haloPublishPort.listCategories().stream().map(this::toTerm).toList();
        List<HaloTermResponse> tags = haloPublishPort.listTags().stream().map(this::toTerm).toList();
        HaloPostTerms selected = StringUtils.hasText(e.getHaloPostName())
                ? haloPublishPort.getPostTerms(e.getHaloPostName())
                : HaloPostTerms.empty();
        return BlogPublishOptionsResponse.builder()
                .published(StringUtils.hasText(e.getHaloPermalink()))
                .permalink(e.getHaloPermalink())
                .categories(categories)
                .tags(tags)
                .selectedCategoryNames(selected.categoryNames())
                .selectedTagNames(selected.tagNames())
                .mediaCount(collectMediaIds(e).size())
                .build();
    }

    public NoteResponse publish(Long id) {
        return publish(id, null);
    }

    public NoteResponse publish(Long id, BlogPublishRequest request) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwnedNote(id);
        String title = StringUtils.hasText(e.getTitle()) ? e.getTitle() : "未命名";
        String rawType = "markdown".equalsIgnoreCase(e.getContentFormat()) ? "markdown" : "HTML";
        String original = e.getContent() == null ? "" : e.getContent();
        PreparedContent prepared = prepareContent(e, userId, original, rawType);

        List<String> categoryNames = request == null ? null : request.getCategoryNames();
        List<String> tagNames = request == null ? null : request.getTagNames();

        HaloPublishResult result = haloPublishPort.publish(new HaloPublishCommand(
                title,
                SlugUtil.fromTitle(title, "post-" + id),
                prepared.raw(),
                rawType,
                e.getHaloPostName(),
                categoryNames,
                tagNames,
                prepared.cover()));

        LocalDateTime now = LocalDateTime.now();
        e.setHaloPostName(result.postName());
        e.setHaloPermalink(result.publicUrl());
        e.setHaloPublishedAt(now);
        e.setUpdatedAt(now);
        noteMapper.updateById(e);

        log.info("kb note published to halo userId={} noteId={} post={} uploaded={}",
                userId, id, result.postName(), prepared.uploaded());
        NoteResponse resp = noteService.get(id);
        resp.setUnresolvedMedia(prepared.failed() > 0 || hasPrivateMedia(prepared.raw()));
        return resp;
    }

    private PreparedContent prepareContent(KbNoteEntity e, Long userId, String original, String rawType) {
        Set<Long> inlineIds = KbHaloContentRewriter.collectFileIds(original);
        Map<Long, KbFileEntity> bound = KbHaloContentRewriter.indexById(
                fileService.listEntitiesByNote(e.getId(), userId));

        Set<Long> toUpload = new LinkedHashSet<>(inlineIds);
        toUpload.addAll(bound.keySet());

        Map<Long, String> permalinks = new LinkedHashMap<>();
        int uploaded = 0;
        int failed = 0;
        for (Long fileId : toUpload) {
            KbFileEntity file = bound.get(fileId);
            if (file == null) {
                try {
                    file = fileService.requireOwnedEntity(fileId, userId);
                } catch (BusinessException ex) {
                    failed++;
                    log.warn("kb halo skip missing file noteId={} fileId={}: {}", e.getId(), fileId, ex.getMessage());
                    continue;
                }
            }
            try {
                byte[] bytes = fileService.readBytes(file);
                HaloAttachment att = haloPublishPort.upload(bytes, file.getOriginalName(), file.getContentType());
                permalinks.put(fileId, att.permalink());
                uploaded++;
            } catch (BusinessException ex) {
                if (inlineIds.contains(fileId)) {
                    throw new BusinessException(ex.getCode(),
                            "附件「" + file.getOriginalName() + "」上传博客失败: " + ex.getMessage());
                }
                failed++;
                log.warn("kb halo extra attach failed noteId={} fileId={}: {}",
                        e.getId(), fileId, ex.getMessage());
            }
        }

        String rewritten = KbHaloContentRewriter.replaceFileUrls(original, permalinks);
        List<KbFileEntity> extras = new ArrayList<>();
        for (KbFileEntity f : bound.values()) {
            if (!inlineIds.contains(f.getId()) && permalinks.containsKey(f.getId())) {
                extras.add(f);
            }
        }
        rewritten = KbHaloContentRewriter.appendExtraAttachments(
                rewritten, rawType, extras, f -> permalinks.get(f.getId()));
        String cover = KbHaloContentRewriter.firstImageUrl(rewritten);
        if (!StringUtils.hasText(cover)) {
            cover = KbHaloContentRewriter.firstBoundImagePermalink(bound.values(), permalinks);
        }
        return new PreparedContent(rewritten, uploaded, failed, cover);
    }

    private Set<Long> collectMediaIds(KbNoteEntity e) {
        Long userId = e.getUserId();
        Set<Long> ids = KbHaloContentRewriter.collectFileIds(e.getContent());
        for (KbFileEntity f : fileService.listEntitiesByNote(e.getId(), userId)) {
            ids.add(f.getId());
        }
        return ids;
    }

    private KbNoteEntity requireOwnedNote(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = noteMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId) || Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(404, "笔记不存在");
        }
        return e;
    }

    private HaloTermResponse toTerm(HaloTerm t) {
        return HaloTermResponse.builder().name(t.name()).displayName(t.displayName()).build();
    }

    static boolean hasPrivateMedia(String content) {
        return content != null && content.contains(KB_FILE_MARKER);
    }

    private record PreparedContent(String raw, int uploaded, int failed, String cover) {
    }
}
