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
