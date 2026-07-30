package com.dwcode.okxbot.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.dto.ExplorerNodeResponse;
import com.dwcode.okxbot.kb.dto.ExplorerTreeResponse;
import com.dwcode.okxbot.kb.dto.NoteBatchMoveRequest;
import com.dwcode.okxbot.kb.dto.TreeMoveRequest;
import com.dwcode.okxbot.kb.dto.TreeReorderRequest;
import com.dwcode.okxbot.kb.entity.KbCategoryEntity;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbCategoryMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 知识库目录树（文件夹 + 文档叶子）。
 * 复用 kb_category / note.category_id，产品层称文件夹。
 */
@Service
@RequiredArgsConstructor
public class KbExplorerService {

    private final KbCategoryMapper categoryMapper;
    private final KbNoteMapper noteMapper;
    private final KbCategoryService categoryService;
    private final KbProperties kbProperties;

    public ExplorerTreeResponse tree() {
        Long userId = SecurityUtils.requireCurrentUserId();

        List<KbCategoryEntity> folders = categoryMapper.selectList(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId)
                        .orderByAsc(KbCategoryEntity::getSortOrder)
                        .orderByAsc(KbCategoryEntity::getId));

        List<KbNoteEntity> notes = noteMapper.selectList(
                new LambdaQueryWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getUserId, userId)
                        .eq(KbNoteEntity::getIsDeleted, 0)
                        .select(
                                KbNoteEntity::getId,
                                KbNoteEntity::getTitle,
                                KbNoteEntity::getContentFormat,
                                KbNoteEntity::getSnippet,
                                KbNoteEntity::getCategoryId,
                                KbNoteEntity::getSortOrder,
                                KbNoteEntity::getIsPinned,
                                KbNoteEntity::getUpdatedAt,
                                KbNoteEntity::getCreatedAt
                        )
                        // 目录树顺序要稳定：置顶 → sort_order → id
                        // 勿用 updatedAt：打开/保存会改时间导致「点谁谁就跑到最前」
                        .orderByDesc(KbNoteEntity::getIsPinned)
                        .orderByAsc(KbNoteEntity::getSortOrder)
                        .orderByAsc(KbNoteEntity::getId));

        Map<Long, ExplorerNodeResponse> folderNodes = new HashMap<>();
        for (KbCategoryEntity f : folders) {
            ExplorerNodeResponse node = ExplorerNodeResponse.builder()
                    .type("folder")
                    .id(f.getId())
                    .name(StringUtils.hasText(f.getName()) ? f.getName() : "未命名文件夹")
                    .parentId(f.getParentId())
                    .sortOrder(f.getSortOrder() != null ? f.getSortOrder() : 0)
                    .children(new ArrayList<>())
                    .build();
            folderNodes.put(f.getId(), node);
        }

        List<ExplorerNodeResponse> rootFolders = new ArrayList<>();
        for (KbCategoryEntity f : folders) {
            ExplorerNodeResponse node = folderNodes.get(f.getId());
            Long parentId = f.getParentId();
            if (parentId != null && folderNodes.containsKey(parentId)) {
                folderNodes.get(parentId).getChildren().add(node);
            } else {
                rootFolders.add(node);
            }
        }

        List<ExplorerNodeResponse> rootNotes = new ArrayList<>();
        for (KbNoteEntity n : notes) {
            ExplorerNodeResponse noteNode = toNoteNode(n);
            Long folderId = n.getCategoryId();
            if (folderId != null && folderNodes.containsKey(folderId)) {
                folderNodes.get(folderId).getChildren().add(noteNode);
            } else {
                rootNotes.add(noteNode);
            }
        }

        for (ExplorerNodeResponse folder : folderNodes.values()) {
            sortChildren(folder.getChildren());
        }
        sortChildren(rootFolders);
        List<ExplorerNodeResponse> roots = new ArrayList<>(rootFolders.size() + rootNotes.size());
        roots.addAll(rootFolders);
        roots.addAll(rootNotes);
        // 根层文档也按 sort 排
        sortRootMixed(roots);

        return ExplorerTreeResponse.builder()
                .roots(roots)
                .folderCount(folders.size())
                .noteCount(notes.size())
                .build();
    }

    /**
     * 移动文件夹或文档到目标文件夹。
     */
    @Transactional
    public void move(TreeMoveRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String type = normalizeType(req.getType());
        Long target = Boolean.TRUE.equals(req.getClearToRoot()) ? null : req.getTargetFolderId();
        if (target != null) {
            categoryService.requireOwned(target, userId);
        }

        if ("folder".equals(type)) {
            moveFolder(userId, req.getId(), target);
        } else if ("note".equals(type)) {
            moveNote(userId, req.getId(), target);
        } else {
            throw new BusinessException(400, "type 仅支持 folder 或 note");
        }
    }

    /**
     * 同级重排（文件夹或文档各自重排，不混排）。
     */
    @Transactional
    public void reorder(TreeReorderRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String type = normalizeType(req.getType());
        Long parent = Boolean.TRUE.equals(req.getClearParent()) ? null : req.getParentFolderId();
        if (parent != null) {
            categoryService.requireOwned(parent, userId);
        }
        List<Long> ordered = req.getOrderedIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ordered.isEmpty()) {
            throw new BusinessException(400, "orderedIds 不能为空");
        }

        if ("folder".equals(type)) {
            reorderFolders(userId, parent, ordered);
        } else if ("note".equals(type)) {
            reorderNotes(userId, parent, ordered);
        } else {
            throw new BusinessException(400, "type 仅支持 folder 或 note");
        }
    }

    @Transactional
    public int batchMoveNotes(NoteBatchMoveRequest req) {
        Long userId = SecurityUtils.requireCurrentUserId();
        Long target = Boolean.TRUE.equals(req.getClearToRoot()) ? null : req.getTargetFolderId();
        if (target != null) {
            categoryService.requireOwned(target, userId);
        }
        int n = 0;
        LocalDateTime now = LocalDateTime.now();
        int nextSort = nextNoteSortOrder(userId, target);
        for (Long noteId : req.getNoteIds()) {
            if (noteId == null) continue;
            int rows = noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                    .eq(KbNoteEntity::getId, noteId)
                    .eq(KbNoteEntity::getUserId, userId)
                    .eq(KbNoteEntity::getIsDeleted, 0)
                    .set(KbNoteEntity::getCategoryId, target)
                    .set(KbNoteEntity::getSortOrder, nextSort++)
                    .set(KbNoteEntity::getUpdatedAt, now));
            n += rows;
        }
        return n;
    }

    private void moveFolder(Long userId, Long folderId, Long targetParentId) {
        KbCategoryEntity e = categoryService.requireOwned(folderId, userId);
        if (Objects.equals(e.getParentId(), targetParentId)) {
            return;
        }
        if (targetParentId != null) {
            if (Objects.equals(targetParentId, folderId)) {
                throw new BusinessException(400, "不能将文件夹移动到自身");
            }
            if (isDescendantFolder(userId, folderId, targetParentId)) {
                throw new BusinessException(400, "不能将文件夹移动到其子文件夹下");
            }
            KbCategoryEntity parent = categoryService.requireOwned(targetParentId, userId);
            int depth = depthOf(parent, userId) + 1;
            int subtreeHeight = subtreeHeight(folderId, userId);
            if (depth + subtreeHeight - 1 > kbProperties.getCategory().getMaxDepth()) {
                throw new BusinessException(400, "移动后文件夹层级将超过限制");
            }
        }
        int sort = nextFolderSortOrder(userId, targetParentId);
        categoryMapper.update(null, new LambdaUpdateWrapper<KbCategoryEntity>()
                .eq(KbCategoryEntity::getId, folderId)
                .eq(KbCategoryEntity::getUserId, userId)
                .set(KbCategoryEntity::getParentId, targetParentId)
                .set(KbCategoryEntity::getSortOrder, sort)
                .set(KbCategoryEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void moveNote(Long userId, Long noteId, Long targetFolderId) {
        KbNoteEntity e = noteMapper.selectById(noteId);
        if (e == null || !Objects.equals(e.getUserId(), userId) || Objects.equals(e.getIsDeleted(), 1)) {
            throw new BusinessException(404, "文档不存在");
        }
        if (Objects.equals(e.getCategoryId(), targetFolderId)) {
            return;
        }
        if (targetFolderId != null) {
            categoryService.requireOwned(targetFolderId, userId);
        }
        int sort = nextNoteSortOrder(userId, targetFolderId);
        noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getId, noteId)
                .eq(KbNoteEntity::getUserId, userId)
                .eq(KbNoteEntity::getIsDeleted, 0)
                .set(KbNoteEntity::getCategoryId, targetFolderId)
                .set(KbNoteEntity::getSortOrder, sort)
                .set(KbNoteEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void reorderFolders(Long userId, Long parentId, List<Long> orderedIds) {
        // 校验均属该父级
        List<KbCategoryEntity> siblings = categoryMapper.selectList(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId)
                        .isNull(parentId == null, KbCategoryEntity::getParentId)
                        .eq(parentId != null, KbCategoryEntity::getParentId, parentId));
        Set<Long> allowed = new HashSet<>();
        for (KbCategoryEntity s : siblings) {
            allowed.add(s.getId());
        }
        for (Long id : orderedIds) {
            if (!allowed.contains(id)) {
                throw new BusinessException(400, "排序列表包含不属于该层级的文件夹");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int i = 0;
        for (Long id : orderedIds) {
            categoryMapper.update(null, new LambdaUpdateWrapper<KbCategoryEntity>()
                    .eq(KbCategoryEntity::getId, id)
                    .eq(KbCategoryEntity::getUserId, userId)
                    .set(KbCategoryEntity::getSortOrder, i++)
                    .set(KbCategoryEntity::getUpdatedAt, now));
        }
        // 未出现在列表中的同级，接在后面
        for (KbCategoryEntity s : siblings) {
            if (!orderedIds.contains(s.getId())) {
                categoryMapper.update(null, new LambdaUpdateWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getId, s.getId())
                        .set(KbCategoryEntity::getSortOrder, i++)
                        .set(KbCategoryEntity::getUpdatedAt, now));
            }
        }
    }

    private void reorderNotes(Long userId, Long folderId, List<Long> orderedIds) {
        List<KbNoteEntity> siblings = noteMapper.selectList(
                new LambdaQueryWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getUserId, userId)
                        .eq(KbNoteEntity::getIsDeleted, 0)
                        .isNull(folderId == null, KbNoteEntity::getCategoryId)
                        .eq(folderId != null, KbNoteEntity::getCategoryId, folderId)
                        .select(KbNoteEntity::getId));
        Set<Long> allowed = new HashSet<>();
        for (KbNoteEntity s : siblings) {
            allowed.add(s.getId());
        }
        for (Long id : orderedIds) {
            if (!allowed.contains(id)) {
                throw new BusinessException(400, "排序列表包含不属于该文件夹的文档");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int i = 0;
        for (Long id : orderedIds) {
            noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                    .eq(KbNoteEntity::getId, id)
                    .eq(KbNoteEntity::getUserId, userId)
                    .eq(KbNoteEntity::getIsDeleted, 0)
                    .set(KbNoteEntity::getSortOrder, i++)
                    .set(KbNoteEntity::getUpdatedAt, now));
        }
        for (KbNoteEntity s : siblings) {
            if (!orderedIds.contains(s.getId())) {
                noteMapper.update(null, new LambdaUpdateWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getId, s.getId())
                        .set(KbNoteEntity::getSortOrder, i++)
                        .set(KbNoteEntity::getUpdatedAt, now));
            }
        }
    }

    private int nextFolderSortOrder(Long userId, Long parentId) {
        List<KbCategoryEntity> list = categoryMapper.selectList(
                new LambdaQueryWrapper<KbCategoryEntity>()
                        .eq(KbCategoryEntity::getUserId, userId)
                        .isNull(parentId == null, KbCategoryEntity::getParentId)
                        .eq(parentId != null, KbCategoryEntity::getParentId, parentId)
                        .orderByDesc(KbCategoryEntity::getSortOrder)
                        .last("LIMIT 1"));
        if (list.isEmpty() || list.get(0).getSortOrder() == null) {
            return 0;
        }
        return list.get(0).getSortOrder() + 1;
    }

    private int nextNoteSortOrder(Long userId, Long folderId) {
        List<KbNoteEntity> list = noteMapper.selectList(
                new LambdaQueryWrapper<KbNoteEntity>()
                        .eq(KbNoteEntity::getUserId, userId)
                        .eq(KbNoteEntity::getIsDeleted, 0)
                        .isNull(folderId == null, KbNoteEntity::getCategoryId)
                        .eq(folderId != null, KbNoteEntity::getCategoryId, folderId)
                        .select(KbNoteEntity::getSortOrder)
                        .orderByDesc(KbNoteEntity::getSortOrder)
                        .last("LIMIT 1"));
        if (list.isEmpty() || list.get(0).getSortOrder() == null) {
            return 0;
        }
        return list.get(0).getSortOrder() + 1;
    }

    private boolean isDescendantFolder(Long userId, Long ancestorId, Long maybeDescendantId) {
        Long cursor = maybeDescendantId;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            if (Objects.equals(cursor, ancestorId)) {
                return true;
            }
            KbCategoryEntity p = categoryMapper.selectById(cursor);
            if (p == null || !Objects.equals(p.getUserId(), userId)) {
                break;
            }
            cursor = p.getParentId();
        }
        return false;
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

    private static String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new BusinessException(400, "type 不能为空");
        }
        return type.trim().toLowerCase();
    }

    private static ExplorerNodeResponse toNoteNode(KbNoteEntity n) {
        String title = StringUtils.hasText(n.getTitle()) ? n.getTitle() : "未命名笔记";
        return ExplorerNodeResponse.builder()
                .type("note")
                .id(n.getId())
                .name(title)
                .parentId(n.getCategoryId())
                .contentFormat(StringUtils.hasText(n.getContentFormat()) ? n.getContentFormat() : "html")
                .pinned(Objects.equals(n.getIsPinned(), 1))
                .snippet(n.getSnippet())
                .sortOrder(n.getSortOrder() != null ? n.getSortOrder() : 0)
                .updatedAt(n.getUpdatedAt() != null ? n.getUpdatedAt() : n.getCreatedAt())
                .children(List.of())
                .build();
    }

    private static void sortChildren(List<ExplorerNodeResponse> children) {
        if (children == null || children.size() <= 1) {
            return;
        }
        // 文件夹在前、文档在后；同组内：置顶 → sort_order 升序 → id 升序（稳定，不受打开/保存影响）
        children.sort(Comparator
                .comparing((ExplorerNodeResponse n) -> !"folder".equals(n.getType()))
                .thenComparing(n -> {
                    if ("note".equals(n.getType())) {
                        return n.isPinned() ? 0 : 1;
                    }
                    return 0;
                })
                .thenComparing(n -> n.getSortOrder() != null ? n.getSortOrder() : 0)
                .thenComparing(n -> n.getId() != null ? n.getId() : 0L)
                .thenComparing(n -> n.getName() == null ? "" : n.getName(), String.CASE_INSENSITIVE_ORDER));
    }

    private static void sortRootMixed(List<ExplorerNodeResponse> roots) {
        // 已是 folders then notes；各段内再稳一次
        List<ExplorerNodeResponse> folders = new ArrayList<>();
        List<ExplorerNodeResponse> notes = new ArrayList<>();
        for (ExplorerNodeResponse n : roots) {
            if ("folder".equals(n.getType())) folders.add(n);
            else notes.add(n);
        }
        sortChildren(folders);
        sortChildren(notes);
        roots.clear();
        roots.addAll(folders);
        roots.addAll(notes);
    }
}
