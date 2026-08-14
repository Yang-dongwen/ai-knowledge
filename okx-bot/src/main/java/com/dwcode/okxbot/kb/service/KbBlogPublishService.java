package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.blog.SlugUtil;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

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
    private final HaloPublishPort haloPublishPort;

    @Transactional
    public NoteResponse publish(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbNoteEntity e = noteMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId) || Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(404, "笔记不存在");
        }
        String title = StringUtils.hasText(e.getTitle()) ? e.getTitle() : "未命名";
        String rawType = "markdown".equalsIgnoreCase(e.getContentFormat()) ? "markdown" : "HTML";
        HaloPublishResult result = haloPublishPort.publish(new HaloPublishCommand(
                title,
                SlugUtil.fromTitle(title, "post-" + id),
                e.getContent() == null ? "" : e.getContent(),
                rawType,
                e.getHaloPostName()));

        LocalDateTime now = LocalDateTime.now();
        e.setHaloPostName(result.postName());
        e.setHaloPermalink(result.publicUrl());
        e.setHaloPublishedAt(now);
        e.setUpdatedAt(now);
        noteMapper.updateById(e);

        log.info("kb note published to halo userId={} noteId={} post={}", userId, id, result.postName());
        NoteResponse resp = noteService.get(id);
        resp.setUnresolvedMedia(hasPrivateMedia(e.getContent()));
        return resp;
    }

    static boolean hasPrivateMedia(String content) {
        return content != null && content.contains(KB_FILE_MARKER);
    }
}
