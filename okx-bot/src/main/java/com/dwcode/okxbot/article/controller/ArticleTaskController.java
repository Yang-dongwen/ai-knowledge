package com.dwcode.okxbot.article.controller;

import com.dwcode.okxbot.article.dto.*;
import com.dwcode.okxbot.article.event.ArticleTaskEventPublisher;
import com.dwcode.okxbot.article.service.ArticleTaskService;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 文章/新闻提取 REST API（PR-3：CRUD + SSE + mock 流水线）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/article")
@RequiredArgsConstructor
public class ArticleTaskController {

    private final ArticleTaskService taskService;
    private final ArticleTaskEventPublisher eventPublisher;

    @GetMapping("/disclaimer")
    public ApiResult<Map<String, String>> disclaimer() {
        return ApiResult.ok(Map.of("submit", taskService.submitDisclaimer()));
    }

    @GetMapping("/models")
    public ApiResult<List<Map<String, Object>>> listModels() {
        return ApiResult.ok(taskService.listChatModels());
    }

    @PostMapping("/platforms/detect")
    public ApiResult<ArticlePlatformDetectResponse> detect(
            @RequestBody(required = false) ArticlePlatformDetectRequest request) {
        return ApiResult.ok(taskService.detectPlatform(
                request != null ? request : new ArticlePlatformDetectRequest()));
    }

    @PostMapping("/tasks")
    public ApiResult<ArticleTaskResponse> create(@Valid @RequestBody ArticleCreateRequest request) {
        log.info("创建 article 任务: hasUrl={} hasPaste={}",
                request.getUrl() != null && !request.getUrl().isBlank(),
                request.getPasteText() != null && !request.getPasteText().isBlank());
        return ApiResult.ok(taskService.create(request));
    }

    @GetMapping("/tasks")
    public ApiResult<ArticleTaskPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(taskService.listTasks(page, size, status));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResult<ArticleTaskResponse> get(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/core")
    public ApiResult<Object> core(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.getCore(taskId));
    }

    @GetMapping("/tasks/{taskId}/rewrite")
    public ApiResult<Object> rewrite(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.getRewrite(taskId));
    }

    @GetMapping("/tasks/{taskId}/main-text")
    public ApiResult<Map<String, Object>> mainText(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.getMainText(taskId));
    }

    @PostMapping("/tasks/{taskId}/paste")
    public ApiResult<ArticleTaskResponse> paste(
            @PathVariable Long taskId,
            @Valid @RequestBody ArticlePasteRequest request) {
        return ApiResult.ok(taskService.paste(taskId, request));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResult<ArticleTaskResponse> cancel(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.cancelTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/pause")
    public ApiResult<ArticleTaskResponse> pause(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.pauseTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResult<ArticleTaskResponse> retry(
            @PathVariable Long taskId,
            @RequestBody(required = false) ArticleRetryRequest request) {
        return ApiResult.ok(taskService.retryTask(taskId, request));
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResult<Void> delete(@PathVariable Long taskId) {
        taskService.deleteTask(taskId);
        return ApiResult.ok();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        Long userId = SecurityUtils.requireCurrentUserId();
        return eventPublisher.subscribe(userId);
    }
}
