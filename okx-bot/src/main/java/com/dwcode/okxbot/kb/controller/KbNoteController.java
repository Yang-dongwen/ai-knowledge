package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.NoteCreateRequest;
import com.dwcode.okxbot.kb.dto.NotePageResponse;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.dto.NoteRevisionResponse;
import com.dwcode.okxbot.kb.dto.NoteUpdateRequest;
import com.dwcode.okxbot.kb.service.KbNoteService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库笔记 API。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb/notes")
@RequiredArgsConstructor
public class KbNoteController {

    private final KbNoteService noteService;

    @GetMapping
    public ApiResult<NotePageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(defaultValue = "false") boolean onlyDeleted,
            @RequestParam(defaultValue = "false") boolean onlyPinned) {
        return ApiResult.ok(noteService.list(
                page, size, categoryId, tagId, keyword, includeDeleted, uncategorized, onlyDeleted, onlyPinned));
    }

    /** 回收站数量（须在 /{id} 之前声明） */
    @GetMapping("/trash/count")
    public ApiResult<Map<String, Long>> trashCount() {
        return ApiResult.ok(Map.of("count", noteService.countTrash()));
    }

    /** 清空回收站 */
    @DeleteMapping("/trash")
    public ApiResult<Map<String, Integer>> emptyTrash() {
        int n = noteService.emptyTrash();
        return ApiResult.ok(Map.of("deleted", n));
    }

    @PostMapping
    public ApiResult<NoteResponse> create(@Valid @RequestBody NoteCreateRequest request) {
        log.info("创建知识库笔记 categoryId={} tagCount={}",
                request.getCategoryId(),
                request.getTagIds() == null ? 0 : request.getTagIds().size());
        return ApiResult.ok(noteService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResult<NoteResponse> get(@PathVariable Long id, HttpServletResponse response) {
        long t0 = System.nanoTime();
        KbNoteService.TimedNote timed = noteService.getTimed(id);
        long serializeStart = System.nanoTime();
        // 注意：此处之后 Jackson 序列化 + 网络写出不在 totalMs 内，浏览器 TTFB 还含这一段
        long appMs = (serializeStart - t0) / 1_000_000L;
        response.setHeader("X-Kb-Query-Ms", String.valueOf(timed.totalMs()));
        response.setHeader("X-Kb-Db-Ms", String.valueOf(timed.dbMs()));
        response.setHeader("X-Kb-Build-Ms", String.valueOf(timed.buildMs()));
        response.setHeader("X-Kb-Content-Chars", String.valueOf(timed.contentChars()));
        response.setHeader("Server-Timing",
                "db;dur=" + timed.dbMs()
                        + ",build;dur=" + timed.buildMs()
                        + ",app;dur=" + appMs);
        if (timed.contentChars() >= 200_000) {
            log.warn("kb note get large payload id={} chars={} appMs={} — 慢多半是 LONGTEXT 读库+JSON 序列化/传输，不是排队",
                    id, timed.contentChars(), appMs);
        }
        return ApiResult.ok(timed.response());
    }

    @PutMapping("/{id}")
    public ApiResult<NoteResponse> update(@PathVariable Long id,
                                          @Valid @RequestBody NoteUpdateRequest request) {
        return ApiResult.ok(noteService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        noteService.delete(id);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/restore")
    public ApiResult<NoteResponse> restore(@PathVariable Long id) {
        return ApiResult.ok(noteService.restore(id));
    }

    /**
     * 永久删除（仅回收站内笔记）：库记录 + 附件 + R2/本地对象。
     */
    @DeleteMapping("/{id}/permanent")
    public ApiResult<Void> permanentDelete(@PathVariable Long id) {
        noteService.permanentDelete(id);
        return ApiResult.ok();
    }

    /** 复制笔记 */
    @PostMapping("/{id}/duplicate")
    public ApiResult<NoteResponse> duplicate(@PathVariable Long id) {
        return ApiResult.ok(noteService.duplicate(id));
    }

    /** 导出 Markdown 文件 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long id,
            @RequestParam(defaultValue = "md") String format) {
        String md = noteService.exportMarkdown(id);
        String filename = "note-" + id + ".md";
        try {
            NoteResponse meta = noteService.get(id);
            if (meta.getTitle() != null && !meta.getTitle().isBlank()) {
                String safe = meta.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (!safe.isEmpty()) {
                    filename = safe + ".md";
                }
            }
        } catch (Exception ignored) {
            // 用默认文件名
        }
        byte[] bytes = md.getBytes(StandardCharsets.UTF_8);
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(bytes);
    }

    @GetMapping("/{id}/revisions")
    public ApiResult<List<NoteRevisionResponse>> listRevisions(@PathVariable Long id) {
        return ApiResult.ok(noteService.listRevisions(id));
    }

    @GetMapping("/{id}/revisions/{revisionId}")
    public ApiResult<NoteRevisionResponse> getRevision(
            @PathVariable Long id, @PathVariable Long revisionId) {
        return ApiResult.ok(noteService.getRevision(id, revisionId));
    }

    @PostMapping("/{id}/revisions/{revisionId}/restore")
    public ApiResult<NoteResponse> restoreRevision(
            @PathVariable Long id, @PathVariable Long revisionId) {
        return ApiResult.ok(noteService.restoreRevision(id, revisionId));
    }
}
