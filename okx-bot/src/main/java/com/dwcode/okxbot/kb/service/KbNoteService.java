package com.dwcode.okxbot.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.dto.NoteCreateRequest;
import com.dwcode.okxbot.kb.dto.NotePageResponse;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.dto.NoteRevisionResponse;
import com.dwcode.okxbot.kb.dto.NoteUpdateRequest;
import com.dwcode.okxbot.kb.dto.TagBrief;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.entity.KbNoteRevisionEntity;
import com.dwcode.okxbot.kb.entity.KbNoteTagEntity;
import com.dwcode.okxbot.kb.entity.KbTagEntity;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteRevisionMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbNoteService {

    private static final Pattern FILE_ID_IN_CONTENT =
            Pattern.compile("/api/v1/kb/files/(\\d+)/content", Pattern.CASE_INSENSITIVE);

    private final KbNoteMapper noteMapper;
    private final KbNoteTagMapper noteTagMapper;
    private final KbNoteRevisionMapper revisionMapper;
    private final KbTagService tagService;
    private final KbCategoryService categoryService;
    private final KbFileService fileService;
    private final KbProperties kbProperties;

    @Transactional
    public NoteResponse create(NoteCreateRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String content = normalizeContent(req.getContent());
        String title = resolveTitle(req.getTitle());
        Long categoryId = resolveCategoryId(userId, req.getCategoryId());
        List<Long> tagIds = tagService.validateOwnedTagIds(userId, req.getTagIds());

        String format = resolveFormat(req.getContentFormat());
        String plain = toPlainText(content, format);
        KbNoteEntity e = new KbNoteEntity();
        e.setUserId(userId);
        e.setTitle(title);
        e.setContent(content);
        e.setContentFormat(format);
        e.setSnippet(truncatePlain(plain, kbProperties.getNote().getSnippetChars()));
        e.setContentText(plain);
        e.setCategoryId(categoryId);
        e.setSortOrder(0);
        e.setIsPinned(Boolean.TRUE.equals(req.getPinned()) ? 1 : 0);
        e.setIsDeleted(0);
        noteMapper.insert(e);
        tagService.replaceNoteTags(e.getId(), tagIds);
        // 不再 selectById 整篇重读
        return toResponse(e, true, null);
    }

    @Transactional
    public NoteResponse update(Long id, NoteUpdateRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwned(id, userId, false);
        if (Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(400, "已删除的笔记无法编辑，请先恢复");
        }

        // 只用 UpdateWrapper 更新业务字段，且强制 is_deleted=0，
        // 避免 updateById 整行写回时把并发软删盖回 0（回收站有、外面还在）
        LambdaUpdateWrapper<KbNoteEntity> uw = new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, id)
                .eq(KbNoteEntity::getUserId, userId)
                .eq(KbNoteEntity::getIsDeleted, 0);

        boolean any = false;
        if (req.getTitle() != null) {
            String title = resolveTitle(req.getTitle());
            e.setTitle(title);
            uw.set(KbNoteEntity::getTitle, title);
            any = true;
        }
        if (req.getContent() != null) {
            String content = normalizeContent(req.getContent());
            String format = req.getContentFormat() != null
                    ? resolveFormat(req.getContentFormat())
                    : (StringUtils.hasText(e.getContentFormat()) ? e.getContentFormat() : "html");
            // 正文变更前写入版本快照
            if (!Objects.equals(nullToEmpty(e.getContent()), content)
                    || !Objects.equals(nullToEmpty(e.getContentFormat()), format)) {
                saveRevisionSnapshot(e, "save");
            }
            String plain = toPlainText(content, format);
            String snippet = truncatePlain(plain, kbProperties.getNote().getSnippetChars());
            e.setContent(content);
            e.setContentFormat(format);
            e.setSnippet(snippet);
            e.setContentText(plain);
            uw.set(KbNoteEntity::getContent, content);
            uw.set(KbNoteEntity::getContentFormat, format);
            uw.set(KbNoteEntity::getSnippet, snippet);
            uw.set(KbNoteEntity::getContentText, plain);
            any = true;
        } else if (req.getContentFormat() != null) {
            String format = resolveFormat(req.getContentFormat());
            String plain = toPlainText(e.getContent(), format);
            String snippet = truncatePlain(plain, kbProperties.getNote().getSnippetChars());
            e.setContentFormat(format);
            e.setSnippet(snippet);
            e.setContentText(plain);
            uw.set(KbNoteEntity::getContentFormat, format);
            uw.set(KbNoteEntity::getSnippet, snippet);
            uw.set(KbNoteEntity::getContentText, plain);
            any = true;
        }
        if (Boolean.TRUE.equals(req.getClearCategory())) {
            e.setCategoryId(null);
            uw.set(KbNoteEntity::getCategoryId, null);
            any = true;
        } else if (req.getCategoryId() != null) {
            Long categoryId = resolveCategoryId(userId, req.getCategoryId());
            e.setCategoryId(categoryId);
            uw.set(KbNoteEntity::getCategoryId, categoryId);
            any = true;
        }
        if (req.getPinned() != null) {
            int pinned = Boolean.TRUE.equals(req.getPinned()) ? 1 : 0;
            e.setIsPinned(pinned);
            uw.set(KbNoteEntity::getIsPinned, pinned);
            any = true;
        }

        if (any) {
            uw.set(KbNoteEntity::getUpdatedAt, LocalDateTime.now());
            int rows = noteMapper.update(null, uw);
            if (rows == 0) {
                throw new BusinessException(404, "笔记不存在或已在回收站，无法保存");
            }
            e.setUpdatedAt(LocalDateTime.now());
        }

        if (req.getTagIds() != null) {
            List<Long> tagIds = tagService.validateOwnedTagIds(userId, req.getTagIds());
            tagService.replaceNoteTags(id, tagIds);
        }
        return toResponse(e, true, null);
    }

    public NoteResponse get(Long id) {
        return getTimed(id).response();
    }

    /**
     * 详情查询（带分段耗时，便于判断慢在 DB 还是组装）。
     */
    public TimedNote getTimed(Long id) {
        long t0 = System.nanoTime();
        Long userId = SecurityUtils.requireCurrentUserId();
        long tAuth = System.nanoTime();

        KbNoteEntity e = requireOwned(id, userId, true);
        // 旧数据懒补 content_text，使历史笔记也能被正文检索
        ensureContentText(e);
        long tDb = System.nanoTime();
        int contentChars = e.getContent() == null ? 0 : e.getContent().length();

        NoteResponse resp = toResponse(e, true, null);
        long tBuild = System.nanoTime();

        long authMs = (tAuth - t0) / 1_000_000L;
        long dbMs = (tDb - tAuth) / 1_000_000L;
        long buildMs = (tBuild - tDb) / 1_000_000L;
        long totalMs = (tBuild - t0) / 1_000_000L;

        if (totalMs >= 200 || contentChars >= 100_000) {
            log.warn("kb note get timed id={} totalMs={} authMs={} dbMs={} buildMs={} contentChars={} userId={}",
                    id, totalMs, authMs, dbMs, buildMs, contentChars, userId);
        } else {
            log.debug("kb note get timed id={} totalMs={} dbMs={} contentChars={}",
                    id, totalMs, dbMs, contentChars);
        }
        return new TimedNote(resp, totalMs, authMs, dbMs, buildMs, contentChars);
    }

    public record TimedNote(
            NoteResponse response,
            long totalMs,
            long authMs,
            long dbMs,
            long buildMs,
            int contentChars
    ) {}

    public NotePageResponse list(int page, int size, Long categoryId, Long tagId,
                                 String keyword, boolean includeDeleted, boolean uncategorized,
                                 boolean onlyDeleted) {
        return list(page, size, categoryId, tagId, keyword, includeDeleted, uncategorized, onlyDeleted, false);
    }

    public NotePageResponse list(int page, int size, Long categoryId, Long tagId,
                                 String keyword, boolean includeDeleted, boolean uncategorized,
                                 boolean onlyDeleted, boolean onlyPinned) {
        Long userId = SecurityUtils.requireCurrentUserId();
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        final boolean hasKeyword = StringUtils.hasText(keyword);
        final String searchKw = hasKeyword ? normalizeKeyword(keyword) : null;

        LambdaQueryWrapper<KbNoteEntity> q = new LambdaQueryWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getUserId, userId);
        // 列表绝不加载 content LONGTEXT；有关键词时带 content_text 做命中高亮
        if (hasKeyword) {
            q.select(
                    KbNoteEntity::getId,
                    KbNoteEntity::getUserId,
                    KbNoteEntity::getTitle,
                    KbNoteEntity::getContentFormat,
                    KbNoteEntity::getSnippet,
                    KbNoteEntity::getContentText,
                    KbNoteEntity::getCategoryId,
                    KbNoteEntity::getIsPinned,
                    KbNoteEntity::getIsDeleted,
                    KbNoteEntity::getDeletedAt,
                    KbNoteEntity::getCreatedAt,
                    KbNoteEntity::getUpdatedAt
            );
        } else {
            q.select(
                    KbNoteEntity::getId,
                    KbNoteEntity::getUserId,
                    KbNoteEntity::getTitle,
                    KbNoteEntity::getContentFormat,
                    KbNoteEntity::getSnippet,
                    KbNoteEntity::getCategoryId,
                    KbNoteEntity::getIsPinned,
                    KbNoteEntity::getIsDeleted,
                    KbNoteEntity::getDeletedAt,
                    KbNoteEntity::getCreatedAt,
                    KbNoteEntity::getUpdatedAt
            );
        }

        if (onlyDeleted) {
            q.eq(KbNoteEntity::getIsDeleted, 1);
        } else if (!includeDeleted) {
            q.eq(KbNoteEntity::getIsDeleted, 0);
        }

        if (onlyPinned && !onlyDeleted) {
            q.eq(KbNoteEntity::getIsPinned, 1);
        }

        // 回收站列表不按分类/标签过滤（已删笔记仍可带 categoryId）
        if (!onlyDeleted) {
            if (uncategorized) {
                q.isNull(KbNoteEntity::getCategoryId);
            } else if (categoryId != null) {
                categoryService.requireOwned(categoryId, userId);
                q.eq(KbNoteEntity::getCategoryId, categoryId);
            }

            if (tagId != null) {
                List<Long> noteIds = tagService.noteIdsByTag(userId, tagId);
                if (noteIds.isEmpty()) {
                    return NotePageResponse.builder()
                            .items(List.of())
                            .total(0)
                            .page(p)
                            .size(s)
                            .build();
                }
                q.in(KbNoteEntity::getId, noteIds);
            }
        }

        if (hasKeyword) {
            final String search = searchKw;
            // 搜标题 + 纯文本副本 + 摘要（旧数据 content_text 可能为空时靠 snippet 兜底）
            // 不对 content LONGTEXT 做 LIKE
            q.and(w -> w.like(KbNoteEntity::getTitle, search)
                    .or()
                    .like(KbNoteEntity::getContentText, search)
                    .or()
                    .like(KbNoteEntity::getSnippet, search));
        }

        // 默认：置顶优先，再按最后修改时间降序（回收站按删除时间）
        if (onlyDeleted) {
            q.orderByDesc(KbNoteEntity::getDeletedAt)
                    .orderByDesc(KbNoteEntity::getUpdatedAt)
                    .orderByDesc(KbNoteEntity::getId);
        } else {
            q.orderByDesc(KbNoteEntity::getIsPinned)
                    .orderByDesc(KbNoteEntity::getUpdatedAt)
                    .orderByDesc(KbNoteEntity::getId);
        }

        Page<KbNoteEntity> pageResult = noteMapper.selectPage(new Page<>(p + 1L, s), q);
        List<KbNoteEntity> records = pageResult.getRecords();
        if (records.isEmpty()) {
            return NotePageResponse.builder()
                    .items(List.of())
                    .total(pageResult.getTotal())
                    .page(p)
                    .size(s)
                    .build();
        }

        List<Long> ids = records.stream().map(KbNoteEntity::getId).toList();
        Map<Long, List<KbTagEntity>> tagsMap = tagService.tagsByNoteIds(userId, ids);
        Map<Long, String> catNames = categoryService.nameMap(userId,
                records.stream().map(KbNoteEntity::getCategoryId).filter(Objects::nonNull).toList());

        int radius = kbProperties.getSearch().getHighlightRadius();
        List<NoteResponse> items = records.stream()
                .map(n -> {
                    String match = hasKeyword
                            ? buildMatchSnippet(n.getTitle(), n.getContentText(), n.getSnippet(), searchKw, radius)
                            : null;
                    return toResponse(n, false, tagsMap.getOrDefault(n.getId(), List.of()),
                            catNames.get(n.getCategoryId()), match);
                })
                .toList();

        return NotePageResponse.builder()
                .items(items)
                .total(pageResult.getTotal())
                .page(p)
                .size(s)
                .build();
    }

    /**
     * 复制笔记（不含附件二进制复制，仅复制正文与标签；附件需用户重新上传）。
     */
    @Transactional
    public NoteResponse duplicate(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity src = requireOwned(id, userId, false);
        if (Objects.equals(src.getIsDeleted(), 1)) {
            throw new BusinessException(400, "已删除的笔记无法复制，请先恢复");
        }
        NoteCreateRequest req = new NoteCreateRequest();
        String title = src.getTitle() == null ? "未命名笔记" : src.getTitle();
        if (!title.endsWith(" (副本)") && title.length() <= 190) {
            title = title + " (副本)";
        }
        req.setTitle(title);
        req.setContent(src.getContent());
        req.setContentFormat(src.getContentFormat());
        req.setCategoryId(src.getCategoryId());
        req.setPinned(false);
        List<KbTagEntity> tags = tagService.tagsByNoteIds(userId, List.of(id)).getOrDefault(id, List.of());
        if (!tags.isEmpty()) {
            req.setTagIds(tags.stream().map(KbTagEntity::getId).toList());
        }
        return create(req);
    }

    /**
     * 导出单篇笔记为 Markdown 文本（html 会降级为纯文本包裹）。
     */
    public String exportMarkdown(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwned(id, userId, true);
        String title = StringUtils.hasText(e.getTitle()) ? e.getTitle() : "未命名笔记";
        String format = StringUtils.hasText(e.getContentFormat()) ? e.getContentFormat() : "markdown";
        String body = e.getContent() == null ? "" : e.getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        if ("html".equalsIgnoreCase(format)) {
            sb.append(toPlainText(body, "html"));
        } else {
            // 去掉可能重复的首行标题
            String trimmed = body.stripLeading();
            if (trimmed.startsWith("#")) {
                int nl = trimmed.indexOf('\n');
                if (nl > 0) {
                    trimmed = trimmed.substring(nl + 1).stripLeading();
                }
            }
            sb.append(trimmed);
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.toString();
    }

    public List<NoteRevisionResponse> listRevisions(Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        requireOwned(noteId, userId, true);
        List<KbNoteRevisionEntity> list = revisionMapper.selectList(
                new LambdaQueryWrapper<KbNoteRevisionEntity>()
                        .eq(KbNoteRevisionEntity::getNoteId, noteId)
                        .eq(KbNoteRevisionEntity::getUserId, userId)
                        .orderByDesc(KbNoteRevisionEntity::getCreatedAt)
                        .orderByDesc(KbNoteRevisionEntity::getId)
                        .last("LIMIT 50"));
        List<NoteRevisionResponse> out = new ArrayList<>();
        for (KbNoteRevisionEntity r : list) {
            String fmt = StringUtils.hasText(r.getContentFormat()) ? r.getContentFormat() : "html";
            out.add(NoteRevisionResponse.builder()
                    .id(r.getId())
                    .noteId(r.getNoteId())
                    .title(r.getTitle())
                    .contentFormat(fmt)
                    .source(r.getSource())
                    .snippet(buildSnippet(r.getContent(), fmt))
                    .createdAt(r.getCreatedAt())
                    .build());
        }
        return out;
    }

    public NoteRevisionResponse getRevision(Long noteId, Long revisionId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        requireOwned(noteId, userId, true);
        KbNoteRevisionEntity r = revisionMapper.selectById(revisionId);
        if (r == null || !Objects.equals(r.getNoteId(), noteId) || !Objects.equals(r.getUserId(), userId)) {
            throw new BusinessException(404, "版本不存在");
        }
        String fmt = StringUtils.hasText(r.getContentFormat()) ? r.getContentFormat() : "html";
        return NoteRevisionResponse.builder()
                .id(r.getId())
                .noteId(r.getNoteId())
                .title(r.getTitle())
                .content(r.getContent())
                .contentFormat(fmt)
                .source(r.getSource())
                .snippet(buildSnippet(r.getContent(), fmt))
                .createdAt(r.getCreatedAt())
                .build();
    }

    @Transactional
    public NoteResponse restoreRevision(Long noteId, Long revisionId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwned(noteId, userId, false);
        if (Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(400, "已删除的笔记无法恢复版本，请先从回收站恢复");
        }
        KbNoteRevisionEntity r = revisionMapper.selectById(revisionId);
        if (r == null || !Objects.equals(r.getNoteId(), noteId) || !Objects.equals(r.getUserId(), userId)) {
            throw new BusinessException(404, "版本不存在");
        }
        // 当前内容先入版本库
        saveRevisionSnapshot(e, "restore");
        String format = resolveFormat(r.getContentFormat());
        String content = normalizeContent(r.getContent());
        String title = resolveTitle(r.getTitle());
        String plain = toPlainText(content, format);
        String snippet = truncatePlain(plain, kbProperties.getNote().getSnippetChars());
        LocalDateTime now = LocalDateTime.now();
        int rows = noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, noteId)
                .eq(KbNoteEntity::getUserId, userId)
                .eq(KbNoteEntity::getIsDeleted, 0)
                .set(KbNoteEntity::getTitle, title)
                .set(KbNoteEntity::getContent, content)
                .set(KbNoteEntity::getContentFormat, format)
                .set(KbNoteEntity::getSnippet, snippet)
                .set(KbNoteEntity::getContentText, plain)
                .set(KbNoteEntity::getUpdatedAt, now));
        if (rows == 0) {
            throw new BusinessException(404, "笔记不存在或已在回收站");
        }
        e.setTitle(title);
        e.setContent(content);
        e.setContentFormat(format);
        e.setSnippet(snippet);
        e.setContentText(plain);
        e.setUpdatedAt(now);
        return toResponse(e, true, null);
    }

    @Transactional
    public void delete(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        // 只改软删字段，绝不 updateById 整实体（防止与保存并发把 is_deleted 写回 0）
        int rows = noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, id)
                .eq(KbNoteEntity::getUserId, userId)
                .eq(KbNoteEntity::getIsDeleted, 0)
                .set(KbNoteEntity::getIsDeleted, 1)
                .set(KbNoteEntity::getDeletedAt, LocalDateTime.now())
                .set(KbNoteEntity::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            // 已删或不存在：幂等视为成功（已在回收站）
            KbNoteEntity e = noteMapper.selectById(id);
            if (e == null || !Objects.equals(e.getUserId(), userId)) {
                throw new BusinessException(404, "笔记不存在");
            }
            if (!Objects.equals(e.getIsDeleted(), 1)) {
                throw new BusinessException(400, "无法移入回收站");
            }
        }
        log.info("kb note soft-deleted userId={} noteId={} rows={}", userId, id, rows);
    }

    @Transactional
    public NoteResponse restore(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        int rows = noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, id)
                .eq(KbNoteEntity::getUserId, userId)
                .eq(KbNoteEntity::getIsDeleted, 1)
                .set(KbNoteEntity::getIsDeleted, 0)
                .set(KbNoteEntity::getDeletedAt, null)
                .set(KbNoteEntity::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            KbNoteEntity e = requireOwned(id, userId, true);
            if (!Objects.equals(e.getIsDeleted(), 1)) {
                return toResponse(e, true, null);
            }
            throw new BusinessException(404, "笔记不存在");
        }
        return toResponse(requireOwned(id, userId, true), true, null);
    }

    /**
     * 永久删除：笔记 + 标签关联 + 附件记录 + R2/本地对象。
     */
    @Transactional
    public void permanentDelete(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = requireOwned(id, userId, true);
        if (!Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(400, "仅回收站中的笔记可永久删除，请先移入回收站");
        }
        purgeNoteFully(userId, e);
        log.info("kb note permanently deleted userId={} noteId={}", userId, id);
    }

    /**
     * 清空回收站：当前用户全部软删笔记永久删除。
     *
     * @return 删除条数
     */
    @Transactional
    public int emptyTrash() {
        Long userId = SecurityUtils.requireCurrentUserId();
        List<KbNoteEntity> trash = noteMapper.selectList(
                new LambdaQueryWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getUserId, userId)
                        .eq(KbNoteEntity::getIsDeleted, 1));
        for (KbNoteEntity e : trash) {
            purgeNoteFully(userId, e);
        }
        log.info("kb trash emptied userId={} count={}", userId, trash.size());
        return trash.size();
    }

    public long countTrash() {
        Long userId = SecurityUtils.requireCurrentUserId();
        Long c = noteMapper.selectCount(
                new LambdaQueryWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getUserId, userId)
                        .eq(KbNoteEntity::getIsDeleted, 1));
        return c == null ? 0 : c;
    }

    private void purgeNoteFully(Long userId, KbNoteEntity e) {
        Long noteId = e.getId();
        // 1) 正文里引用的媒体
        Set<Long> fromContent = extractFileIdsFromContent(e.getContent());
        if (!fromContent.isEmpty()) {
            fileService.deleteByIds(userId, fromContent);
        }
        // 2) note_id 关联附件
        fileService.deleteAllForNote(userId, noteId);
        // 3) 标签关联
        noteTagMapper.delete(new LambdaQueryWrapper<KbNoteTagEntity>()
                .eq(KbNoteTagEntity::getNoteId, noteId));
        // 4) 版本历史
        revisionMapper.delete(new LambdaQueryWrapper<KbNoteRevisionEntity>()
                .eq(KbNoteRevisionEntity::getNoteId, noteId)
                .eq(KbNoteRevisionEntity::getUserId, userId));
        // 5) 笔记行
        noteMapper.deleteById(noteId);
    }

    /** 懒填充 content_text（仅写空字段，不触发版本） */
    private void ensureContentText(KbNoteEntity e) {
        if (e == null || e.getId() == null) {
            return;
        }
        if (StringUtils.hasText(e.getContentText())) {
            return;
        }
        if (!StringUtils.hasText(e.getContent())) {
            return;
        }
        try {
            String format = StringUtils.hasText(e.getContentFormat()) ? e.getContentFormat() : "html";
            String plain = toPlainText(e.getContent(), format);
            String snippet = StringUtils.hasText(e.getSnippet())
                    ? e.getSnippet()
                    : truncatePlain(plain, kbProperties.getNote().getSnippetChars());
            noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                    .eq(KbNoteEntity::getId, e.getId())
                    .eq(KbNoteEntity::getUserId, e.getUserId())
                    .set(KbNoteEntity::getContentText, plain)
                    .set(KbNoteEntity::getSnippet, snippet));
            e.setContentText(plain);
            e.setSnippet(snippet);
        } catch (Exception ex) {
            log.debug("ensureContentText skip id={}: {}", e.getId(), ex.getMessage());
        }
    }

    private void saveRevisionSnapshot(KbNoteEntity e, String source) {
        if (e == null || e.getId() == null) {
            return;
        }
        // 空白内容不存版本
        if (!StringUtils.hasText(e.getContent()) && !StringUtils.hasText(e.getTitle())) {
            return;
        }
        KbNoteRevisionEntity rev = new KbNoteRevisionEntity();
        rev.setNoteId(e.getId());
        rev.setUserId(e.getUserId());
        rev.setTitle(e.getTitle());
        rev.setContent(e.getContent());
        rev.setContentFormat(StringUtils.hasText(e.getContentFormat()) ? e.getContentFormat() : "html");
        rev.setSource(StringUtils.hasText(source) ? source : "save");
        rev.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(rev);

        int max = Math.max(5, kbProperties.getNote().getMaxRevisions());
        List<KbNoteRevisionEntity> old = revisionMapper.selectList(
                new LambdaQueryWrapper<KbNoteRevisionEntity>()
                        .eq(KbNoteRevisionEntity::getNoteId, e.getId())
                        .eq(KbNoteRevisionEntity::getUserId, e.getUserId())
                        .orderByDesc(KbNoteRevisionEntity::getCreatedAt)
                        .orderByDesc(KbNoteRevisionEntity::getId)
                        .select(KbNoteRevisionEntity::getId));
        if (old.size() > max) {
            for (int i = max; i < old.size(); i++) {
                revisionMapper.deleteById(old.get(i).getId());
            }
        }
    }

    static Set<Long> extractFileIdsFromContent(String content) {
        Set<Long> ids = new HashSet<>();
        if (!StringUtils.hasText(content)) {
            return ids;
        }
        Matcher m = FILE_ID_IN_CONTENT.matcher(content);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return ids;
    }

    private KbNoteEntity requireOwned(Long id, Long userId, boolean allowDeleted) {
        KbNoteEntity e = noteMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
        if (!allowDeleted && Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(404, "笔记不存在");
        }
        return e;
    }

    private Long resolveCategoryId(Long userId, Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        categoryService.requireOwned(categoryId, userId);
        return categoryId;
    }

    private String resolveTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return kbProperties.getNote().getDefaultTitle();
        }
        String t = title.trim();
        if (t.isEmpty()) {
            return kbProperties.getNote().getDefaultTitle();
        }
        if (t.length() > 200) {
            throw new BusinessException(400, "标题不能超过200字");
        }
        return t;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        int max = kbProperties.getNote().getMaxContentChars();
        if (content.length() > max) {
            throw new BusinessException(400, "正文不能超过 " + max + " 字符");
        }
        return content;
    }

    private String resolveFormat(String format) {
        if (!StringUtils.hasText(format)) {
            String def = kbProperties.getNote().getDefaultFormat();
            return StringUtils.hasText(def) ? def.trim().toLowerCase() : "html";
        }
        String f = format.trim().toLowerCase();
        if (!"html".equals(f) && !"markdown".equals(f)) {
            throw new BusinessException(400, "contentFormat 仅支持 html 或 markdown");
        }
        return f;
    }

    private NoteResponse toResponse(KbNoteEntity e, boolean includeContent, String matchSnippet) {
        Long userId = e.getUserId();
        Map<Long, List<KbTagEntity>> tagsMap = tagService.tagsByNoteIds(userId, List.of(e.getId()));
        String catName = null;
        if (e.getCategoryId() != null) {
            catName = categoryService.nameMap(userId, List.of(e.getCategoryId())).get(e.getCategoryId());
        }
        return toResponse(e, includeContent, tagsMap.getOrDefault(e.getId(), List.of()), catName, matchSnippet);
    }

    private NoteResponse toResponse(KbNoteEntity e, boolean includeContent,
                                    List<KbTagEntity> tags, String categoryName) {
        return toResponse(e, includeContent, tags, categoryName, null);
    }

    private NoteResponse toResponse(KbNoteEntity e, boolean includeContent,
                                    List<KbTagEntity> tags, String categoryName, String matchSnippet) {
        List<TagBrief> tagBriefs = tags == null ? Collections.emptyList() : tags.stream()
                .map(t -> TagBrief.builder().id(t.getId()).name(t.getName()).build())
                .collect(Collectors.toList());

        String format = StringUtils.hasText(e.getContentFormat()) ? e.getContentFormat() : "markdown";
        String snippet = null;
        if (!includeContent) {
            // 优先用库里的 snippet，避免 list 再解析整篇 content
            if (StringUtils.hasText(e.getSnippet())) {
                snippet = e.getSnippet();
            } else if (StringUtils.hasText(e.getContentText())) {
                snippet = truncatePlain(e.getContentText(), kbProperties.getNote().getSnippetChars());
            } else if (StringUtils.hasText(e.getContent())) {
                snippet = buildSnippet(e.getContent(), format);
            } else {
                snippet = "";
            }
        }

        return NoteResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .content(includeContent ? e.getContent() : null)
                .contentFormat(format)
                .snippet(snippet)
                .matchSnippet(matchSnippet)
                .categoryId(e.getCategoryId())
                .categoryName(categoryName)
                .tags(tagBriefs)
                .pinned(Objects.equals(e.getIsPinned(), 1))
                .deleted(Objects.equals(e.getIsDeleted(), 1))
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    static String buildSnippet(String content) {
        return buildSnippet(content, "markdown");
    }

    static String buildSnippet(String content, String format) {
        return truncatePlain(toPlainText(content, format), 160);
    }

    /** 将 html/markdown 转为可检索纯文本 */
    static String toPlainText(String content, String format) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String plain = content;
        if ("html".equalsIgnoreCase(format) || looksLikeHtml(content)) {
            plain = content
                    .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("(?i)</p>", "\n")
                    .replaceAll("(?i)</div>", "\n")
                    .replaceAll("(?i)</h[1-6]>", "\n")
                    .replaceAll("<[^>]+>", " ");
        } else {
            plain = content
                    .replaceAll("(?m)^#+\\s*", "")
                    .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
                    .replaceAll("[*`_>~]", "");
        }
        // HTML 实体粗略还原
        plain = plain
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
        return plain;
    }

    static String truncatePlain(String plain, int max) {
        if (!StringUtils.hasText(plain)) {
            return "";
        }
        if (max <= 0) {
            max = 160;
        }
        if (plain.length() <= max) {
            return plain;
        }
        return plain.substring(0, max) + "…";
    }

    static String buildMatchSnippet(String title, String contentText, String snippet,
                                    String keyword, int radius) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return null;
        }
        if (radius <= 0) {
            radius = 60;
        }
        // 标题命中优先展示标题上下文
        if (StringUtils.hasText(title) && containsIgnoreCase(title, kw)) {
            return truncatePlain(title, radius * 2);
        }
        String body = StringUtils.hasText(contentText) ? contentText
                : (StringUtils.hasText(snippet) ? snippet : "");
        if (!StringUtils.hasText(body)) {
            return null;
        }
        int idx = indexOfIgnoreCase(body, kw);
        if (idx < 0) {
            return truncatePlain(body, radius * 2);
        }
        int start = Math.max(0, idx - radius);
        int end = Math.min(body.length(), idx + kw.length() + radius);
        String frag = body.substring(start, end).trim();
        if (start > 0) {
            frag = "…" + frag;
        }
        if (end < body.length()) {
            frag = frag + "…";
        }
        return frag;
    }

    private static String normalizeKeyword(String keyword) {
        String kw = keyword.trim();
        if (kw.length() > 100) {
            kw = kw.substring(0, 100);
        }
        return kw;
    }

    private static boolean containsIgnoreCase(String text, String kw) {
        return indexOfIgnoreCase(text, kw) >= 0;
    }

    private static int indexOfIgnoreCase(String text, String kw) {
        if (text == null || kw == null || kw.isEmpty()) {
            return -1;
        }
        return text.toLowerCase(Locale.ROOT).indexOf(kw.toLowerCase(Locale.ROOT));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean looksLikeHtml(String content) {
        String t = content.trim();
        return t.startsWith("<") && t.contains(">");
    }
}
