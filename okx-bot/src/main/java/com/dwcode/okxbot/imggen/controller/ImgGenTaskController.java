package com.dwcode.okxbot.imggen.controller;

import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.imggen.dto.*;
import com.dwcode.okxbot.imggen.event.ImgGenTaskEventPublisher;
import com.dwcode.okxbot.imggen.service.ImgGenTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 文生图 REST API。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/imggen")
@RequiredArgsConstructor
public class ImgGenTaskController {

    private final ImgGenTaskService taskService;
    private final ImgGenTaskEventPublisher eventPublisher;

    /**
     * 生图模型目录（NVIDIA FLUX 等，来自 yml imggen.flux.models）。
     */
    @GetMapping("/models")
    public ApiResult<List<ImgGenImageModelResponse>> listImageModels() {
        return ApiResult.ok(taskService.listImageModels());
    }

    /**
     * 独立润色提示词：立即返回结果，不创建任务。
     * 前端写回输入框，用户确认后再 POST /tasks 下发生图。
     */
    @PostMapping("/enhance-prompt")
    public ApiResult<ImgGenEnhanceResponse> enhancePrompt(@Valid @RequestBody ImgGenEnhanceRequest request) {
        log.info("独立润色: promptLen={}",
                request.getPrompt() != null ? request.getPrompt().length() : 0);
        return ApiResult.ok(taskService.enhancePrompt(request));
    }

    @PostMapping("/tasks")
    public ApiResult<ImgGenTaskResponse> create(@Valid @RequestBody ImgGenCreateRequest request) {
        log.info("创建 imggen 任务: promptLen={}",
                request.getPrompt() != null ? request.getPrompt().length() : 0);
        return ApiResult.ok(taskService.create(request));
    }

    @GetMapping("/tasks")
    public ApiResult<ImgGenTaskPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(taskService.listTasks(page, size, status));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResult<ImgGenTaskResponse> get(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.getTask(taskId));
    }

    /**
     * PR5：单图直链（R2 预签名）。
     *
     * @param fileName    outputs 下文件名
     * @param disposition inline | attachment
     */
    @GetMapping("/tasks/{taskId}/media-url")
    public ApiResult<com.dwcode.okxbot.storage.dto.MediaUrlResponse> mediaUrl(
            @PathVariable Long taskId,
            @RequestParam String fileName,
            @RequestParam(required = false, defaultValue = "inline") String disposition) {
        return ApiResult.ok(taskService.resolveMediaUrl(taskId, fileName, disposition));
    }

    /** 图片流代理（PR5 回退）。 */
    @GetMapping("/tasks/{taskId}/media/{fileName}")
    public ResponseEntity<Resource> media(
            @PathVariable Long taskId,
            @PathVariable String fileName) {
        return taskService.openMedia(taskId, fileName);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResult<ImgGenTaskResponse> cancel(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.cancelTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/pause")
    public ApiResult<ImgGenTaskResponse> pause(@PathVariable Long taskId) {
        return ApiResult.ok(taskService.pauseTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResult<ImgGenTaskResponse> retry(
            @PathVariable Long taskId,
            @RequestBody(required = false) ImgGenRetryRequest request) {
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
