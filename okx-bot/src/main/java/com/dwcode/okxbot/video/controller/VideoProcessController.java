package com.dwcode.okxbot.video.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.video.dto.*;
import com.dwcode.okxbot.video.service.VideoProcessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 视频核心内容提取 REST API（v2 持久化版）。
 *
 * <ul>
 *   <li>POST /api/v1/video/process — 提交异步处理任务</li>
 *   <li>GET  /api/v1/video/tasks/{taskId} — 查询任务详情（含结果）</li>
 *   <li>GET  /api/v1/video/status/{taskId} — 同上（兼容旧路径）</li>
 *   <li>GET  /api/v1/video/tasks/{taskId}/transcription — 转录文字</li>
 *   <li>GET  /api/v1/video/tasks/{taskId}/summary — 核心内容</li>
 *   <li>GET  /api/v1/video/tasks/{taskId}/video — 下载/播放原始视频</li>
 *   <li>DELETE /api/v1/video/tasks/{taskId} — 删除任务（库 + 文件）</li>
 *   <li>GET  /api/v1/video/models — 可用 LLM 模型列表</li>
 *   <li>POST /api/v1/video/models/test — 测试模型是否可用</li>
 *   <li>GET  /api/v1/video/tasks?page=0&amp;size=20 — 分页任务列表</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
public class VideoProcessController {

    private final VideoProcessService videoProcessService;

    /**
     * 提交视频链接，异步处理，返回任务 ID。
     */
    @PostMapping("/process")
    public ApiResult<VideoTaskResponse> process(@Valid @RequestBody VideoProcessRequest request) {
        log.info("收到视频处理请求: url={}", request.getUrl());
        return ApiResult.ok(videoProcessService.submit(request));
    }

    /**
     * 可用 LLM 供应商与模型（有 api-key 的）。
     */
    @GetMapping("/models")
    public ApiResult<List<Map<String, Object>>> listModels() {
        return ApiResult.ok(videoProcessService.listLlmModels());
    }

    /**
     * 测试指定模型是否可调用（短请求）。
     */
    @PostMapping("/models/test")
    public ApiResult<LlmModelTestResponse> testModel(@Valid @RequestBody LlmModelTestRequest request) {
        log.info("测试 LLM 模型: provider={}, model={}", request.getProvider(), request.getModel());
        return ApiResult.ok(videoProcessService.testLlmModel(request));
    }

    /**
     * 查询任务状态与完整结果（v2 主路径）。
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResult<VideoTaskResponse> getTask(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getStatus(taskId));
    }

    /**
     * 查询任务状态（兼容旧路径）。
     */
    @GetMapping("/status/{taskId}")
    public ApiResult<VideoTaskResponse> status(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getStatus(taskId));
    }

    /**
     * 分页任务列表。
     */
    @GetMapping("/tasks")
    public ApiResult<VideoTaskPageResponse> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(videoProcessService.listTasks(page, size));
    }

    /**
     * 最近任务列表（轻量兼容）。
     */
    @GetMapping("/tasks/recent")
    public ApiResult<List<VideoTaskResponse>> listRecent(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(videoProcessService.listRecent(limit));
    }

    /**
     * 获取带时间戳的完整转录文字。
     */
    @GetMapping("/tasks/{taskId}/transcription")
    public ApiResult<TranscriptionResult> getTranscription(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getTranscription(taskId));
    }

    /**
     * 获取 AI 提炼的核心内容。
     */
    @GetMapping("/tasks/{taskId}/summary")
    public ApiResult<VideoSummaryPart> getSummary(@PathVariable Long taskId) {
        return ApiResult.ok(videoProcessService.getSummary(taskId));
    }

    /**
     * 下载 / 在线播放原始视频文件流。
     */
    @GetMapping("/tasks/{taskId}/video")
    public ResponseEntity<Resource> downloadVideo(@PathVariable Long taskId) {
        return videoProcessService.downloadVideo(taskId);
    }

    /**
     * 删除任务及其持久化数据（数据库记录 + 本地视频/音频/JSON 文件）。
     */
    @DeleteMapping("/tasks/{taskId}")
    public ApiResult<Void> deleteTask(@PathVariable Long taskId) {
        log.info("删除视频任务: taskId={}", taskId);
        videoProcessService.deleteTask(taskId);
        return ApiResult.ok();
    }
}
