package com.dwcode.okxbot.video.controller;

import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.video.dto.*;
import com.dwcode.okxbot.video.event.VideoTaskEventPublisher;
import com.dwcode.okxbot.video.service.AiModelConfigService;
import com.dwcode.okxbot.video.service.VideoCookieService;
import com.dwcode.okxbot.video.service.VideoProcessService;
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
 * 视频核心内容提取 REST API（v2 持久化版）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
public class VideoProcessController {

    private final VideoProcessService videoProcessService;
    private final AiModelConfigService aiModelConfigService;
    private final VideoTaskEventPublisher videoTaskEventPublisher;
    private final VideoCookieService videoCookieService;

    /**
     * 提交视频链接，异步处理，返回任务 ID。
     */
    @PostMapping("/process")
    public ApiResult<VideoTaskResponse> process(@Valid @RequestBody VideoProcessRequest request) {
        log.info("收到视频处理请求: url={}", request.getUrl());
        return ApiResult.ok(videoProcessService.submit(request));
    }

    /**
     * 当前用户任务状态 SSE 流（步骤变更实时推送）。
     * 需 Authorization: Bearer；前端用 fetch 读流（原生 EventSource 不便带头）。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents() {
        Long userId = SecurityUtils.requireCurrentUserId();
        log.debug("打开视频任务 SSE: userId={}", userId);
        return videoTaskEventPublisher.subscribe(userId);
    }

    // ---------- LLM 模型（任务选择：仅启用 + 有 api-key 的供应商） ----------

    /**
     * 可用模型列表（数据库 enabled=1，按供应商分组）。
     *
     * @param capability 可选 chat（默认）| image | video_omni
     */
    @GetMapping("/models")
    public ApiResult<List<Map<String, Object>>> listModels(
            @RequestParam(required = false) String capability) {
        if (capability == null || capability.isBlank()) {
            return ApiResult.ok(aiModelConfigService.listEnabledGroupedByProvider());
        }
        return ApiResult.ok(aiModelConfigService.listEnabledGroupedByProvider(capability));
    }

    /**
     * 测试指定模型是否可调用（短请求，默认 10s 超时）。
     */
    @PostMapping("/models/test")
    public ApiResult<LlmModelTestResponse> testModel(@Valid @RequestBody LlmModelTestRequest request) {
        log.info("测试 LLM 模型: provider={}, model={}", request.getProvider(), request.getModel());
        return ApiResult.ok(videoProcessService.testLlmModel(request));
    }

    // ---------- 模型管理 CRUD（存库，不再依赖 yml models） ----------

    /**
     * 管理列表：全部模型配置（含禁用）。
     * @param capability 可选 chat | image | video_omni；空=全部
     */
    @GetMapping("/model-configs")
    public ApiResult<List<AiModelConfigResponse>> listModelConfigs(
            @RequestParam(required = false) String capability) {
        return ApiResult.ok(aiModelConfigService.listAll(capability));
    }

    /**
     * 有 api-key 的供应商下拉（管理页用）。
     */
    @GetMapping("/model-configs/providers")
    public ApiResult<List<Map<String, String>>> listProviders() {
        return ApiResult.ok(aiModelConfigService.listProviders());
    }

    @GetMapping("/model-configs/{id}")
    public ApiResult<AiModelConfigResponse> getModelConfig(@PathVariable Long id) {
        return ApiResult.ok(aiModelConfigService.getById(id));
    }

    @PostMapping("/model-configs")
    public ApiResult<AiModelConfigResponse> createModelConfig(@Valid @RequestBody AiModelConfigRequest request) {
        log.info("新增模型配置: provider={}, modelId={}", request.getProvider(), request.getModelId());
        return ApiResult.ok(aiModelConfigService.create(request));
    }

    @PutMapping("/model-configs/{id}")
    public ApiResult<AiModelConfigResponse> updateModelConfig(
            @PathVariable Long id,
            @Valid @RequestBody AiModelConfigRequest request) {
        log.info("更新模型配置: id={}", id);
        return ApiResult.ok(aiModelConfigService.update(id, request));
    }

    @DeleteMapping("/model-configs/{id}")
    public ApiResult<Void> deleteModelConfig(@PathVariable Long id) {
        log.info("删除模型配置: id={}", id);
        aiModelConfigService.delete(id);
        return ApiResult.ok();
    }

    // ---------- Cookie（抖音等，服务端全局 cookies.txt） ----------

    /**
     * Cookie 文件状态（不返回明文）。
     */
    @GetMapping("/cookies")
    public ApiResult<VideoCookieStatusResponse> cookieStatus(
            @RequestParam(defaultValue = "douyin") String platform) {
        return ApiResult.ok(videoCookieService.status(platform));
    }

    /**
     * 上传浏览器 Cookie 请求头字符串，写入 Netscape cookies.txt 供 yt-dlp 使用。
     */
    @PostMapping("/cookies")
    public ApiResult<VideoCookieStatusResponse> uploadCookies(
            @Valid @RequestBody VideoCookieUploadRequest request) {
        log.info("上传视频 Cookie: platform={}, len={}",
                request.getPlatform(),
                request.getCookieHeader() != null ? request.getCookieHeader().length() : 0);
        return ApiResult.ok(videoCookieService.upload(request));
    }

    /**
     * 清除已上传的 Cookie 文件。
     */
    @DeleteMapping("/cookies")
    public ApiResult<Void> clearCookies(@RequestParam(defaultValue = "douyin") String platform) {
        log.info("清除视频 Cookie: platform={}", platform);
        videoCookieService.clear(platform);
        return ApiResult.ok();
    }

    // ---------- 任务 ----------

    @GetMapping("/tasks/{taskId}")
    public ApiResult<VideoTaskResponse> getTask(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getStatus(taskId));
    }

    @GetMapping("/status/{taskId}")
    public ApiResult<VideoTaskResponse> status(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getStatus(taskId));
    }

    @GetMapping("/tasks")
    public ApiResult<VideoTaskPageResponse> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(videoProcessService.listTasks(page, size));
    }

    @GetMapping("/tasks/recent")
    public ApiResult<List<VideoTaskResponse>> listRecent(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(videoProcessService.listRecent(limit));
    }

    @GetMapping("/tasks/{taskId}/transcription")
    public ApiResult<TranscriptionResult> getTranscription(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getTranscription(taskId));
    }

    @GetMapping("/tasks/{taskId}/summary")
    public ApiResult<VideoSummaryPart> getSummary(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getSummary(taskId));
    }

    @GetMapping("/tasks/{taskId}/video")
    public ResponseEntity<Resource> downloadVideo(@PathVariable Long taskId) {
        return videoProcessService.downloadVideo(taskId);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResult<Void> deleteTask(@PathVariable Long taskId) {
        log.info("删除视频任务: taskId={}", taskId);
        videoProcessService.deleteTask(taskId);
        return ApiResult.ok();
    }

    /**
     * 暂停进行中/排队中任务，并调度其它排队任务。
     */
    @PostMapping("/tasks/{taskId}/pause")
    public ApiResult<VideoTaskResponse> pauseTask(@PathVariable Long taskId) {
        log.info("暂停视频任务: taskId={}", taskId);
        return ApiResult.ok(videoProcessService.pauseTask(taskId));
    }

    /**
     * 失败 / 暂停 / 成功任务重试（可 body 指定 llmProvider/llmModel）。
     * 成功任务重试会清空原产物后整流水线重跑。
     */
    @PostMapping("/tasks/{taskId}/retry")
    public ApiResult<VideoTaskResponse> retryTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) VideoRetryRequest request) {
        log.info("重试视频任务: taskId={}, llm={}/{}",
                taskId,
                request != null ? request.getLlmProvider() : null,
                request != null ? request.getLlmModel() : null);
        return ApiResult.ok(videoProcessService.retryTask(taskId, request));
    }
}
