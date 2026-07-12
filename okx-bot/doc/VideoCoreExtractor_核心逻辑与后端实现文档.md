# VideoCoreExtractor 核心逻辑与后端实现文档

**模块名称**：视频核心内容提取（VideoCoreExtractor）  
**代码包**：`com.dwcode.okxbot.video`  
**文档版本**：v3.0（对齐当前代码）  
**更新日期**：2026-07-12  

本文档描述**业务核心逻辑**与**后端实现流程**，与当前仓库实现一一对应，便于二次开发与排障。

---

## 目录

1. [业务目标与边界](#1-业务目标与边界)
2. [总体架构](#2-总体架构)
3. [核心业务逻辑](#3-核心业务逻辑)
4. [包结构与职责](#4-包结构与职责)
5. [后端实现流程（按调用链）](#5-后端实现流程按调用链)
6. [各步骤实现细节](#6-各步骤实现细节)
7. [数据模型与持久化](#7-数据模型与持久化)
8. [REST API 一览](#8-rest-api-一览)
9. [配置项说明](#9-配置项说明)
10. [状态机与异常](#10-状态机与异常)
11. [前端协作要点](#11-前端协作要点)
12. [依赖与部署](#12-依赖与部署)

---

## 1. 业务目标与边界

### 1.1 目标

用户提供**视频链接**（抖音 / B站 / YouTube / 小红书等），系统异步完成：

1. **下载**视频并提取音频  
2. **语音转文字**（Whisper，带时间戳分段）  
3. **LLM 结构化总结**（要点、章节、思维导图、二创文案）  
4. **持久化**：数据库 + 本地文件系统，支持历史查询、播放、删除  

### 1.2 非目标（当前版本）

- 不做实时流式视频处理  
- 不做付费墙 / 加密视频破解  
- 不做用户权限体系（与交易模块共用无鉴权或网关层）  
- 不做前端剪辑 / 自动发帖  

### 1.3 设计原则

| 原则 | 落地方式 |
|------|----------|
| 异步解耦 | 提交任务立即返回 `taskId`，后台线程池跑流水线 |
| 可观测 | 状态机 + `currentStep` + `errorMessage` |
| 可回放 | 视频 / 音频 / JSON 按 taskId 落盘 |
| 可配置 | LLM 模型存库（`ai_model_config`）；供应商密钥仍在 yml |
| 隔离 | 专用线程池，不阻塞交易 Job |

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│  前端 okx-trading-web  /video-extract                            │
│  · 选模型 / 测试可用性 / 模型管理 / 提交任务 / 轮询结果 / 播放删除   │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP /api/v1/video/*
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Spring Boot 后端  com.dwcode.okxbot.video                        │
│  Controller → VideoProcessService → 异步 Pipeline                 │
│       │              │                                            │
│       │              ├─ VideoDownloadService (yt-dlp + FFmpeg)    │
│       │              ├─ TranscriptionService → WhisperClient      │
│       │              ├─ SummarizationService → LlmChatClient      │
│       │              └─ StorageService / AiModelConfigService     │
└───────┼──────────────┴────────────────────────────────────────────┘
        │
        ├─ MySQL: video_task / ai_model_config
        ├─ 本地盘: {work-dir}/{taskId}/video.* audio.* *.json
        ├─ Whisper: http://127.0.0.1:8000 (faster-whisper)
        └─ LLM: ai.providers.* (如 NVIDIA integrate.api.nvidia.com)
```

### 2.1 端到端数据流（主路径）

```
POST /process
  → insert video_task (PENDING)
  → @Async VideoProcessingPipeline.run(taskId)
  → DOWNLOADING: yt-dlp 下载 + ffmpeg 抽音频
  → TRANSCRIBING: 上传 audio 到 Whisper → transcription_json
  → SUMMARIZING: 字幕喂给 LLM → summary_json + result_json
  → SUCCESS / FAILED
GET /tasks/{id} 轮询查看 result
```

---

## 3. 核心业务逻辑

### 3.1 任务生命周期（状态机）

```
                    ┌─────────────┐
         提交        │   PENDING   │  排队 / 刚创建
          ─────────►│  排队中      │
                    └──────┬──────┘
                           │ 异步线程启动
                           ▼
                    ┌─────────────┐
                    │ DOWNLOADING │  yt-dlp + FFmpeg
                    └──────┬──────┘
                           │ 得到 videoPath / audioPath
                           ▼
                    ┌─────────────┐
                    │TRANSCRIBING │  Whisper 转录
                    └──────┬──────┘
                           │ transcriptionJson 入库 + 落盘
                           ▼
                    ┌─────────────┐
                    │ SUMMARIZING │  LLM 总结（任务级 provider/model）
                    └──────┬──────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
       ┌─────────────┐           ┌─────────────┐
       │   SUCCESS   │           │   FAILED    │
       │  result 完整 │           │ errorMessage│
       └─────────────┘           └─────────────┘
```

状态枚举：`VideoTaskStatus`  
- `PENDING` / `DOWNLOADING` / `TRANSCRIBING` / `SUMMARIZING` / `SUCCESS` / `FAILED`  
- 终态：`SUCCESS`、`FAILED`（`isTerminal()`）

### 3.2 创建任务时的核心规则

1. **URL 必填**，trim 后入库。  
2. **平台识别**（`StorageService.detectPlatform`）：按域名归类  
   `douyin` / `bilibili` / `youtube` / `xiaohongshu` / `other`。  
3. **LLM 解析顺序**：  
   - 请求 `options.llmProvider` / `options.llmModel`  
   - 否则 `video.llm.provider`  
   - 否则 `ai.default-provider`  
   - 否则第一个有 api-key 的供应商  
   - 模型为空时取该供应商在库中**第一个启用模型**（`AiModelConfigService.firstEnabledModelId`）  
4. **校验**：供应商必须在 yml 中且 **api-key 非空**；必须有可用模型。  
5. **选项**：  
   - `language` 默认 `zh`  
   - `extractMindMap` / `generateRepurposeScript` 默认 true → 库字段 1/0  
6. **先 insert 再异步**，接口只返回任务摘要（不含完整 result）。

### 3.3 流水线核心规则

| 步骤 | 输入 | 输出写回 | 失败 |
|------|------|----------|------|
| Download | `sourceUrl` | title, duration, videoPath, audioPath | FAILED |
| Transcribe | audioPath, language | transcriptionJson, transcriptionPath, duration | FAILED |
| Summarize | title + transcription + llm* + 选项 | summaryJson, summaryPath, resultJson | FAILED |

- 任一步抛异常 → 捕获后写 `FAILED` + `errorMessage`（截断 1000 字符）。  
- `cleanupMedia=true` 时成功后删除任务目录并清空路径字段（默认 **false**，保留媒体供播放）。

### 3.4 下载与分轨合并逻辑

1. `yt-dlp --dump-json` 取标题、时长；超时长拒绝。  
2. 按格式选择器下载（默认限高 **720p**，分片并发 **8**，优先 progressive）。  
3. 传入 `--ffmpeg-location`（ffmpeg 所在目录），尽量让 yt-dlp 自动 merge。  
4. 若产物为 **分轨**（`video.fXXX.mp4` + `video.fYYY.m4a`）：  
   - 识别 video 轨 / audio 轨  
   - 本机 ffmpeg 合并为 `video.mp4`（先 copy，失败再转码）  
5. 从**含音轨源**提取 `audio.mp3`（供 Whisper）；禁止对「纯视频轨」`-vn` 导致无流错误。

### 3.5 转录逻辑

- Java `WhisperClient`：`multipart/form-data` → `POST {whisper.base-url}/v1/audio/transcriptions`  
- `response_format=verbose_json`，解析 `text` / `segments[]` / `duration` / `language`  
- 结果：  
  - DB：`transcription_json`  
  - 文件：`{workDir}/{taskId}/transcription.json`

### 3.6 LLM 总结逻辑

1. 系统提示：只输出合法 JSON，不要 markdown 围栏。  
2. 用户提示：带时间戳字幕 + 标题 + 是否生成思维导图/二创。  
3. 字幕过长（>24000 字符）时截头保尾，中间省略。  
4. 解析 `keyPoints` / `chapters` / `mindMapMarkdown` / `repurposeScript`；失败则兜底结构。  
5. 调用 `LlmChatClient.chat(system, user, task.llmProvider, task.llmModel)`。  
6. **重试**：429/503 等指数退避（`max-retries` 等，业务总结用长超时）。  
7. **连通性测试**：独立短超时客户端（默认 10s）、**不重试**，超时/失败返回 `available=false`。

### 3.7 模型配置逻辑（库表化）

| 概念 | 存储位置 | 说明 |
|------|----------|------|
| 供应商 base-url / api-key | `application.yml` → `ai.providers` | 鉴权与端点 |
| 模型列表 | MySQL `ai_model_config` | 展示名、启用、排序 |
| 任务选用模型 | `video_task.llm_provider` / `llm_model` | 创建时快照 |

- 任务下拉：`enabled=1` 且供应商有 api-key。  
- 管理页：全部配置 CRUD，`provider+model_id` 唯一。

### 3.8 删除逻辑

`DELETE /tasks/{taskId}`：

1. 校验任务存在  
2. 删除 `{workDir}/{taskId}/` 整目录（路径必须在 workDir 下，防穿越）  
3. 尽力删除 entity 上记录的零散路径  
4. 删除 DB 行  

---

## 4. 包结构与职责

```
com.dwcode.okxbot.video
├── controller
│   └── VideoProcessController      # REST 入口
├── agent
│   ├── VideoTaskAsyncRunner        # @Async 触发，保证代理生效
│   └── VideoProcessingPipeline     # 固定流水线编排
├── service
│   ├── VideoProcessService         # 任务 CRUD 业务、提交、查询
│   ├── VideoDownloadService        # yt-dlp / ffmpeg
│   ├── TranscriptionService        # 转录门面
│   ├── SummarizationService        # LLM 总结与 JSON 解析
│   ├── StorageService              # 路径、落盘、平台识别、删目录
│   └── AiModelConfigService        # 模型配置 CRUD + 分组列表
├── client
│   ├── WhisperClient               # HTTP 调 Whisper
│   └── LlmChatClient               # OpenAI 兼容 Chat Completions
├── config
│   ├── VideoProperties             # video.* 配置
│   └── VideoAsyncConfig            # 异步线程池
├── entity / mapper / dto / enums / util
```

### 4.1 类职责矩阵

| 类 | 是否访问 DB | 是否调外部进程/HTTP | 备注 |
|----|-------------|---------------------|------|
| VideoProcessController | 否（经 Service） | 否 | 参数校验、路由 |
| VideoProcessService | 是 video_task | 否（测试经 Client） | 提交、查询、删除 |
| VideoTaskAsyncRunner | 否 | 否 | 仅 `@Async` |
| VideoProcessingPipeline | 是 | 经各 Service | 编排 |
| VideoDownloadService | 否 | yt-dlp / ffmpeg | |
| WhisperClient | 否 | Whisper HTTP | |
| LlmChatClient | 否 | LLM HTTP | 复用 AiProperties |
| AiModelConfigService | 是 ai_model_config | 否 | |
| StorageService | 否 | 文件系统 | |

---

## 5. 后端实现流程（按调用链）

### 5.1 提交任务 `POST /api/v1/video/process`

```
VideoProcessController.process(request)
  └─ VideoProcessService.submit(request)
       ├─ 解析 options（语言 / 思维导图 / 二创 / llmProvider / llmModel）
       ├─ 解析并校验 LLM 供应商与模型
       ├─ new VideoTaskEntity + insert
       ├─ VideoTaskAsyncRunner.runAsync(taskId)   // 立即返回
       └─ return VideoTaskResponse（无 result）
```

`VideoTaskAsyncRunner`：

```java
@Async(VideoAsyncConfig.VIDEO_TASK_EXECUTOR)
public void runAsync(Long taskId) {
    pipeline.run(taskId);
}
```

线程池：`core=2, max=4, queue=50, prefix=video-task-`，拒绝策略 `CallerRunsPolicy`。

### 5.2 流水线 `VideoProcessingPipeline.run`

```
1. selectById(taskId)；null 则 return
2. setStartedAt / update
3. try:
     a. updateStatus(DOWNLOADING)
        downloadService.download(url, taskIdStr)
        写 title/duration/paths → update
     b. updateStatus(TRANSCRIBING)
        transcriptionService.transcribe(audioPath, language)
        transcriptionJson + saveJson(transcription.json) → update
     c. updateStatus(SUMMARIZING)
        summarizationService.summarize(..., llmProvider, llmModel)
        summaryJson + saveJson(summary.json)
        组装 VideoSummaryResponse → resultJson
        status=SUCCESS → update
        若 cleanupMedia：删目录并清空路径
   catch:
     status=FAILED, errorMessage → update
```

> 说明：当前仓库中的 `VideoProcessingPipeline` 以「步骤间状态更新 + 异常捕获」为主路径。删除任务后若异步仍在跑，可能对已删 ID 的 update 无影响；目录删除与运行中下载可能存在竞态（后续可增强 `stillExists` 检查）。

### 5.3 查询任务 `GET /api/v1/video/tasks/{taskId}`

```
VideoProcessService.getStatus(taskId)
  └─ toResponse(entity, includeResult=true)
       ├─ 基础字段：status/title/platform/llm*/paths/时间
       ├─ videoAvailable = videoPath 存在且为文件
       └─ SUCCESS 且 resultJson 非空 → 反序列化为 VideoSummaryResponse
            失败则用 summaryJson + transcriptionJson 兜底组装
```

列表接口 `includeResult=false`，减轻体积。

### 5.4 模型测试 `POST /api/v1/video/models/test`

```
LlmChatClient.testModel(provider, model)
  ├─ 使用 testHttpClient（callTimeout = test-timeout-seconds，默认 10s）
  ├─ maxRetries = 0
  ├─ prompt: 请只回复 OK，max_tokens=32
  └─ 成功 available=true + latencyMs
      失败 available=false + errorMessage（超时统一文案）
```

### 5.5 模型管理

```
GET    /model-configs           → listAll
GET    /model-configs/providers → yml 中有 api-key 的供应商
POST   /model-configs           → create（唯一性 provider+modelId）
PUT    /model-configs/{id}      → update
DELETE /model-configs/{id}      → delete
GET    /models                  → listEnabledGroupedByProvider（任务下拉）
```

---

## 6. 各步骤实现细节

### 6.1 VideoDownloadService

**格式选择器** `buildFormatSelector()`（默认）：

```
b[height<=?720][ext=mp4]/b[height<=?720]/bv*[height<=?720]+ba/b/bv*+ba/b
```

- `preferMerged=true`：优先单文件  
- `maxHeight=0`：退回 `bv*+ba/b`  
- 可被 `video.download.format` 完全覆盖  

**关键命令参数**：

| 参数 | 作用 |
|------|------|
| `-f` | 格式 |
| `--merge-output-format mp4` | 合并输出 |
| `--ffmpeg-location` | yt-dlp 找到 ffmpeg |
| `-N` | 分片并发 |
| `--no-playlist` | 只下单个 |
| `-o video.%(ext)s` | 输出模板 |

**音频提取**：优先从 audio 轨；目标格式默认 mp3（`libmp3lame -q:a 2`）。

### 6.2 WhisperClient

- URL：`{baseUrl}/v1/audio/transcriptions`  
- 字段：`file`, `model`, `response_format=verbose_json`, `language`  
- 超时：`video.whisper.timeout-seconds`（默认 600）  

### 6.3 SummarizationService

输出 JSON schema（概念）：

```json
{
  "keyPoints": [{"timestamp": "00:01:23", "point": "..."}],
  "chapters": [{"timestamp": "00:00:00", "title": "...", "summary": "..."}],
  "mindMapMarkdown": "...",
  "repurposeScript": "..."
}
```

- 从模型回复中剥离 ` ```json ` 围栏与多余文本，再 `readTree`  
- 解析失败：保留一条兜底 keyPoint + 原文片段  

### 6.4 LlmChatClient

- 端点：`{baseUrl}/v1/chat/completions`（兼容各种 baseUrl 拼法）  
- Header：`Authorization: Bearer {apiKey}`  
- Body：`model`, `messages`, `temperature`, `max_tokens`  
- 解析 `choices[0].message.content`；若空尝试 `reasoning_content`（部分推理模型）  
- 可重试 HTTP：408/429/500/502/503/504 或 body 含 busy/rate limit 等  

### 6.5 ProcessExecutor

- `ProcessBuilder` 执行命令，合并 stderr  
- 超时 `waitFor` → `destroyForcibly`  
- 非 0 退出码 → `BusinessException`  
- Windows：解析 PATH / 补全 `.exe`，减轻 IDE 与 CMD 环境不一致  

### 6.6 StorageService

| 方法 | 作用 |
|------|------|
| `resolveTaskDir` | `{workDir}/{taskId}` 绝对路径 |
| `ensureTaskDir` | 创建目录 |
| `saveJson` | pretty JSON 写文件 |
| `deleteTaskDir` | 递归删除；校验在 workDir 内 |
| `detectPlatform` | URL 平台识别 |
| `guessMediaType` | 下载接口 Content-Type |

磁盘布局示例：

```text
./data/video/{taskId}/
  video.mp4              # 或 video.f*.mp4 中间产物
  video.f30280.m4a       # 分轨时可能残留
  audio.mp3
  transcription.json
  summary.json
```

---

## 7. 数据模型与持久化

### 7.1 表 `video_task`

| 字段 | 类型 | 含义 |
|------|------|------|
| id | BIGINT | 雪花 ID |
| source_url | VARCHAR(1024) | 源链接 |
| title | VARCHAR(512) | 标题 |
| platform | VARCHAR(32) | 平台 |
| status | VARCHAR(32) | 状态 |
| current_step | VARCHAR(128) | 步骤文案 |
| language | VARCHAR(16) | 语言 |
| llm_provider / llm_model | VARCHAR | 本任务 LLM 快照 |
| extract_mind_map / generate_repurpose_script | TINYINT | 选项 |
| duration_seconds | DOUBLE | 时长 |
| video_path / audio_path | VARCHAR | 本地媒体 |
| transcription_path / summary_path | VARCHAR | JSON 文件路径 |
| transcription_json / summary_json / result_json | LONGTEXT | 内容 |
| error_message | TEXT | 失败原因 |
| started_at / finished_at / created_at / updated_at | DATETIME(3) | 时间 |

### 7.2 表 `ai_model_config`

| 字段 | 含义 |
|------|------|
| provider | 对应 `ai.providers` 的 key |
| model_id | API 模型 ID |
| model_name | 展示名 |
| enabled | 是否出现在任务下拉 |
| sort_order | 排序 |
| UNIQUE(provider, model_id) | 防重复 |

初始化脚本：`doc/ai_model_config.sql`  
建表亦在：`src/main/resources/db/schema.sql`

### 7.3 结果 JSON 结构（result_json）

```json
{
  "videoId": "任务ID",
  "title": "标题",
  "duration": 214.0,
  "sourceUrl": "https://...",
  "summary": {
    "keyPoints": [...],
    "chapters": [...],
    "mindMapMarkdown": "...",
    "repurposeScript": "..."
  },
  "transcription": {
    "text": "全文",
    "language": "zh",
    "durationSeconds": 214.0,
    "segments": [
      {"id": 0, "start": 0.0, "end": 3.2, "text": "..."}
    ]
  }
}
```

---

## 8. REST API 一览

Base path：`/api/v1/video`  
统一包装：`ApiResult{ code, message, success, data, timestamp }`  
成功：`code=0, success=true`（视频文件流接口除外）

### 8.1 任务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/process` | 提交任务 |
| GET | `/tasks/{taskId}` | 详情（含 SUCCESS 的 result） |
| GET | `/status/{taskId}` | 兼容旧路径 |
| GET | `/tasks?page&size` | 分页列表 |
| GET | `/tasks/recent?limit` | 最近列表 |
| GET | `/tasks/{id}/transcription` | 仅转录 |
| GET | `/tasks/{id}/summary` | 仅摘要 |
| GET | `/tasks/{id}/video` | 视频文件流 |
| DELETE | `/tasks/{id}` | 删库 + 删文件 |

**提交请求体示例**：

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

### 8.2 模型

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/models` | 启用模型，按供应商分组 |
| POST | `/models/test` | 连通性测试 |
| GET | `/model-configs` | 管理列表 |
| GET | `/model-configs/providers` | 有 key 的供应商 |
| POST | `/model-configs` | 新增 |
| PUT | `/model-configs/{id}` | 更新 |
| DELETE | `/model-configs/{id}` | 删除 |

**测试请求**：

```json
{ "provider": "nvidia", "model": "deepseek-ai/deepseek-v4-flash" }
```

**测试响应 data**：

```json
{
  "available": true,
  "provider": "nvidia",
  "model": "deepseek-ai/deepseek-v4-flash",
  "reply": "OK",
  "latencyMs": 1200,
  "errorMessage": null
}
```

---

## 9. 配置项说明

### 9.1 `video.*`（`VideoProperties`）

| 配置 | 默认 | 说明 |
|------|------|------|
| work-dir | `./data/video` | 任务文件根目录 |
| yt-dlp-path | `yt-dlp` | 建议绝对路径（IDE PATH 问题） |
| ffmpeg-path | `ffmpeg` | 建议绝对路径 |
| cleanup-media | `false` | 完成后是否删媒体 |
| whisper.base-url | `http://127.0.0.1:8000` | Whisper 服务 |
| whisper.model | `medium` | 请求体模型名 |
| whisper.timeout-seconds | `600` | 转录超时 |
| llm.provider | — | 默认供应商 |
| llm.temperature | `0.3` | 总结温度 |
| llm.max-tokens | `4096` | 总结 max_tokens |
| llm.max-retries | `5` | 业务调用重试 |
| llm.test-timeout-seconds | `10` | **测试**超时 |
| download.max-height | `720` | 下载限高 |
| download.concurrent-fragments | `8` | 分片并发 |
| download.max-duration-seconds | `7200` | 时长上限 |

### 9.2 `ai.providers`

- **只负责**供应商 name / base-url / api-key  
- **models 列表可为空**；运行时模型读 `ai_model_config`  
- 与聊天模块共用 Key  

---

## 10. 状态机与异常

### 10.1 业务异常

- 统一 `BusinessException(code, message)`  
- 全局 `GlobalExceptionHandler` → `ApiResult.fail`  
- 常见 code：400 参数 / 404 不存在 / 500 业务失败  

### 10.2 典型失败点

| 阶段 | 现象 | 可能原因 |
|------|------|----------|
| Download | CreateProcess error=2 | yt-dlp/ffmpeg 路径未配置 |
| Download | 分轨 / 无音轨 | 已处理：识别 m4a + 合并 |
| Transcribe | 连接失败 | Whisper 未启动 |
| Summarize | 503 busy | NVIDIA 繁忙 → 重试 |
| Summarize | 400 DEGRADED | 云端模型降级 |
| Test | timeout 10s | 策略性判不可用 |

### 10.3 日志关键字

```
创建视频任务
任务状态更新
yt-dlp 下载
调用 Whisper 转录
开始 LLM 总结 / 调用 LLM
视频任务完成 / 视频任务失败
测试 LLM 模型
```

---

## 11. 前端协作要点

前端：`okx-trading-web`，路由 `/video-extract`。

推荐交互：

1. `GET /models` 填充下拉  
2. `POST /models/test` 通过后再允许提交（产品规则）  
3. `POST /process` 拿 `taskId`  
4. 每 3s `GET /tasks/{id}` 直到 SUCCESS/FAILED  
5. 播放：`/api/v1/video/tasks/{id}/video`（经 Vite 代理）  
6. 模型管理：`/model-configs*` CRUD  

API 封装：`src/api/video.api.ts`  
页面：`src/views/video-extract/index.vue` + `ModelManageModal.vue`

---

## 12. 依赖与部署

### 12.1 运行依赖

| 组件 | 用途 |
|------|------|
| JDK 17 + Spring Boot | 后端 |
| MySQL | video_task / ai_model_config |
| yt-dlp | 下载 |
| FFmpeg | 合并 / 抽音频 |
| whisper-service (Python) | 转录 |
| LLM API（NVIDIA 等） | 总结 |

### 12.2 Whisper 服务

- 目录：`okx-bot/whisper-service`  
- 默认端口 8000，OpenAI 兼容转录接口  
- 建议 CPU：`small` + `beam_size=1`  

### 12.3 初始化

```sql
-- schema 中含 video_task / ai_model_config
-- 或单独执行：
source doc/ai_model_config.sql;
```

---

## 附录 A：关键类方法索引

| 类 | 方法 | 作用 |
|----|------|------|
| VideoProcessService | submit | 创建任务并异步启动 |
| VideoProcessService | getStatus / listTasks | 查询 |
| VideoProcessService | deleteTask | 删除 |
| VideoProcessService | testLlmModel | 测试转发 |
| VideoProcessingPipeline | run | 三步流水线 |
| VideoDownloadService | download / fetchMeta | 下载 |
| TranscriptionService | transcribe | 转录 |
| SummarizationService | summarize | 总结 |
| AiModelConfigService | listEnabledGroupedByProvider | 下拉数据 |
| AiModelConfigService | create/update/delete | 模型管理 |
| LlmChatClient | chat / testModel | LLM 调用 |
| WhisperClient | transcribe | Whisper HTTP |
| StorageService | * | 路径与文件 |

## 附录 B：与早期文档关系

| 文档 | 关系 |
|------|------|
| `VideoCoreExtractor_开发文档_v2_持久化保存版.md` | 需求与架构规划 |
| `VideoCoreExtractor_后端使用与验证手册.md` | 运维与验收操作 |
| **本文档** | **当前实现的逻辑与代码级流程** |

若实现变更，请优先更新本文档中的状态机、表结构与 API 章节。

---

**文档状态**：与代码库 `com.dwcode.okxbot.video` 当前实现对齐。  
维护建议：合并改流水线或表结构时同步修订第 3、5、7、8 节。
