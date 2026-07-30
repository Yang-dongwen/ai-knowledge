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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                throw new BusinessException(400, "分类层级不能超过 " + kbProperties.getCategory().getMaxDepth() + " 层");
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
                throw new BusinessException(400, "不能将分类设为自己的父级");
            }
            KbCategoryEntity parent = requireOwned(req.getParentId(), userId);
            if (isDescendant(userId, id, req.getParentId())) {
                throw new BusinessException(400, "不能将分类移动到其子分类下");
            }
            int depth = depthOf(parent, userId) + 1;
            // 还要考虑本节点子树高度
            int subtreeHeight = subtreeHeight(id, userId);
            if (depth + subtreeHeight - 1 > kbProperties.getCategory().getMaxDepth()) {
                throw new BusinessException(400, "移动后分类层级将超过限制");
            }
            e.setParentId(req.getParentId());
        }

        if (req.getSortOrder() != null) {
            e.setSortOrder(req.getSortOrder());
        }
        categoryMapper.updateById(e);
        return toResponse(requireOwned(id, userId));
    }

    @Transactional
    public void delete(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        requireOwned(id, userId);

        Long childCount = categoryMapper.selectCount(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId)
                        .eq(KbCategoryEntity::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(400, "请先删除或移动子分类");
        }

        Long noteCount = noteMapper.selectCount(
                new LambdaQueryWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getUserId, userId)
                        .eq(KbNoteEntity::getCategoryId, id)
                        .eq(KbNoteEntity::getIsDeleted, 0));
        if (noteCount != null && noteCount > 0) {
            throw new BusinessException(400, "请先移除或移动该分类下的笔记");
        }

        categoryMapper.deleteById(id);
    }

    public KbCategoryEntity requireOwned(Long id, Long userId) {
        KbCategoryEntity e = categoryMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(404, "分类不存在");
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
            throw new BusinessException(400, "同级下已存在同名分类");
        }
    }

    private static String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "分类名称不能为空");
        }
        String n = name.trim();
        if (n.isEmpty()) {
            throw new BusinessException(400, "分类名称不能为空");
        }
        if (n.length() > 64) {
            throw new BusinessException(400, "分类名称不能超过64字");
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
