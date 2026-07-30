package com.dwcode.okxbot.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.dto.TagCreateRequest;
import com.dwcode.okxbot.kb.dto.TagResponse;
import com.dwcode.okxbot.kb.dto.TagUpdateRequest;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.entity.KbNoteTagEntity;
import com.dwcode.okxbot.kb.entity.KbTagEntity;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteTagMapper;
import com.dwcode.okxbot.kb.mapper.KbTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KbTagService {

    private final KbTagMapper tagMapper;
    private final KbNoteTagMapper noteTagMapper;
    private final KbNoteMapper noteMapper;

    public List<TagResponse> list() {
        Long userId = SecurityUtils.requireCurrentUserId();
        List<KbTagEntity> tags = tagMapper.selectList(
                new LambdaQueryWrapper<KbTagEntity>()
                        .eq(KbTagEntity::getUserId, userId)
                        .orderByAsc(KbTagEntity::getName));
        if (tags.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> countMap = noteCountByTag(userId, tags.stream().map(KbTagEntity::getId).toList());
        List<TagResponse> result = new ArrayList<>();
        for (KbTagEntity t : tags) {
            result.add(TagResponse.builder()
                    .id(t.getId())
                    .name(t.getName())
                    .noteCount(countMap.getOrDefault(t.getId(), 0L))
                    .createdAt(t.getCreatedAt())
                    .build());
        }
        return result;
    }

    @Transactional
    public TagResponse create(TagCreateRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String name = requireName(req.getName());
        KbTagEntity existing = tagMapper.selectOne(
                new LambdaQueryWrapper<KbTagEntity>()
                        .eq(KbTagEntity::getUserId, userId)
                        .eq(KbTagEntity::getName, name)
                        .last("LIMIT 1"));
        if (existing != null) {
            return TagResponse.builder()
                    .id(existing.getId())
                    .name(existing.getName())
                    .noteCount(noteCountByTag(userId, List.of(existing.getId()))
                            .getOrDefault(existing.getId(), 0L))
                    .createdAt(existing.getCreatedAt())
                    .build();
        }
        KbTagEntity e = new KbTagEntity();
        e.setUserId(userId);
        e.setName(name);
        tagMapper.insert(e);
        return TagResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .noteCount(0)
                .createdAt(e.getCreatedAt())
                .build();
    }

    @Transactional
    public TagResponse update(Long id, TagUpdateRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbTagEntity e = requireOwned(id, userId);
        String name = requireName(req.getName());
        KbTagEntity clash = tagMapper.selectOne(
                new LambdaQueryWrapper<KbTagEntity>()
                        .eq(KbTagEntity::getUserId, userId)
                        .eq(KbTagEntity::getName, name)
                        .ne(KbTagEntity::getId, id)
                        .last("LIMIT 1"));
        if (clash != null) {
            throw new BusinessException(400, "标签名称已存在");
        }
        e.setName(name);
        tagMapper.updateById(e);
        return TagResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .noteCount(noteCountByTag(userId, List.of(id)).getOrDefault(id, 0L))
                .createdAt(e.getCreatedAt())
                .build();
    }

    @Transactional
    public void delete(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        requireOwned(id, userId);
        noteTagMapper.delete(new LambdaQueryWrapper<KbNoteTagEntity>()
                .eq(KbNoteTagEntity::getTagId, id));
        tagMapper.deleteById(id);
    }

    public KbTagEntity requireOwned(Long id, Long userId) {
        KbTagEntity e = tagMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(404, "标签不存在");
        }
        return e;
    }

    /**
     * 校验标签均属当前用户，返回去重后的 id 列表。
     */
    public List<Long> validateOwnedTagIds(Long userId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = tagIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<KbTagEntity> found = tagMapper.selectList(
                new LambdaQueryWrapper<KbTagEntity>()
                        .eq(KbTagEntity::getUserId, userId)
                        .in(KbTagEntity::getId, distinct));
        if (found.size() != distinct.size()) {
            throw new BusinessException(400, "存在无效标签");
        }
        return distinct;
    }

    public Map<Long, List<KbTagEntity>> tagsByNoteIds(Long userId, List<Long> noteIds) {
        Map<Long, List<KbTagEntity>> result = new HashMap<>();
        if (noteIds == null || noteIds.isEmpty()) {
            return result;
        }
        List<KbNoteTagEntity> links = noteTagMapper.selectList(
                new LambdaQueryWrapper<KbNoteTagEntity>()
                        .in(KbNoteTagEntity::getNoteId, noteIds));
        if (links.isEmpty()) {
            return result;
        }
        Set<Long> tagIds = links.stream().map(KbNoteTagEntity::getTagId).collect(Collectors.toSet());
        List<KbTagEntity> tags = tagMapper.selectList(
                new LambdaQueryWrapper<KbTagEntity>()
                        .eq(KbTagEntity::getUserId, userId)
                        .in(KbTagEntity::getId, tagIds));
        Map<Long, KbTagEntity> tagMap = tags.stream()
                .collect(Collectors.toMap(KbTagEntity::getId, t -> t, (a, b) -> a));
        for (KbNoteTagEntity link : links) {
            KbTagEntity tag = tagMap.get(link.getTagId());
            if (tag != null) {
                result.computeIfAbsent(link.getNoteId(), k -> new ArrayList<>()).add(tag);
            }
        }
        return result;
    }

    @Transactional
    public void replaceNoteTags(Long noteId, List<Long> tagIds) {
        noteTagMapper.delete(new LambdaQueryWrapper<KbNoteTagEntity>()
                .eq(KbNoteTagEntity::getNoteId, noteId));
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        for (Long tagId : tagIds) {
            KbNoteTagEntity link = new KbNoteTagEntity();
            link.setNoteId(noteId);
            link.setTagId(tagId);
            noteTagMapper.insert(link);
        }
    }

    public List<Long> noteIdsByTag(Long userId, Long tagId) {
        requireOwned(tagId, userId);
        return noteTagMapper.selectList(
                        new LambdaQueryWrapper<KbNoteTagEntity>()
                                .eq(KbNoteTagEntity::getTagId, tagId))
                .stream()
                .map(KbNoteTagEntity::getNoteId)
                .toList();
    }

    private Map<Long, Long> noteCountByTag(Long userId, List<Long> tagIds) {
        Map<Long, Long> map = new HashMap<>();
        if (tagIds == null || tagIds.isEmpty()) {
            return map;
        }
        List<KbNoteTagEntity> links = noteTagMapper.selectList(
                new LambdaQueryWrapper<KbNoteTagEntity>()
                        .in(KbNoteTagEntity::getTagId, tagIds));
        if (links.isEmpty()) {
            return map;
        }
        Set<Long> noteIds = links.stream().map(KbNoteTagEntity::getNoteId).collect(Collectors.toSet());
        if (noteIds.isEmpty()) {
            return map;
        }
        List<KbNoteEntity> notes = noteMapper.selectList(
                new LambdaQueryWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getUserId, userId)
                        .eq(KbNoteEntity::getIsDeleted, 0)
                        .in(KbNoteEntity::getId, noteIds)
                        .select(KbNoteEntity::getId));
        Set<Long> alive = notes.stream().map(KbNoteEntity::getId).collect(Collectors.toCollection(HashSet::new));
        for (KbNoteTagEntity link : links) {
            if (alive.contains(link.getNoteId())) {
                map.merge(link.getTagId(), 1L, Long::sum);
            }
        }
        return map;
    }

    private static String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "标签名称不能为空");
        }
        String n = name.trim();
        if (n.isEmpty()) {
            throw new BusinessException(400, "标签名称不能为空");
        }
        if (n.length() > 64) {
            throw new BusinessException(400, "标签名称不能超过64字");
        }
        return n;
    }
}
