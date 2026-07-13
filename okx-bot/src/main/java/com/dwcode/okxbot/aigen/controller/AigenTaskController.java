package com.dwcode.okxbot.aigen.controller;

import com.dwcode.okxbot.aigen.dto.*;
import com.dwcode.okxbot.aigen.event.AigenTaskEventPublisher;
import com.dwcode.okxbot.aigen.service.AigenTaskService;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 视频生成 REST API（Phase 0 骨架）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/aigen")
@RequiredArgsConstructor
public class AigenTaskController {

    private final AigenTaskService aigenTaskService;
    private final AigenTaskEventPublisher eventPublisher;

    @PostMapping("/tasks")
    public ApiResult<AigenTaskResponse> create(@Valid @RequestBody AigenCreateRequest request) {
        log.info("创建 aigen 任务: template={}, promptLen={}",
                request.getTemplateId(),
                request.getPrompt() != null ? request.getPrompt().length() : 0);
        return ApiResult.ok(aigenTaskService.create(request));
    }

    @GetMapping("/tasks")
    public ApiResult<AigenTaskPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(aigenTaskService.listTasks(page, size, status));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResult<AigenTaskResponse> get(@PathVariable Long taskId) {
        return ApiResult.ok(aigenTaskService.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/storyboard")
    public ApiResult<Map<String, Object>> storyboard(@PathVariable Long taskId) {
        return ApiResult.ok(aigenTaskService.getStoryboard(taskId));
    }

    /**
     * 成片流（需登录且为任务所有者）。前端建议 fetch+Authorization 转 blob 播放。
     */
    @GetMapping("/tasks/{taskId}/media/output")
    public ResponseEntity<Resource> mediaOutput(@PathVariable Long taskId) {
        return aigenTaskService.openOutputMedia(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResult<AigenTaskResponse> cancel(@PathVariable Long taskId) {
        log.info("取消 aigen 任务: {}", taskId);
        return ApiResult.ok(aigenTaskService.cancelTask(taskId));
    }

    /**
     * 暂停：排队立即暂停；进行中在规划/素材/渲染步骤边界中断。
     */
    @PostMapping("/tasks/{taskId}/pause")
    public ApiResult<AigenTaskResponse> pause(@PathVariable Long taskId) {
        log.info("暂停 aigen 任务: {}", taskId);
        return ApiResult.ok(aigenTaskService.pauseTask(taskId));
    }

    /**
     * 失败 / 取消 / 暂停 / 成功 任务重试。
     * body 可选：{ "llmProvider", "llmModel" } 覆盖模型后整流水线重跑。
     */
    @PostMapping("/tasks/{taskId}/retry")
    public ApiResult<AigenTaskResponse> retry(
            @PathVariable Long taskId,
            @RequestBody(required = false) AigenRetryRequest request) {
        log.info("重试 aigen 任务: {}, llm={}/{}",
                taskId,
                request != null ? request.getLlmProvider() : null,
                request != null ? request.getLlmModel() : null);
        return ApiResult.ok(aigenTaskService.retryTask(taskId, request));
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResult<Void> delete(@PathVariable Long taskId) {
        log.info("删除 aigen 任务: {}", taskId);
        aigenTaskService.deleteTask(taskId);
        return ApiResult.ok();
    }

    @GetMapping("/templates")
    public ApiResult<List<AigenTemplateResponse>> templates() {
        return ApiResult.ok(aigenTaskService.listTemplates());
    }

    @GetMapping("/voices")
    public ApiResult<List<Map<String, String>>> voices() {
        return ApiResult.ok(aigenTaskService.listVoices());
    }

    /**
     * 当前用户任务 SSE（需 Authorization Bearer）。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        Long userId = SecurityUtils.requireCurrentUserId();
        log.debug("打开 aigen SSE: userId={}", userId);
        return eventPublisher.subscribe(userId);
    }
}
