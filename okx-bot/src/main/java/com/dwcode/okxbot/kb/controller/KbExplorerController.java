package com.dwcode.okxbot.kb.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.kb.dto.ExplorerTreeResponse;
import com.dwcode.okxbot.kb.dto.NoteBatchMoveRequest;
import com.dwcode.okxbot.kb.dto.TreeMoveRequest;
import com.dwcode.okxbot.kb.dto.TreeReorderRequest;
import com.dwcode.okxbot.kb.service.KbExplorerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库目录树（文件夹 + 文档）。
 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KbExplorerController {

    private final KbExplorerService explorerService;

    /**
     * 完整目录树（无正文，仅标题/摘要元数据）。
     */
    @GetMapping("/tree")
    public ApiResult<ExplorerTreeResponse> tree() {
        return ApiResult.ok(explorerService.tree());
    }

    /** 移动文件夹或文档到目标文件夹 */
    @PostMapping("/tree/move")
    public ApiResult<Void> move(@Valid @RequestBody TreeMoveRequest request) {
        explorerService.move(request);
        return ApiResult.ok();
    }

    /** 同级重排（folder 或 note 各自的 orderedIds） */
    @PostMapping("/tree/reorder")
    public ApiResult<Void> reorder(@Valid @RequestBody TreeReorderRequest request) {
        explorerService.reorder(request);
        return ApiResult.ok();
    }

    /** 批量移动文档 */
    @PostMapping("/notes/batch-move")
    public ApiResult<Map<String, Integer>> batchMoveNotes(@Valid @RequestBody NoteBatchMoveRequest request) {
        int n = explorerService.batchMoveNotes(request);
        return ApiResult.ok(Map.of("moved", n));
    }
}
