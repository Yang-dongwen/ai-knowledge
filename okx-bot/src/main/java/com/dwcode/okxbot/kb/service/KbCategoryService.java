package com.dwcode.okxbot.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.dto.CategoryCreateRequest;
import com.dwcode.okxbot.kb.dto.CategoryResponse;
import com.dwcode.okxbot.kb.dto.CategoryUpdateRequest;
import com.dwcode.okxbot.kb.entity.KbCategoryEntity;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbCategoryMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KbCategoryService {

    private final KbCategoryMapper categoryMapper;
    private final KbNoteMapper noteMapper;
    private final KbProperties kbProperties;

    public List<CategoryResponse> listTree() {
        Long userId = SecurityUtils.requireCurrentUserId();
        List<KbCategoryEntity> all = categoryMapper.selectList(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId)
                        .orderByAsc(KbCategoryEntity::getSortOrder)
                        .orderByAsc(KbCategoryEntity::getId));
        return buildTree(all);
    }

    @Transactional
    public CategoryResponse create(CategoryCreateRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String name = requireName(req.getName());
        Long parentId = req.getParentId();
        if (parentId != null) {
            KbCategoryEntity parent = requireOwned(parentId, userId);
            int depth = depthOf(parent, userId) + 1;
            if (depth > kbProperties.getCategory().getMaxDepth()) {
                throw new BusinessException(400, "文件夹层级不能超过 " + kbProperties.getCategory().getMaxDepth() + " 层");
            }
        }
        ensureNameUnique(userId, parentId, name, null);

        KbCategoryEntity e = new KbCategoryEntity();
        e.setUserId(userId);
        e.setName(name);
        e.setParentId(parentId);
        e.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        categoryMapper.insert(e);
        return toResponse(e);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryUpdateRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbCategoryEntity e = requireOwned(id, userId);

        if (StringUtils.hasText(req.getName())) {
            String name = requireName(req.getName());
            Long parentForUnique = e.getParentId();
            if (Boolean.TRUE.equals(req.getClearParent())) {
                parentForUnique = null;
            } else if (req.getParentId() != null) {
                parentForUnique = req.getParentId();
            }
            ensureNameUnique(userId, parentForUnique, name, id);
            e.setName(name);
        }

        if (Boolean.TRUE.equals(req.getClearParent())) {
            e.setParentId(null);
        } else if (req.getParentId() != null) {
            if (Objects.equals(req.getParentId(), id)) {
                throw new BusinessException(400, "不能将文件夹设为自己的父级");
            }
            KbCategoryEntity parent = requireOwned(req.getParentId(), userId);
            if (isDescendant(userId, id, req.getParentId())) {
                throw new BusinessException(400, "不能将文件夹移动到其子文件夹下");
            }
            int depth = depthOf(parent, userId) + 1;
            // 还要考虑本节点子树高度
            int subtreeHeight = subtreeHeight(id, userId);
            if (depth + subtreeHeight - 1 > kbProperties.getCategory().getMaxDepth()) {
                throw new BusinessException(400, "移动后文件夹层级将超过限制");
            }
            e.setParentId(req.getParentId());
        }

        if (req.getSortOrder() != null) {
            e.setSortOrder(req.getSortOrder());
        }
        categoryMapper.updateById(e);
        return toResponse(requireOwned(id, userId));
    }

    /**
     * 删除文件夹。
     *
     * @param mode reject 非空拒绝（默认）；orphan 子项上移到父级后删夹；trash 子树文档进回收站并删夹
     */
    @Transactional
    public com.dwcode.okxbot.kb.dto.FolderDeleteResult delete(Long id, String mode) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbCategoryEntity folder = requireOwned(id, userId);
        String m = StringUtils.hasText(mode) ? mode.trim().toLowerCase() : "reject";
        if (!Set.of("reject", "orphan", "trash").contains(m)) {
            throw new BusinessException(400, "mode 仅支持 reject / orphan / trash");
        }

        if ("reject".equals(m)) {
            Long childCount = categoryMapper.selectCount(
                    new LambdaQueryWrapper<KbCategoryEntity>()
                            .eq(KbCategoryEntity::getUserId, userId)
                            .eq(KbCategoryEntity::getParentId, id));
            if (childCount != null && childCount > 0) {
                throw new BusinessException(400, "请先删除或移动子文件夹，或使用「内容上移/移入回收站」");
            }
            Long noteCount = noteMapper.selectCount(
                    new LambdaQueryWrapper<KbNoteEntity>()
                            .eq(KbNoteEntity::getUserId, userId)
                            .eq(KbNoteEntity::getCategoryId, id)
                            .eq(KbNoteEntity::getIsDeleted, 0));
            if (noteCount != null && noteCount > 0) {
                throw new BusinessException(400, "请先移除或移动该文件夹下的文档，或使用「内容上移/移入回收站」");
            }
            categoryMapper.deleteById(id);
            return com.dwcode.okxbot.kb.dto.FolderDeleteResult.builder()
                    .foldersDeleted(1).notesOrphaned(0).notesTrashed(0).build();
        }

        if ("orphan".equals(m)) {
            Long parentId = folder.getParentId();
            // 子文件夹上移
            categoryMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbCategoryEntity>()
                    .eq(KbCategoryEntity::getUserId, userId)
                    .eq(KbCategoryEntity::getParentId, id)
                    .set(KbCategoryEntity::getParentId, parentId)
                    .set(KbCategoryEntity::getUpdatedAt, java.time.LocalDateTime.now()));
            // 文档上移到父夹（或未归档）
            int notes = noteMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbNoteEntity>()
                    .eq(KbNoteEntity::getUserId, userId)
                    .eq(KbNoteEntity::getCategoryId, id)
                    .eq(KbNoteEntity::getIsDeleted, 0)
                    .set(KbNoteEntity::getCategoryId, parentId)
                    .set(KbNoteEntity::getUpdatedAt, java.time.LocalDateTime.now()));
            categoryMapper.deleteById(id);
            return com.dwcode.okxbot.kb.dto.FolderDeleteResult.builder()
                    .foldersDeleted(1).notesOrphaned(notes).notesTrashed(0).build();
        }

        // trash：该文件夹子树内所有文档软删，再删子树全部文件夹
        List<Long> folderIds = collectSubtreeFolderIds(userId, id);
        int trashed = 0;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (!folderIds.isEmpty()) {
            trashed = noteMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbNoteEntity>()
                    .eq(KbNoteEntity::getUserId, userId)
                    .in(KbNoteEntity::getCategoryId, folderIds)
                    .eq(KbNoteEntity::getIsDeleted, 0)
                    .set(KbNoteEntity::getIsDeleted, 1)
                    .set(KbNoteEntity::getDeletedAt, now)
                    .set(KbNoteEntity::getUpdatedAt, now));
            // 先删深层再删根：按 id 倒序不够可靠，用层序逆序
            List<Long> ordered = new ArrayList<>(folderIds);
            // 简单：反复删除叶子直到清空
            int guard = 0;
            while (!ordered.isEmpty() && guard++ < 1000) {
                boolean progress = false;
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    Long fid = ordered.get(i);
                    Long kids = categoryMapper.selectCount(
                            new LambdaQueryWrapper<KbCategoryEntity>()
                                    .eq(KbCategoryEntity::getUserId, userId)
                                    .eq(KbCategoryEntity::getParentId, fid));
                    if (kids == null || kids == 0) {
                        categoryMapper.deleteById(fid);
                        ordered.remove(i);
                        progress = true;
                    }
                }
                if (!progress) {
                    // 兜底：强制删剩余
                    for (Long fid : ordered) {
                        categoryMapper.deleteById(fid);
                    }
                    ordered.clear();
                }
            }
        }
        return com.dwcode.okxbot.kb.dto.FolderDeleteResult.builder()
                .foldersDeleted(folderIds.size())
                .notesOrphaned(0)
                .notesTrashed(trashed)
                .build();
    }

    /** 兼容旧调用：reject 模式 */
    @Transactional
    public void delete(Long id) {
        delete(id, "reject");
    }

    private List<Long> collectSubtreeFolderIds(Long userId, Long rootId) {
        List<KbCategoryEntity> all = categoryMapper.selectList(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId));
        Map<Long, List<Long>> children = new HashMap<>();
        for (KbCategoryEntity c : all) {
            if (c.getParentId() != null) {
                children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        List<Long> out = new ArrayList<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        q.add(rootId);
        while (!q.isEmpty()) {
            Long id = q.poll();
            out.add(id);
            for (Long c : children.getOrDefault(id, List.of())) {
                q.add(c);
            }
        }
        return out;
    }

    public KbCategoryEntity requireOwned(Long id, Long userId) {
        KbCategoryEntity e = categoryMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(404, "文件夹不存在");
        }
        return e;
    }

    public Map<Long, String> nameMap(Long userId, Iterable<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        List<Long> list = new ArrayList<>();
        for (Long id : ids) {
            if (id != null) {
                list.add(id);
            }
        }
        if (list.isEmpty()) {
            return map;
        }
        List<KbCategoryEntity> rows = categoryMapper.selectList(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId)
                        .in(KbCategoryEntity::getId, list));
        for (KbCategoryEntity r : rows) {
            map.put(r.getId(), r.getName());
        }
        return map;
    }

    private int depthOf(KbCategoryEntity node, Long userId) {
        int depth = 1;
        Long parentId = node.getParentId();
        int guard = 0;
        while (parentId != null && guard++ < 32) {
            KbCategoryEntity p = categoryMapper.selectById(parentId);
            if (p == null || !Objects.equals(p.getUserId(), userId)) {
                break;
            }
            depth++;
            parentId = p.getParentId();
        }
        return depth;
    }

    private int subtreeHeight(Long rootId, Long userId) {
        List<KbCategoryEntity> all = categoryMapper.selectList(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId));
        Map<Long, List<Long>> children = new HashMap<>();
        for (KbCategoryEntity c : all) {
            if (c.getParentId() != null) {
                children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        return height(rootId, children);
    }

    private int height(Long id, Map<Long, List<Long>> children) {
        List<Long> kids = children.getOrDefault(id, List.of());
        if (kids.isEmpty()) {
            return 1;
        }
        int max = 0;
        for (Long k : kids) {
            max = Math.max(max, height(k, children));
        }
        return max + 1;
    }

    private boolean isDescendant(Long userId, Long ancestorId, Long maybeDescendantId) {
        Long cursor = maybeDescendantId;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            if (Objects.equals(cursor, ancestorId)) {
                return true;
            }
            KbCategoryEntity n = categoryMapper.selectById(cursor);
            if (n == null || !Objects.equals(n.getUserId(), userId)) {
                return false;
            }
            cursor = n.getParentId();
        }
        return false;
    }

    private void ensureNameUnique(Long userId, Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<KbCategoryEntity> q = new LambdaQueryWrapper<KbCategoryEntity>()
                .eq(KbCategoryEntity::getUserId, userId)
                .eq(KbCategoryEntity::getName, name);
        if (parentId == null) {
            q.isNull(KbCategoryEntity::getParentId);
        } else {
            q.eq(KbCategoryEntity::getParentId, parentId);
        }
        if (excludeId != null) {
            q.ne(KbCategoryEntity::getId, excludeId);
        }
        Long cnt = categoryMapper.selectCount(q);
        if (cnt != null && cnt > 0) {
            throw new BusinessException(400, "同级下已存在同名文件夹");
        }
    }

    private static String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "文件夹名称不能为空");
        }
        String n = name.trim();
        if (n.isEmpty()) {
            throw new BusinessException(400, "文件夹名称不能为空");
        }
        if (n.length() > 64) {
            throw new BusinessException(400, "文件夹名称不能超过64字");
        }
        return n;
    }

    private List<CategoryResponse> buildTree(List<KbCategoryEntity> all) {
        Map<Long, CategoryResponse> map = new HashMap<>();
        for (KbCategoryEntity e : all) {
            map.put(e.getId(), toResponse(e));
        }
        List<CategoryResponse> roots = new ArrayList<>();
        for (KbCategoryEntity e : all) {
            CategoryResponse node = map.get(e.getId());
            if (e.getParentId() == null || !map.containsKey(e.getParentId())) {
                roots.add(node);
            } else {
                map.get(e.getParentId()).getChildren().add(node);
            }
        }
        sortRecursive(roots);
        return roots;
    }

    private void sortRecursive(List<CategoryResponse> list) {
        list.sort(Comparator
                .comparing((CategoryResponse c) -> c.getSortOrder() == null ? 0 : c.getSortOrder())
                .thenComparing(c -> c.getId() == null ? 0L : c.getId()));
        for (CategoryResponse c : list) {
            if (c.getChildren() != null && !c.getChildren().isEmpty()) {
                sortRecursive(c.getChildren());
            }
        }
    }

    private CategoryResponse toResponse(KbCategoryEntity e) {
        return CategoryResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .parentId(e.getParentId())
                .sortOrder(e.getSortOrder())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .children(new ArrayList<>())
                .build();
    }
}
