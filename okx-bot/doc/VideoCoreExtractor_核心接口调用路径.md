# VideoCoreExtractor — 核心接口调用路径梳理

**模块**：视频核心内容提取（链接 → 下载 → 转录 → LLM 总结）  
**代码包**：`com.dwcode.okxbot.video`  
**Controller**：`VideoProcessController`（`/api/v1/video/*`）  
**文档目标**：按**核心接口**串起「HTTP → Service → 调度/流水线 → 下游 Client」的真实调用链，并说明为何这样分层。  
**对齐代码日期**：2026-07-16  

> 更完整的状态机 / 配置 / 表结构见：`VideoCoreExtractor_核心逻辑与后端实现文档.md`。  
> 本文只保留**读代码最有用的主路径**，附带可对照的示例代码片段。

---

## 1. 总览：一次「视频提取」发生了什么

```
前端 okx-trading-web  /video-extract
  videoApi.process(...)  ──POST──►  /api/v1/video/process
  video.events SSE      ◄─stream─  /api/v1/video/events
  videoApi.getTask(...)  ──GET───►  /api/v1/video/tasks/{id}   （可选兜底轮询）

后端分层（从外到内）：

  Controller（薄：参数校验 + 鉴权上下文 + 包装 ApiResult）
       │
       ▼
  VideoProcessService（任务生命周期：提交 / 查 / 暂停 / 重试 / 删）
       │
       ├─ insert DB (PENDING) + notify 调度
       │
       ▼
  VideoTaskScheduler（并发槽位 ≤ 2，从 PENDING 拉起）
       │
       ▼
  VideoTaskAsyncRunner（@Async 独立 Bean，保证代理生效）
       │
       ▼
  VideoProcessingPipeline（固定 3 步编排）
       ├─ VideoDownloadService   → yt-dlp + FFmpeg（本机进程）
       ├─ TranscriptionService   → WhisperClient → HTTP Whisper 微服务
       └─ SummarizationService   → LlmChatClient → OpenAI 兼容 LLM API
```

**设计一句话**：HTTP 只负责「接单与查单」；重活在专用线程池 + 槽位调度里跑；每步状态写库并 SSE 推给当前用户。

---

## 2. 分层职责（为什么这么拆）

| 层 | 类 | 职责 | 为什么独立 |
|----|-----|------|------------|
| 入口 | `VideoProcessController` | REST 映射、日志、统一 `ApiResult` | 不写业务，方便换协议/加鉴权 |
| 应用服务 | `VideoProcessService` | 建任务、鉴权归属、暂停/重试规则、读结果 | 事务边界与权限集中在一处 |
| 调度 | `VideoTaskScheduler` | 并发上限、PENDING 队列、暂停标记 | 下载/ASR 很重，必须限流，不能「来一个就 `@Async`」无上限 |
| 异步触发 | `VideoTaskAsyncRunner` | `@Async(videoTaskExecutor)` | **独立 Bean**，避免同类自调用导致 `@Async` 失效 |
| 编排 | `VideoProcessingPipeline` | Download → Transcribe → Summarize | Agent 编排与领域服务解耦，步骤可观测、可暂停 |
| 领域服务 | `VideoDownloadService` / `TranscriptionService` / `SummarizationService` | 单步业务 | 可单测、可替换实现 |
| 出站 Client | `WhisperClient` / `LlmChatClient` | HTTP 细节、超时、重试 | 业务层不拼 multipart / OpenAI body |
| 存储 | `StorageService` | `{work-dir}/{taskId}/` 文件路径 | DB 存路径与 JSON；大文件不进库 |
| 推送 | `VideoTaskEventPublisher` | 按 `userId` 的 SSE fan-out | 前端少轮询；单机内存即可，多实例再换 Redis |

---

## 3. 核心接口清单（只列主路径）

| 能力 | Method | Path | Service 入口 | 是否触发流水线 |
|------|--------|------|--------------|----------------|
| **提交提取** | `POST` | `/api/v1/video/process` | `submit` | 是（入队） |
| **任务详情/结果** | `GET` | `/api/v1/video/tasks/{taskId}` | `getStatus` | 否 |
| **状态 SSE** | `GET` | `/api/v1/video/events` | `eventPublisher.subscribe` | 否 |
| **任务列表** | `GET` | `/api/v1/video/tasks` | `listTasks` | 否 |
| **暂停** | `POST` | `/api/v1/video/tasks/{taskId}/pause` | `pauseTask` | 间接（腾槽位） |
| **重试** | `POST` | `/api/v1/video/tasks/{taskId}/retry` | `retryTask` | 是（重新入队） |
| **转录** | `GET` | `/api/v1/video/tasks/{taskId}/transcription` | `getTranscription` | 否 |
| **摘要** | `GET` | `/api/v1/video/tasks/{taskId}/summary` | `getSummary` | 否 |
| **视频流** | `GET` | `/api/v1/video/tasks/{taskId}/video` | `downloadVideo` | 否 |
| **删除** | `DELETE` | `/api/v1/video/tasks/{taskId}` | `deleteTask` | 否 |
| **可用模型** | `GET` | `/api/v1/video/models` | `AiModelConfigService` | 否 |
| **测模型** | `POST` | `/api/v1/video/models/test` | `testLlmModel` → `LlmChatClient` | 否 |

模型 CRUD（`/model-configs/**`）属于配置侧，本文不展开调用链。

---

## 4. 核心接口 ①：提交任务 `POST /process`

### 4.1 调用路径

```
VideoProcessController.process(request)
  └─ VideoProcessService.submit(request)
       ├─ 解析 LLM：options → video.llm.provider → ai.default-provider → 首个有 key 的供应商
       ├─ 校验 Provider api-key；模型空则 AiModelConfigService.firstEnabledModelId
       ├─ 组装 VideoTaskEntity（userId / url / platform / PENDING / options）
       ├─ videoTaskMapper.insert
       ├─ taskScheduler.notifyPending()          // 尝试占槽启动
       └─ eventPublisher.publishEntity(..., TYPE_CREATED)
  返回 VideoTaskResponse（立即，不等流水线结束）
```

异步支路（与 HTTP 解耦）：

```
VideoTaskScheduler.tryStartNext()
  ├─ 统计 RUNNING 类状态 + activeTaskIds，槽位 = MAX_CONCURRENT(2) - occupied
  ├─ 查最早 PENDING 任务
  └─ VideoTaskAsyncRunner.runAsync(taskId)   // @Async videoTaskExecutor
        └─ VideoProcessingPipeline.run(taskId)
             ├─ Download → Transcribe → Summarize
             └─ finally: taskScheduler.markFinished(taskId) → 再 tryStartNext
```

### 4.2 Controller / Service 示例（源码对照）

```java
// VideoProcessController
@PostMapping("/process")
public ApiResult<VideoTaskResponse> process(@Valid @RequestBody VideoProcessRequest request) {
    return ApiResult.ok(videoProcessService.submit(request));
}
```

```java
// VideoProcessService.submit — 核心骨架
public VideoTaskResponse submit(VideoProcessRequest request) {
    // 1) 解析并校验 llmProvider / llmModel
    // 2) entity: PENDING + sourceUrl + platform + 选项
    entity.setUserId(SecurityUtils.requireCurrentUserId());
    entity.setStatus(VideoTaskStatus.PENDING.name());
    videoTaskMapper.insert(entity);

    taskScheduler.notifyPending();  // 不在这里直接 pipeline.run
    eventPublisher.publishEntity(entity, VideoTaskEventPublisher.TYPE_CREATED);
    return toResponse(entity, false);
}
```

```java
// VideoTaskAsyncRunner — 必须独立 Bean
@Async(VideoAsyncConfig.VIDEO_TASK_EXECUTOR)
public void runAsync(Long taskId) {
    pipeline.run(taskId);
}
```

### 4.3 前端示例

```typescript
// okx-trading-web/src/api/video.api.ts
videoApi.process({
  url: 'https://www.bilibili.com/video/BVxxxx',
  options: {
    language: 'zh',
    extractMindMap: true,
    generateRepurposeScript: true,
    llmProvider: 'nvidia',
    llmModel: 'deepseek-ai/deepseek-v4-flash'
  }
})
// → 立刻拿到 { taskId, status: 'PENDING', currentStep: '排队中', ... }
```

### 4.4 HTTP 请求体示例

```json
{
  "url": "https://www.bilibili.com/video/BVxxxx",
  "options": {
    "language": "zh",
    "extractMindMap": true,
    "generateRepurposeScript": true,
    "llmProvider": "nvidia",
    "llmModel": "deepseek-ai/deepseek-v4-flash"
  }
}
```

### 4.5 为什么这样设计

| 决策 | 原因 |
|------|------|
| **同步只建单，异步跑流水线** | 下载 + Whisper + LLM 动辄分钟级，HTTP 不能同步阻塞 |
| **Scheduler 再 `@Async`，而不是 submit 里直接 `pipeline.run`** | 需要全局并发上限与 FIFO 排队；pause/retry 也共用同一调度入口 |
| **任务级绑定 llmProvider/model** | 提交时锁定模型，避免运行中改全局配置导致结果不可复现 |
| **`@Async` 放独立 Runner** | Spring AOP 代理：同类 `this.xxx()` 调不到代理，异步会静默变成同步 |
| **专用 `videoTaskExecutor`** | 与交易 Job 线程池隔离，避免长任务饿死策略调度 |
| **SSE + 写库双通道** | 前端实时 UI；刷新后仍可 `GET /tasks/{id}` 恢复状态 |

---

## 5. 流水线内部：`VideoProcessingPipeline.run`（被调度触发）

这不是 REST 接口，但是 **`/process` 真正干活的主路径**，必须串清楚。

### 5.1 步骤调用路径

```
VideoProcessingPipeline.run(taskId)
  ├─ 读库；PAUSED/SUCCESS 则 markFinished 返回
  ├─ markRunning + clearPauseRequest
  │
  ├─ [间隙] shouldPause? → markPaused + return
  ├─ Step1 DOWNLOADING
  │    VideoDownloadService.download(url, taskIdStr)
  │      ├─ StorageService.ensureTaskDir
  │      ├─ yt-dlp 拉元数据 / 下载（ProcessExecutor）
  │      ├─ 必要时 ffmpeg merge 分轨
  │      └─ ffmpeg 抽 audio.* → DownloadResult{title, videoPath, audioPath, duration}
  │    写库：title / paths / downloadDurationMs + SSE
  │
  ├─ [间隙] shouldPause?
  ├─ Step2 TRANSCRIBING
  │    TranscriptionService.transcribe(audioPath, language)
  │      └─ WhisperClient.transcribe(File, language)
  │           POST {whisper.base-url}/v1/audio/transcriptions
  │           multipart: file + model + verbose_json + segments
  │    写库：transcriptionJson + 落盘 transcription.json + SSE
  │
  ├─ [间隙] shouldPause?
  ├─ Step3 SUMMARIZING（再读库，取最新 llm 字段）
  │    SummarizationService.summarize(title, transcription, mindMap, repurpose, lang, provider, model)
  │      ├─ 拼 system/user prompt（字幕带时间戳，超长头尾截断）
  │      └─ LlmChatClient.chat(...) → 解析 JSON → VideoSummaryPart
  │    组装 VideoSummaryResponse → resultJson + summaryJson 落盘
  │    status = SUCCESS + totalDurationMs + SSE
  │
  ├─ 可选 cleanupMedia（配置开关）
  ├─ catch → FAILED（若 pause 信号优先记 PAUSED）
  └─ finally → markFinished → tryStartNext
```

### 5.2 编排示例代码

```java
// VideoProcessingPipeline.run — 三步骨架
updateStatus(task, VideoTaskStatus.DOWNLOADING, "正在下载视频并提取音频");
DownloadResult download = downloadService.download(task.getSourceUrl(), taskIdStr);

updateStatus(task, VideoTaskStatus.TRANSCRIBING, "正在转录音频");
TranscriptionResult transcription = transcriptionService.transcribe(
        download.getAudioPath(), task.getLanguage());

updateStatus(task, VideoTaskStatus.SUMMARIZING, "正在生成结构化摘要…");
VideoSummaryPart summaryPart = summarizationService.summarize(
        task.getTitle(), transcription, mindMap, repurpose, task.getLanguage(),
        task.getLlmProvider(), task.getLlmModel());

// 写 resultJson → SUCCESS
```

### 5.3 状态机（与接口返回字段对应）

```
PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING → SUCCESS
   │           │              │               │
   └───────────┴──────────────┴───────────────┴──► PAUSED（协作式）
                                                     FAILED（异常）
```

- `status` / `currentStep` / 各 `*DurationMs` 均来自 `video_task` 表。  
- 终态：`SUCCESS` / `FAILED` / `PAUSED`（`VideoTaskStatus.isTerminal()`）。

### 5.4 为什么固定三步、步骤间隙才能 pause

| 决策 | 原因 |
|------|------|
| **固定顺序，不做通用 Agent 图** | 业务路径单一，可预期、易排障；失败点明确 |
| **协作式暂停（步骤间隙 / 异常里认 pause 标记）** | yt-dlp / Whisper 是阻塞外部调用，硬杀线程不安全；标记 + 间隙退出可释放槽位给排队任务 |
| **产物双写：DB JSON + 文件** | 列表/详情快查走 DB；磁盘便于人工排查与 `<video>` 播放 |
| **Whisper / LLM 抽 Client** | 可换 base-url、超时、重试策略，不污染 Service |

### 5.5 产物目录约定

```
{video.work-dir}/{taskId}/
  video.mp4              # 原片（或合并后）
  audio.mp3              # Whisper 输入
  transcription.json
  summary.json
```

库表 `video_task` 存路径字段 + `transcription_json` / `summary_json` / `result_json`。

---

## 6. 核心接口 ②：查任务 `GET /tasks/{taskId}`

### 6.1 调用路径

```
VideoProcessController.getTask(taskId)
  └─ VideoProcessService.getStatus(taskId)
       ├─ requireOwnedTask(taskId)   // 存在性 + 当前用户归属（403）
       └─ toResponse(entity, includeResult=true)
            └─ SUCCESS 且 resultJson 非空 → 反序列化为 VideoSummaryResponse 填 result
```

兼容路径：`GET /status/{taskId}` 同样落到 `getStatus`。

### 6.2 示例

```java
public VideoTaskResponse getStatus(Long taskId) {
    return toResponse(requireOwnedTask(taskId), true);
}
```

```typescript
const { data } = await videoApi.getTask(taskId)
// data.status: PENDING | DOWNLOADING | ... | SUCCESS
// data.result: 仅 SUCCESS 时带完整摘要 + 转录
```

### 6.3 为什么 `includeResult` 只在详情打开

- 列表接口 `listTasks(..., includeResult=false)` 不带大 JSON，减轻带宽。  
- 详情才解析 `resultJson`；解析失败会 fallback 用 `summaryJson` + `transcriptionJson` 拼装。

### 6.4 归属校验设计

```java
// requireOwnedTask：非本人 403；user_id 为空的历史数据首次访问归当前用户
if (entity.getUserId() != null && !entity.getUserId().equals(userId)) {
    throw new BusinessException(403, "无权访问该任务");
}
```

视频提取任务与登录用户绑定，避免 taskId 枚举读到他人内容。

---

## 7. 核心接口 ③：SSE `GET /events`

### 7.1 调用路径

```
VideoProcessController.streamEvents()
  └─ SecurityUtils.requireCurrentUserId()
  └─ VideoTaskEventPublisher.subscribe(userId)
       ├─ 每用户最多 3 条 SseEmitter（踢最旧）
       └─ 立刻推 type=connected

// 写端（submit / pipeline / pause / retry / delete 等）
eventPublisher.publishEntity(entity, TYPE_CREATED | TYPE_STATUS)
eventPublisher.publishDeleted(userId, taskId)
  └─ 按 userId 找到 emitters fan-out JSON
```

### 7.2 事件类型

| type | 含义 |
|------|------|
| `connected` | 订阅成功 |
| `ping` | 心跳（定时） |
| `task.created` | 新任务 |
| `task.status` | 状态/步骤/耗时变化 |
| `task.deleted` | 删除 |

### 7.3 前端示例（需 Bearer，故用 fetch 而非原生 EventSource）

```typescript
// video.events.ts 思路
fetch('/api/v1/video/events', {
  headers: { Authorization: `Bearer ${token}` }
})
// 读 stream → 解析 task.status → 刷新列表/当前任务 UI
```

### 7.4 为什么用内存 SSE

| 决策 | 原因 |
|------|------|
| **按 userId 隔离** | 只推自己的任务，不广播全站 |
| **连接数上限 3** | 防多开 Tab 泄漏连接 |
| **单机 ConcurrentHashMap** | 当前部署体量足够；文档已注明多实例可换 Redis Pub/Sub |
| **仍保留 GET 详情** | SSE 断线时有轮询兜底，不绑死推送通道 |

---

## 8. 核心接口 ④：暂停 `POST /tasks/{taskId}/pause`

### 8.1 调用路径

```
VideoProcessController.pauseTask(taskId)
  └─ VideoProcessService.pauseTask(taskId)
       ├─ requireOwnedTask
       ├─ 仅 PENDING / DOWNLOADING / TRANSCRIBING / SUMMARIZING 可暂停
       │
       ├─ 若 PENDING：
       │    status=PAUSED，直接 markFinished + SSE
       │
       └─ 若进行中：
            taskScheduler.requestPause(taskId)   // 写入 pauseRequested 集合
            库状态先标 PAUSED（currentStep 提示等待当前步骤结束）
            taskScheduler.tryStartNext()         // 腾槽位给其它 PENDING
```

流水线侧：

```
shouldPause / catch 中 isPauseRequested
  → markPaused(...)
  → finally markFinished → 清 pause 标记并调度下一家
```

### 8.2 示例

```java
// 进行中暂停
taskScheduler.requestPause(taskId);
entity.setStatus(VideoTaskStatus.PAUSED.name());
entity.setCurrentStep("暂停中，等待当前步骤结束…");
videoTaskMapper.updateById(entity);
taskScheduler.tryStartNext();
```

### 8.3 为什么协作式而不是 `Thread.interrupt`

- 外部进程（yt-dlp）与 OkHttp 调用对 interrupt 语义不统一。  
- 步骤边界退出：状态一致、文件半成品可接受、槽位立刻可被排队任务占用。  
- UI 可立刻显示「已暂停」，流水线在当前阻塞调用返回后再落最终 `PAUSED`。

---

## 9. 核心接口 ⑤：重试 `POST /tasks/{taskId}/retry`

### 9.1 调用路径

```
VideoProcessController.retryTask(taskId, body?)
  └─ VideoProcessService.retryTask(taskId, request)
       ├─ 仅 FAILED / PAUSED / SUCCESS 可重试
       ├─ 可选覆盖 llmProvider / llmModel（同样校验 api-key / 模型库）
       ├─ storageService.deleteTaskDir(taskId)     // 清空旧媒体与 JSON 文件
       ├─ clearPauseRequest
       ├─ 清空 entity 产物字段与各 duration → status=PENDING
       ├─ updateById + SSE
       └─ taskScheduler.notifyPending()            // 重新走调度
```

之后路径与「提交」相同：Scheduler → AsyncRunner → Pipeline 全量重跑。

### 9.2 请求体示例

```json
{
  "llmProvider": "nvidia",
  "llmModel": "another-model-id"
}
```

body 可空：沿用任务原有模型。

### 9.3 为什么成功也可重试

- 同一链接换模型再总结、或下载不完整后重跑。  
- **先删目录再 PENDING**，避免旧 `video.mp4` 与新 result 混用。  
- 不新建 taskId：历史列表、前端路由与文件路径保持稳定。

---

## 10. 核心接口 ⑥：列表 / 转录 / 摘要 / 视频流 / 删除

### 10.1 列表 `GET /tasks?page&size`

```
listTasks → MyBatis-Plus 分页
  .eq(userId).orderByDesc(createdAt)
  → toResponse(e, false)   // 不含大 result
```

### 10.2 转录 / 摘要

```
getTranscription → 读 transcriptionJson → TranscriptionResult
getSummary       → 优先 summaryJson，否则从 resultJson.summary 拆
```

两者都不重新调 Whisper/LLM，只读已落库结果。

### 10.3 视频流 `GET /tasks/{taskId}/video`

```
downloadVideo
  → storageService.requireExistingFile(videoPath)
  → FileSystemResource + Content-Disposition: inline
```

前端：

```typescript
videoApi.videoStreamUrl(taskId)  // `/api/v1/video/tasks/${taskId}/video`
// 挂到 <video src=...>，经 Vite 代理带鉴权策略视项目配置而定
```

### 10.4 删除 `DELETE /tasks/{taskId}`

```
deleteTask
  → deleteTaskDir + 路径字段文件 best-effort 删除
  → videoTaskMapper.deleteById
  → publishDeleted
```

---

## 11. 辅助接口：模型列表与连通性测试

提交前前端通常：

```
GET  /models          → 启用模型按供应商分组（库表 ai_model_config）
POST /models/test     → LlmChatClient.testModel（短超时、不重试）
```

```
Controller.testModel
  → VideoProcessService.testLlmModel
       → LlmChatClient.testModel(provider, model)
            chatInternal(..., maxRetries=0, testHttpClient)
```

**设计意图**：供应商密钥仍在 yml（`ai.providers.*`），可选模型存库可 CRUD；测试接口与长任务分离超时策略，避免管理页卡住。

---

## 12. 端到端时序（提交 → 完成）

```
前端                Controller              Service               Scheduler            Pipeline              外部
 │  POST /process      │                      │                      │                    │                   │
 │────────────────────►│ submit               │                      │                    │                   │
 │                     │─────────────────────►│ insert PENDING       │                    │                   │
 │                     │                      │ notifyPending ──────►│ tryStartNext       │                   │
 │                     │                      │ SSE created          │ runAsync ─────────►│                   │
 │◄── taskId PENDING ──│◄─────────────────────│                      │                    │                   │
 │                     │                      │                      │                    │ yt-dlp/ffmpeg     │
 │  SSE task.status    │◄─────────────────────┼──────────────────────┼────────────────────│──────────────────►│
 │  DOWNLOADING…       │                      │                      │                    │ Whisper HTTP      │
 │  TRANSCRIBING…      │                      │                      │                    │──────────────────►│
 │  SUMMARIZING…       │                      │                      │                    │ LLM HTTP          │
 │  SUCCESS + 耗时     │                      │                      │                    │──────────────────►│
 │                     │                      │                      │ markFinished       │                   │
 │  GET /tasks/{id}    │ getStatus            │ 读 resultJson        │                    │                   │
 │────────────────────►│─────────────────────►│                      │                    │                   │
 │◄── result 完整 ─────│◄─────────────────────│                      │                    │                   │
```

---

## 13. 包结构速查（跟调用链读代码）

```
com.dwcode.okxbot.video
├── controller/VideoProcessController.java   # REST
├── service/
│   ├── VideoProcessService.java             # 任务生命周期
│   ├── VideoDownloadService.java            # yt-dlp + ffmpeg
│   ├── TranscriptionService.java
│   ├── SummarizationService.java
│   ├── StorageService.java
│   └── AiModelConfigService.java
├── agent/
│   ├── VideoTaskScheduler.java              # 并发与排队
│   ├── VideoTaskAsyncRunner.java            # @Async 入口
│   └── VideoProcessingPipeline.java         # 三步编排
├── client/
│   ├── WhisperClient.java
│   └── LlmChatClient.java
├── event/VideoTaskEventPublisher.java       # SSE
├── entity/ / mapper/ / dto/ / enums/
└── config/VideoAsyncConfig.java             # videoTaskExecutor
```

前端对应：

- API：`okx-trading-web/src/api/video.api.ts`  
- SSE：`okx-trading-web/src/api/video.events.ts`  
- 页面：`/video-extract`

---

## 14. 设计原则小结（对接「为什么」）

1. **异步解耦**：接口秒回 `taskId`，重 IO/CPU 进专用池。  
2. **有界并发**：Scheduler 槽位 + 线程池双保险，保护本机与 Whisper。  
3. **可观测**：状态机 + `currentStep` + 分步耗时 + SSE。  
4. **可回放**：`work-dir` 按任务落盘，DB 存元数据与结构化 JSON。  
5. **可配置**：密钥 yml、模型库表、任务级锁定 provider/model。  
6. **资源隔离**：视频线程池不与交易 Job 混用。  
7. **协作式控制**：pause/retry 围绕 PENDING 队列与槽位设计，而不是随意杀线程。  
8. **安全边界**：`requireOwnedTask` 保证任务归属当前登录用户。

---

## 15. 相关文档

| 文档 | 内容 |
|------|------|
| `VideoCoreExtractor_核心逻辑与后端实现文档.md` | 全量业务逻辑、配置、表结构 |
| `VideoCoreExtractor_任务状态推送方案.md` | SSE 细节 |
| `VideoCoreExtractor_后端使用与验证手册.md` | 联调 / 验证步骤 |
| `AI视频生成_*` 系列 | **另一条业务线**（提示词生成视频），任务表与包均为 `aigen`，勿与本文 `video` 混淆 |

---

*文档根据当前仓库源码整理；若接口或调度语义变更，以 `VideoProcessController` / `VideoProcessService` / `VideoProcessingPipeline` 为准。*
