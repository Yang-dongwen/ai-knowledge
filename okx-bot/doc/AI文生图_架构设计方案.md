# AI 文生图工具 — 架构设计方案

**模块名称**：AI Image Generator（提示词 → 图片）  
**后端包（拟）**：`com.dwcode.okxbot.imggen`  
**后端**：`okx-bot`（Java Spring Boot）  
**前端（拟）**：`okx-trading-web` 路由 `/image-generate`  
**文档版本**：v1.1  
**日期**：2026-07-14  

> **关联文档**  
> - **实现手册（推荐开发先读）**：`AI文生图_后端实现与开发手册.md`  
> - 视频提取：`VideoCoreExtractor_核心逻辑与后端实现文档.md`  
> - 文生视频：`AI视频生成_架构设计方案.md`、`AI视频生成_Phase1_架构设计.md`  
> - aigen 预留配图端口：Phase1 文档 §5.4 `ImagePort`（当前 NoOp）  
> - SQL：`sql/imggen_task.sql`

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [与现有模块的关系](#2-与现有模块的关系)
3. [市面主流产品形态](#3-市面主流产品形态)
4. [总体架构](#4-总体架构)
5. [流水线与状态机](#5-流水线与状态机)
6. [端口与 Provider 适配](#6-端口与-provider-适配)
7. [NVIDIA FLUX + LangChain4j 分层接入](#7-nvidia-flux--langchain4j-分层接入)
8. [数据模型与落盘](#8-数据模型与落盘)
9. [REST API 设计](#9-rest-api-设计)
10. [前端模块设计](#10-前端模块设计)
11. [配置项](#11-配置项)
12. [安全、配额与可观测](#12-安全配额与可观测)
13. [并发与部署](#13-并发与部署)
14. [分阶段落地计划](#14-分阶段落地计划)
15. [验收标准](#15-验收标准)
16. [风险与对策](#16-风险与对策)
17. [附录：三模块对比总表](#17-附录三模块对比总表)

---

## 1. 背景与目标

### 1.1 业务目标

做一个 **「用户输入提示词 → 系统产出图片」** 的工具，典型路径：

```
用户提示词 + 比例/风格/模型/张数
        ↓
（可选）LLM 润色 / 翻译 prompt
        ↓
云端或本地文生图 API
        ↓
落盘 PNG/JPEG + 缩略图
        ↓
画廊预览 / 下载 / 再生成
```

### 1.2 产品能力范围（V1）

| 能力 | 说明 |
|------|------|
| 文本生成图片 | 纯提示词驱动，选模型 + 比例 + 风格 |
| 多图 | 单次 `n` 张（上限可配，建议 ≤4） |
| 任务化异步 | 提交立即返回 `taskId`，SSE 推进度 |
| 历史画廊 | 列表、详情、预览、下载、删除 |
| 多用户隔离 | 复用现有 JWT + `user_id` |
| 可插拔供应商 | OpenAI Images / Flux / SD / 国内聚合等 |

### 1.3 非目标（V1 明确不做）

- 不做本机 GPU 训练 / 微调  
- 不做完整 ControlNet / IP-Adapter 工作流编排（留给 Phase 3 本地 ComfyUI）  
- 不做实时流式出图预览（以任务完成后整图为准）  
- 不与 `video_task` / `aigen_task` 共用任务表  
- 不把文生图逻辑塞进 Remotion 渲染进程  

### 1.4 设计原则

| 原则 | 落地方式 |
|------|----------|
| 复用优先 | 复用 JWT、`ai.providers` / `ai_model_config`、SSE、Storage 模式 |
| 异步解耦 | 提交即返回；生图等待放线程池，不阻塞交易 Job |
| 端口隔离 | 业务只依赖 `ImageGenPort` 等接口，厂商差异进 Adapter |
| 独立模块 | 独立包、独立表、独立线程池、独立前端页 |
| 可观测 | 状态机 + 分步耗时 + `provider_request_id` + 失败信息 + 中间产物落盘 |
| 可扩展 | 新供应商 = 新 Adapter；img2img / 超分 = 新步骤或新 Port 实现 |
| 反哺 aigen | 同一套 Image Provider 可被 aigen `AssetStep` 调用，任务实体仍分离 |

---

## 2. 与现有模块的关系

### 2.1 仓库现状（两条已有流水线）

#### 视频提取（`com.dwcode.okxbot.video`）

**方向：已有视频 → 结构化文本**

```
URL → 下载(yt-dlp) → 抽音频(FFmpeg) → ASR(Whisper) → LLM 总结 → JSON/落盘
```

| 项 | 现状 |
|----|------|
| 输入 | 平台视频链接 |
| 核心算力 | IO + ASR + LLM 文本 |
| 产出 | 字幕、要点、章节、二创文案 |
| 状态机 | `PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING → SUCCESS/FAILED` |
| 表 | `video_task` |
| 前端 | `/video-extract` |

本质是 **分析型流水线**，从已有视频抽信息，不创造新画面。

#### AI 文生视频（`com.dwcode.okxbot.aigen`）

**方向：提示词 → 新合成 MP4**

```
Prompt → LLM 写 Storyboard JSON → TTS 配音 → Remotion 模板渲染 → output.mp4
```

| 项 | 现状 |
|----|------|
| 输入 | prompt + template + 时长/比例/音色 |
| 核心算力 | LLM 结构化规划 + TTS + Chromium 渲染 |
| 产出 | 分镜 JSON + 音频 + 成片 MP4 |
| 状态机 | `PENDING → PLANNING → ASSET_GENERATING → RENDERING → SUCCESS/...` |
| 表 | `aigen_task` |
| 前端 | `/video-generate` |
| 图像 | 文档预留 `ImagePort`，Phase1 **未接真文生图**（模板渐变/占位背景） |

本质是 **编排型生成**：LLM 只写剧本，画面由 **预置 Remotion Composition** 程序化绘制，不是扩散模型逐帧生成视频。

### 2.2 复用 vs 新建

```
                    ┌──────────────────────────────────────┐
                    │           可复用（共享能力）            │
                    │  JWT 鉴权 / ApiResult / user 隔离      │
                    │  ai.providers + ai_model_config       │
                    │  SSE 推送模式 / ProcessExecutor 风格    │
                    │  本地 work-dir 按 taskId 分目录        │
                    └──────────────────────────────────────┘
                                      │
          ┌───────────────────────────┼───────────────────────────┐
          ▼                           ▼                           ▼
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ video（已有）      │     │ aigen（已有）      │     │ imggen（本方案）   │
│ 输入：URL          │     │ 输入：prompt+模板  │     │ 输入：prompt+尺寸  │
│ DL→ASR→LLM        │     │ Plan→TTS→Render   │     │ Enhance?→Generate │
│ 表：video_task     │     │ 表：aigen_task     │     │ 表：imggen_task    │
│ /video-extract     │     │ /video-generate    │     │ /image-generate    │
└──────────────────┘     └──────────────────┘     └──────────────────┘
```

**为什么独立包 / 独立表？**

1. 输入输出、状态机、中间产物完全不同，硬塞一张表会字段爆炸。  
2. 资源特征不同：提取偏 IO/ASR，aigen 偏 LLM + 本地渲染，文生图偏 **外网 GPU API 等待**。  
3. 权限与配额后续可按「张 / 分辨率」分开计费。  
4. 代码演进互不拖累；aigen 需要配图时 **调用同一 Provider 实现**，而非共用任务实体。

### 2.3 与 aigen `ImagePort` 的关系

| 层级 | 职责 |
|------|------|
| **imggen 模块** | 产品能力：用户直接出图、历史画廊、配额 |
| **ImageGenPort / Adapter** | 可复用的「调生图 API」实现 |
| **aigen AssetStep** | Phase2 起可选调用同一 Port，为分镜写 `assets/images/*` |

原则：**共享 Provider，分离任务表与前端入口**。

### 2.4 概念上最容易混的三点

1. **aigen 不是「真·视频扩散模型」**  
   当前是「LLM 剧本 + TTS + Remotion 模板动画」。Sora / 可灵一类是另一条技术路线。  
2. **video 与 imggen 方向相反**  
   一个吃视频吐文本，一个吃文本吐图；共享工程骨架，不共享业务步骤。  
3. **imggen 可反哺 aigen，但不等于 aigen 的子集**  
   独立产品解决「只要图」；嵌入 aigen 解决「分镜配图」；不要用 `aigen_task` 存纯出图任务。

---

## 3. 市面主流产品形态

主流产品（DALL·E / Flux / SD / Midjourney / 通义万相 / 即梦等）共性：

| 能力层 | 主流做法 |
|--------|----------|
| 入口 | 文本 prompt；可选参考图（img2img）、蒙版（inpaint） |
| 参数 | 尺寸/比例、风格、负向提示词、seed、张数 n、质量档位 |
| 调用方式 | 云 API 同步/异步；长耗时用 taskId 轮询或 webhook |
| Prompt 工程 | 可选 LLM 润色/扩写（尤其中文 → 英文模型） |
| 安全 | 输入审核 + 输出审核；违规直接拒 |
| 后处理 | 超分、去背景、变体、局部重绘 |
| 产品形态 | 画廊历史、收藏、同款再生成、批量、成本计费 |

与本项目最接近的工程套路：**异步任务 + 可插拔 Provider + 落盘 + SSE 进度**——与 `video` / `aigen` 一致，但流水线更短。

---

## 4. 总体架构

### 4.1 逻辑架构图

```
┌─────────────────────────────────────────────────────────────┐
│  前端 okx-trading-web  /image-generate                       │
│  · prompt / 比例 / 风格 / 张数 · 历史画廊 · 预览下载 · SSE    │
└───────────────────────────────┬─────────────────────────────┘
                                │ HTTPS + JWT
                                │ REST /api/v1/imggen/*
                                │ SSE  /api/v1/imggen/events
                                ▼
┌─────────────────────────────────────────────────────────────┐
│  Spring Boot  okx-bot  package: com.dwcode.okxbot.imggen     │
│                                                              │
│  Controller → ImgGenTaskService → Scheduler → Pipeline       │
│       │                              │                       │
│       │         ┌────────────────────┼──────────────────┐    │
│       │         ▼                    ▼                  ▼    │
│       │  PromptEnhanceStep     ImageGenerateStep   PostStep  │
│       │  (可选 LLM 润色)       (ImageGenPort)      (可选超分) │
│       │         │                    │                  │    │
│       └─────────┴────────────────────┴──────────────────┘    │
│              StorageService / EventPublisher / ModelConfig   │
└───────┬──────────────────┬───────────────────┬──────────────┘
        ▼                  ▼                   ▼
     MySQL            data/imggen/{id}/     云端/本地生图 API
     imggen_task      prompt / outputs/*    OpenAI Images / Flux /
     （复用 ai_model_config）                 SD / 国内万相等
```

### 4.2 进程边界

| 进程 | 技术 | 职责 | 资源特征 |
|------|------|------|----------|
| **okx-bot** | Java 17 / Spring Boot | 鉴权、任务编排、可选 prompt 润色、落盘、API | 内存中等、IO 中等 |
| **Image Provider** | 云 API 或本地 ComfyUI | 实际出图 | 网络等待或 GPU |
| **MySQL** | 8.x | 任务元数据 | 常规 |
| **对象存储**（后期） | MinIO / OSS / S3 | 成品与素材 | 容量型 |

V1 单机即可：Java 调云 API + 本地 `data/imggen` 落盘。**不依赖** Remotion、Whisper、FFmpeg。

### 4.3 包结构草案

```
com.dwcode.okxbot.imggen
├── controller/
│   └── ImgGenTaskController.java
├── service/
│   ├── ImgGenTaskService.java
│   └── ImgGenStorageService.java
├── agent/
│   ├── ImgGenPipeline.java
│   ├── ImgGenTaskScheduler.java
│   ├── ImgGenTaskAsyncRunner.java
│   └── step/
│       ├── PipelineContext.java
│       ├── PipelineStep.java
│       ├── PromptEnhanceStep.java
│       ├── GenerateStep.java
│       └── PostProcessStep.java
├── port/
│   ├── ImageGenPort.java
│   ├── ImageGenCommand.java
│   ├── ImageGenResult.java
│   ├── PromptEnhancePort.java
│   └── ImagePostPort.java
├── adapter/
│   ├── openai/OpenAiImagesAdapter.java
│   ├── flux/FluxAdapter.java          # 可选
│   ├── mock/MockImageGenAdapter.java
│   └── llm/LlmPromptEnhanceAdapter.java  # 可复用 LlmChatClient
├── domain/ dto/ entity/ enums/ event/ mapper/
└── config/
    ├── ImgGenProperties.java
    ├── ImgGenAsyncConfig.java
    └── ImgGenBeanConfig.java
```

前端：

```
okx-trading-web/src/
  api/imggen.api.ts
  api/imggen.events.ts
  views/image-generate/index.vue
  router: /image-generate   # meta.group = tools，与 extract/generate 并列
```

---

## 5. 流水线与状态机

### 5.1 主路径时序

```
用户                  前端                 okx-bot                    Image Provider
 │                     │                      │                          │
 │  填 prompt/参数     │                      │                          │
 │────────────────────►│  POST /imggen/tasks  │                          │
 │                     │─────────────────────►│ insert PENDING           │
 │                     │◄──── taskId ─────────│ notify scheduler         │
 │                     │  SSE 订阅            │                          │
 │                     │◄═════════════════════│ task.created             │
 │                     │                      │ PROMPT_ENHANCING (opt)   │
 │                     │◄═════════════════════│                          │
 │                     │                      │ GENERATING ─────────────►│
 │                     │◄═════════════════════│  (poll if async) ◄───────│
 │                     │                      │ save png + thumbs        │
 │                     │◄═════════════════════│ SUCCESS + preview urls   │
 │  预览/下载          │  GET media           │                          │
 │◄────────────────────│◄─────────────────────│                          │
```

单张常见 **5～30s**，比 aigen 整片（几十秒～数分钟）短，但仍建议异步，避免 HTTP 超时。

### 5.2 状态机

```
                    ┌─────────────┐
         提交        │   PENDING   │
          ─────────►│  排队中      │
                    └──────┬──────┘
                           │
                           ▼
                    ┌─────────────────┐
                    │PROMPT_ENHANCING │  可选；配置关闭则跳过
                    └──────┬──────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │ GENERATING  │  核心：调生图 API
                    └──────┬──────┘
                           │
                           ▼
                    ┌─────────────────┐
                    │ POST_PROCESSING │  可选：超分/缩略图/格式
                    └──────┬──────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
       ┌─────────────┐           ┌─────────────┐
       │   SUCCESS   │           │   FAILED    │
       └─────────────┘           └─────────────┘
              │
              │ 用户取消（非终态前）
              ▼
       ┌─────────────┐
       │  CANCELLED  │
       └─────────────┘
```

状态枚举建议：`ImgGenTaskStatus`

- `PENDING` / `PROMPT_ENHANCING` / `GENERATING` / `POST_PROCESSING`  
- `SUCCESS` / `FAILED` / `CANCELLED`  
- 终态：`isTerminal()` = SUCCESS | FAILED | CANCELLED  
- 运行中：`isRunning()` = PROMPT_ENHANCING | GENERATING | POST_PROCESSING  

### 5.3 步骤明细

| 步骤 | 状态 | 输入 | 输出 | 失败策略 |
|------|------|------|------|----------|
| 1. 校验与入库 | `PENDING` | prompt, options | task 行 | 同步拒绝（参数非法、无 key、超额） |
| 2. Prompt 润色 | `PROMPT_ENHANCING` | 原始 prompt | `enhanced_prompt` | 可关；失败可降级用原文继续（可配） |
| 3. 生图 | `GENERATING` | 最终 prompt + 参数 | `outputs/*.png` | 超时/审核/额度映射明确错误；可 retry |
| 4. 后处理 | `POST_PROCESSING` | 原图 | thumb / 可选超分 | V1 可仅做缩略图；失败不挡主图 SUCCESS（可配） |
| 5. 收尾 | `SUCCESS` | — | result_json、耗时 | — |

### 5.4 重试策略

| 场景 | 策略 |
|------|------|
| Provider 429/503 | Adapter 内有限次退避重试 |
| Provider 内容审核拒绝 | 直接 FAILED，不盲目重试 |
| 用户 retry | 清空 `outputs/` 后从 PENDING 再跑，或创建新 task（保留历史） |
| 同款再生成 | 复制 prompt/参数，新 taskId（seed 可沿用或置空） |

### 5.5 Pipeline 伪代码

```text
ctx = PipelineContext.load(taskId)
for step in steps:   # PromptEnhanceStep? → GenerateStep → PostProcessStep?
  if cancelled: mark CANCELLED; return
  updateStatus(step.status, step.label, progress)
  try:
    step.execute(ctx)
    persist(ctx)
    publish SSE
  catch:
    FAILED + message; return
SUCCESS
```

与 aigen 一致：`PipelineStep` 接口 + 专用线程池，不在 Pipeline 里堆 if-else。

---

## 6. 端口与 Provider 适配

### 6.1 ImageGenPort（核心）

```java
public interface ImageGenPort {
    ImageGenResult generate(ImageGenCommand cmd);
}
```

```java
public class ImageGenCommand {
    String taskId;
    String prompt;
    String negativePrompt;
    String model;          // dall-e-3 / flux-pro / sdxl ...
    String size;           // 1024x1024 或厂商枚举
    String aspectRatio;    // 1:1 / 16:9 / 9:16
    String style;          // vivid / natural / anime ...
    Integer n;             // 张数 1~4
    Long seed;             // 可选，可复现
    Path workDir;
    // img2img 扩展（V1.1+）：
    Path referenceImage;
    Double strength;
}
```

```java
public class ImageGenResult {
    List<ImageAsset> images; // relativePath, width, height, seed, revisedPrompt
    long providerLatencyMs;
    String providerRequestId;
    String providerRawMetaJson; // 计费/调试，落盘时注意脱敏
}
```

### 6.2 PromptEnhancePort（可选）

```java
public interface PromptEnhancePort {
    EnhancedPrompt enhance(EnhanceCommand cmd);
}
```

实现：复用现有 `LlmChatClient` / 与 aigen 规划相同的 chat 供应商配置。  
职责仅限 **扩写、翻译、风格化**，不负责出图。

### 6.3 ImagePostPort（可选）

缩略图（V1 推荐必做，可本地 ImageIO）、超分、格式转换。V1 可只做 thumb，接口先留。

### 6.4 Provider 适配树

```
ImageGenPort
  ├─ OpenAiImagesAdapter      // DALL·E 3 / gpt-image
  ├─ FluxAdapter              // Black Forest 或中转
  ├─ StabilitySdAdapter       // SD3 / SDXL
  ├─ SiliconFlowAdapter       // 国内聚合（按供应商习惯）
  ├─ LocalComfyUiAdapter      // 可选：本机 ComfyUI / SD WebUI
  └─ MockImageGenAdapter      // 单测 / 无 key：写渐变假图
```

### 6.5 统一约定（强制）

1. 业务层只认 Port，禁止 Pipeline 直接拼各家 HTTP。  
2. 厂商差异（尺寸枚举、同步/异步、是否支持 negative/seed）在 Adapter 内消化。  
3. **不支持的参数**：创建任务时同步拒绝，或文档明确降级规则；禁止静默忽略导致「同参不同结果」。  
4. 输出一律落到 `data/imggen/{taskId}/outputs/`，对外只暴露带鉴权的 media API。  
5. 异步类 API：Adapter 内部 submit + poll，对 Pipeline 暴露同步 `generate()`（内部可响应 cancel）。  

### 6.6 端到端数据流（主路径）

```
POST /tasks
  → insert imggen_task (PENDING)
  → @Async ImgGenPipeline.run(taskId)
  → PROMPT_ENHANCING?: LLM 润色 → enhanced_prompt
  → GENERATING: ImageGenPort.generate → outputs/*
  → POST_PROCESSING?: thumbs
  → SUCCESS / FAILED
GET /tasks/{id} 或 SSE 查看结果
```

---

## 7. NVIDIA FLUX + LangChain4j 分层接入

### 7.1 结论

| 能力 | 实现 | 说明 |
|------|------|------|
| **真出图** | NVIDIA Build **FLUX**（默认 `flux.1-schnell`） | 云端 GenAI，复用 `ai.providers.nvidia` 的 API Key |
| **Prompt 润色** | `PromptEnhancePort` → 当前 `LlmChatClient`；可换 **LangChain4j ChatModel** | 文本侧，OpenAI 兼容 chat |
| **任务编排/SSE/落盘** | Spring 自管 | **不用** LangChain4j |
| **FLUX HTTP** | `NvidiaFluxImageAdapter` | **不要**硬套 `OpenAiImageModel`（协议不同） |

### 7.2 为什么不能把 FLUX 当 Chat 调

| 用途 | URL |
|------|-----|
| 现有 Chat（总结/分镜/润色） | `https://integrate.api.nvidia.com/v1` + `/chat/completions` |
| **FLUX 文生图** | `https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell` |

请求体示例：

```json
{
  "prompt": "cyberpunk Tokyo rainy street neon",
  "width": 1024,
  "height": 1024,
  "seed": 0,
  "steps": 4
}
```

支持分辨率（Adapter 内做 aspectRatio 映射）：1024×1024、768×1344、1344×768、896×1152、1152×896、832×1216、1216×832。

### 7.3 LangChain4j 使用边界

```
ImgGenPipeline
  ├─ PromptEnhancePort
  │     └─ LlmChatClient（现状，与 video/aigen 一致）
  │     └─ 可选：LangChain4j ChatModel / AiServices（结构化润色升级）
  └─ ImageGenPort
        ├─ NvidiaFluxImageAdapter   ← 专用 HTTP，非 LC4j ImageModel
        ├─ MockImageGenAdapter
        └─ （可选）OpenAiImages → 才适合 LC4j OpenAiImageModel
```

原则与 aigen 一致：**业务只依赖 Port**；`dev.langchain4j.*` 不得泄漏到 Controller。

### 7.4 默认模型与配置键

- 默认生图：`black-forest-labs/flux.1-schnell`（配置 `imggen.flux.model-path`）
- 密钥：`ai.providers.nvidia.api-key`
- `mock-pipeline=true` 或 `steps.generate=mock` 时写本地假图，不调 NVIDIA

---

## 8. 数据模型与落盘

### 8.1 表 `imggen_task`（建议 SQL）

```sql
-- AI 文生图任务表
-- 可在已有库上单独执行
USE okx_bot;

CREATE TABLE IF NOT EXISTS imggen_task (
    id                    BIGINT         NOT NULL COMMENT '主键 snowflake',
    user_id               BIGINT         NOT NULL COMMENT '所属用户',
    title                 VARCHAR(256)            COMMENT '标题（可截取 prompt 前 N 字）',
    prompt                TEXT           NOT NULL COMMENT '用户原始提示词',
    enhanced_prompt       TEXT                    COMMENT '润色后提示词',
    negative_prompt       VARCHAR(1024)           COMMENT '负向提示词',
    status                VARCHAR(32)    NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    current_step          VARCHAR(128)            COMMENT '当前步骤说明',
    progress              INT            NOT NULL DEFAULT 0 COMMENT '0-100',

    provider              VARCHAR(64)             COMMENT '生图供应商',
    model                 VARCHAR(128)            COMMENT '生图模型',
    aspect_ratio          VARCHAR(16)             COMMENT '1:1 / 16:9 / 9:16',
    size                  VARCHAR(32)             COMMENT '如 1024x1024',
    style                 VARCHAR(64)             COMMENT '风格档位',
    n                     INT            DEFAULT 1 COMMENT '张数',
    seed                  BIGINT         NULL     COMMENT '随机种子',
    enhance_enabled       TINYINT        DEFAULT 1 COMMENT '是否启用 prompt 润色',

    result_json           LONGTEXT                COMMENT '多图元数据 JSON',
    work_dir              VARCHAR(1024),
    cover_path            VARCHAR(1024)           COMMENT '封面/首图相对路径',
    error_message         TEXT,

    provider_request_id   VARCHAR(128)            COMMENT '供应商请求 ID',
    estimated_cost        DECIMAL(12,6)           COMMENT '估算费用',
    enhance_duration_ms   BIGINT,
    generate_duration_ms  BIGINT,
    post_duration_ms      BIGINT,
    total_duration_ms     BIGINT,

    started_at            DATETIME(3),
    finished_at           DATETIME(3),
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,

    PRIMARY KEY (id),
    INDEX idx_imggen_user_created (user_id, created_at),
    INDEX idx_imggen_status (status),
    INDEX idx_imggen_provider_model (provider, model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文生图任务';
```

实现落地时可将本 SQL 同步到 `okx-bot/doc/sql/imggen_task.sql`。

### 8.2 `result_json` 示例

```json
{
  "images": [
    {
      "index": 1,
      "path": "outputs/img-01.png",
      "thumbPath": "outputs/thumb-01.jpg",
      "width": 1024,
      "height": 1024,
      "seed": 42,
      "revisedPrompt": "..."
    }
  ],
  "provider": "openai",
  "model": "dall-e-3"
}
```

### 8.3 落盘结构

```
data/imggen/{taskId}/
  request.txt                 # 原始参数快照
  prompt.enhanced.txt         # 可选
  outputs/
    img-01.png
    img-02.png
    thumb-01.jpg
  provider/
    raw-response.json         # 排障用（注意脱敏，勿存完整 api-key）
```

### 8.4 Storage 安全

对齐 `AigenStorageService` / `video.StorageService`：

- 所有读写限制在 `{work-dir}/{taskId}/` 沙箱内  
- 禁止 `../` 与绝对路径穿越  
- 删除任务：库记录 + 目录双清  

---

## 9. REST API 设计

前缀建议：`/api/v1/imggen`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tasks` | 创建任务，立即返回 `taskId` |
| GET | `/tasks` | 分页历史（画廊） |
| GET | `/tasks/{id}` | 详情 + 结果 |
| POST | `/tasks/{id}/retry` | 失败重跑 / 同参再生成 |
| POST | `/tasks/{id}/cancel` | 取消（排队/生成中） |
| DELETE | `/tasks/{id}` | 删库 + 删盘 |
| GET | `/tasks/{id}/media/**` | 鉴权读图（校验 user 归属） |
| GET | `/events` | SSE 进度 |
| GET | `/models` | 可用生图模型（capability=image） |

### 9.1 创建请求示例

```json
{
  "prompt": "赛博朋克风格的东京夜景，霓虹倒映在雨后街道",
  "negativePrompt": "blurry, low quality, text, watermark",
  "options": {
    "provider": "openai",
    "model": "dall-e-3",
    "aspectRatio": "16:9",
    "n": 1,
    "style": "vivid",
    "enhancePrompt": true,
    "seed": null
  }
}
```

### 9.2 创建响应示例

```json
{
  "code": 0,
  "data": {
    "id": "2076...",
    "status": "PENDING",
    "progress": 0
  }
}
```

### 9.3 SSE 事件（对齐 video/aigen）

建议事件类型：`task.created` / `task.status` / `task.progress` / `task.success` / `task.failed` / `task.cancelled`。  
前端策略：**SSE 优先，断线回退轮询 `GET /tasks/{id}`**。

---

## 10. 前端模块设计

### 10.1 路由与入口

| 路由 | 名称 | 分组 |
|------|------|------|
| `/image-generate` | AI 文生图 | tools（与 video-extract / video-generate 并列） |

### 10.2 UI 规范

**复用** `video-generate/aigen-ui.scss` 与任务列表 + 详情双栏布局（与视频提取/视频生成同一套工具台视觉）。

### 10.3 页面分区

| 区域 | 功能 |
|------|------|
| 创作台 | prompt、负向词、比例、张数、seed、是否润色 |
| 进度 | SSE + 断线轮询 |
| 结果区 | 图片网格预览、下载、「再生成」 |
| 历史 | 左侧任务列表 |

---

## 11. 配置项

```yaml
imggen:
  enabled: true
  mock-pipeline: false
  work-dir: ./data/imggen
  max-concurrent-tasks: 2
  cleanup-on-delete: true
  max-n: 4
  steps:
    enhance: off    # off | real | mock
    generate: real  # real | mock
  prompt-enhance:
    fallback-on-error: true
    provider:       # 空则 ai.default-provider
    model: ""
  flux:
    provider-key: nvidia
    invoke-url: https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell
    model-path: black-forest-labs/flux.1-schnell
    default-steps: 4
    timeout-seconds: 120
```

### 11.1 模型说明

V1 生图模型固定为配置中的 FLUX 路径，不强制走 `ai_model_config`（该表当前为 chat 模型）。润色 LLM 复用 chat 供应商与库表模型。

---

## 12. 安全、配额与可观测

| 点 | 做法 |
|----|------|
| 鉴权 | 任务带 `user_id`；media 接口校验归属 |
| 内容安全 | 创建前本地关键词；Provider moderation 错误映射友好文案 |
| 密钥 | 仅 yml / 环境变量，不进库、不回传前端 |
| 路径安全 | task 目录沙箱，禁 `../` |
| 成本 | 记 `provider_request_id` + 估算费用；超额同步拒绝创建 |
| 可观测 | 分步耗时、失败码、raw response 落盘（脱敏） |
| 线程池 | `imggenTaskExecutor`，与 `videoTaskExecutor` / aigen 池隔离 |
| 幂等 | 用户 retry 清 outputs 或新 taskId；Provider 支持则带幂等 key 防重复扣费 |

---

## 13. 并发与部署

### 13.1 线程池

| 池 | 用途 |
|----|------|
| `videoTaskExecutor` | 已有：提取 |
| aigen 任务池 | 已有：视频生成 |
| **`imggenTaskExecutor`** | **新建：文生图** |

队列满时：创建任务可返回「系统繁忙」或进入 PENDING 排队（与现有模块策略对齐）。

### 13.2 部署拓扑（V1）

```
[浏览器] → [okx-trading-web]
                ↓
           [okx-bot]
           /    |    \
      MySQL   本地盘   云端 Image API
```

无需额外部署 Remotion / Whisper。若 Phase3 接 ComfyUI，再增加 GPU 节点。

---

## 14. 分阶段落地计划

| 阶段 | 目标 | 交付 |
|------|------|------|
| **Phase 0** | 骨架可跑 | 表 + API + SSE + MockImageGen（渐变假图）+ 前端画廊壳 |
| **Phase 1** | 真能出图 | 接 1 家云 API + 落盘 + 鉴权下载 |
| **Phase 1.1** | 体验 | LLM prompt 润色开关、多图 n、缩略图、再生成 |
| **Phase 2** | 扩展 | img2img、变体、超分；aigen `AssetStep` 接同一 Image Port |
| **Phase 3** | 增强 | 本地 ComfyUI、风格预设库、批量、计费看板 |

**推荐 Phase 1 供应商顺序**：优先使用仓库已有 `ai.providers` 可复用密钥的中转/OpenAI 兼容 Images API，降低新账号与配置成本。

### 14.1 实现顺序建议

1. 定 Provider（哪家 API、密钥是否已有中转）  
2. Phase 0 骨架（表 + 任务 API + Mock）  
3. 接真 Provider  
4. 前端 `/image-generate`  
5. （可选）给 aigen `AssetStep` 接同一 Port 做分镜配图  

---

## 15. 验收标准

### Phase 0

- [ ] 可创建任务并收到 `taskId`  
- [ ] Mock 流水线跑通至 SUCCESS，磁盘有假图  
- [ ] SSE 或轮询可见状态变化  
- [ ] 列表/详情/删除可用  

### Phase 1

- [ ] 登录用户提交中文 prompt，约 30s 内拿到 ≥1 张可预览真图  
- [ ] 失败有明确 `errorMessage`（鉴权 / 审核 / 额度 / 超时可区分）  
- [ ] 历史可回看；删除后库盘双清  
- [ ] `mock-pipeline` / 真 Provider 可配置切换  
- [ ] 用户隔离：A 用户不可读 B 用户 media  

### Phase 1.1

- [ ] 润色开关生效；关闭时直接用原文  
- [ ] `n>1` 多图网格展示  
- [ ] 「再生成」产生新任务且参数可继承  

---

## 16. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 供应商尺寸/参数枚举不一致 | 创建失败或静默降级 | Adapter 映射表 + 创建时校验 |
| 审核误杀 / 政策差异 | 用户体验差 | 友好错误文案 + 提示改写 prompt |
| 费用失控 | 账单暴涨 | 日配额、并发上限、`n` 上限、可观测成本字段 |
| 大图占盘 | 磁盘满 | keep-days 清理、缩略图、后期上对象存储 |
| 与 aigen 配图重复实现 | 双份维护 | 强制共享 Port/Adapter，禁止复制粘贴 HTTP 客户端 |
| 同步 HTTP 超时 | 网关 504 | 全程异步任务 + 前端轮询/SSE |

---

## 17. 附录：三模块对比总表

| 维度 | 视频提取 `video` | 文生视频 `aigen` | **文生图 `imggen`（本方案）** |
|------|------------------|------------------|------------------------------|
| 业务方向 | 视频 → 文本 | 文本 → 视频 | **文本 → 图片** |
| 主输入 | URL | prompt + 模板 | **prompt + 尺寸/风格** |
| 主输出 | summary JSON + 原视频 | MP4 + storyboard | **PNG/JPEG 多图** |
| 流水线 | DL → ASR → Summarize | Plan → Asset(TTS) → Render | **Enhance? → Generate → Post?** |
| LLM 角色 | 总结转录 | 写分镜 JSON | **可选 prompt 润色** |
| 是否依赖 Remotion | 否 | **是（核心）** | **否** |
| 是否依赖 Whisper/FFmpeg | **是** | 否（TTS 另算） | **否** |
| 是否依赖图像生成 API | 否 | 文档预留、代码未接 | **是（核心）** |
| 中间契约 | transcription/summary JSON | **Storyboard JSON** | **prompt + ImageGenCommand** |
| 典型耗时 | 分钟级（下载/ASR） | 分钟级（渲染） | **秒～几十秒** |
| 产品交互 | 中 | **高**（模板/分镜） | **低**（画廊） |
| 失败重试点 | 下载/ASR/总结 | 规划/TTS/渲染 | **生图 API** |
| 与交易模块关系 | 共用鉴权/模型配置 | 同左 | 同左 |
| 任务表 | `video_task` | `aigen_task` | **`imggen_task`（新建）** |
| 前端路由 | `/video-extract` | `/video-generate` | **`/image-generate`** |
| 线程池 | `videoTaskExecutor` | aigen 池 | **`imggenTaskExecutor`** |

### 17.1 一句话对照

| 模块 | 一句话 |
|------|--------|
| 视频提取 | 用户丢链接，系统帮你「听懂 + 总结」视频。 |
| 文生视频 | 用户丢提示词，系统写分镜、配音、用模板「渲」成片。 |
| **文生图** | **用户丢提示词，系统调图像模型直接「画」图。** |

---

## 变更记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-14 | 初稿：对齐市面主流与仓库 video/aigen 架构，给出可落地方案 |
| v1.1 | 2026-07-14 | 定稿 NVIDIA FLUX 默认 Provider + LangChain4j 分层边界；进入实现 |
