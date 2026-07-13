# AI 视频生成工具 — 架构设计方案

**模块名称**：AI Video Generator（提示词 → 视频）  
**后端**：`okx-bot`（Java Spring Boot 3.2 + LangChain4j 用于 aigen 规划层）  
**前端**：`okx-trading-web`（Vue 3）  
**渲染引擎**：Remotion 4（**独立 Node 工程，实现阶段新建**；旧 demo `remotion-video` 已删除）  
**文档版本**：v1.2  
**日期**：2026-07-13  
**变更**：v1.1 补充 LangChain4j；v1.2 删除无效 Remotion demo，改为实现期新建渲染工程  

> **极简说明（推荐先读）**：同目录 `AI视频生成_极简说明.md`（一页纸主链路，不含本长文细节）。

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [与现有模块的关系](#2-与现有模块的关系)
3. [总体架构](#3-总体架构)
4. [核心设计决策](#4-核心设计决策)
5. [端到端流水线](#5-端到端流水线)
6. [LLM 层：LangChain4j 集成设计](#6-llm-层langchain4j-集成设计)
7. [后端包结构与职责](#7-后端包结构与职责)
8. [Remotion 渲染服务](#8-remotion-渲染服务)
9. [数据模型与持久化](#9-数据模型与持久化)
10. [状态机](#10-状态机)
11. [REST API 设计](#11-rest-api-设计)
12. [前端模块设计](#12-前端模块设计)
13. [配置项](#13-配置项)
14. [并发、队列与可扩展性](#14-并发队列与可扩展性)
15. [安全与合规](#15-安全与合规)
16. [部署拓扑](#16-部署拓扑)
17. [分阶段落地计划](#17-分阶段落地计划)
18. [风险与对策](#18-风险与对策)
19. [验收标准](#19-验收标准)

---

## 1. 背景与目标

### 1.1 业务目标

做一个 **「用户输入提示词 → 系统自动产出成片」** 的工具，典型路径：

```
用户提示词 + 风格/模板/时长
        ↓
AI 规划分镜脚本（结构化 JSON）
        ↓
素材生成（配音 TTS / 配图 / 字幕）
        ↓
Remotion 程序化渲染 MP4
        ↓
在线预览 / 下载
```

### 1.2 产品能力范围（V1）

| 能力 | 说明 |
|------|------|
| 文本生成视频 | 纯提示词驱动，选模板 + 风格 |
| 多模板 | 口播解说 / 产品卖点 / 知识卡片 / 科技宣传 等 |
| 分镜可编辑 | 生成后允许用户改文案、调时长，再重渲 |
| 任务化异步 | 提交立即返回 `taskId`，SSE 推进度 |
| 历史管理 | 列表、详情、预览、下载、删除 |
| 多用户隔离 | 复用现有 JWT + `user_id` |

### 1.3 非目标（V1 明确不做）

- 不做实时流式预览每一帧（Remotion Studio 仅开发侧使用）
- 不做「LLM 随意写任意 TSX 并在生产直接 eval」的主路径（见 §4）
- 不做完整非线性剪辑器（CapCut 级）
- 不做 GPU 扩散模型本机训练
- 不与 VideoCoreExtractor（链接提取总结）混为同一任务表

### 1.4 设计原则

| 原则 | 落地方式 |
|------|----------|
| 复用优先 | 复用 `ai.providers` / `ai_model_config`、SSE、鉴权、Storage 模式 |
| LLM 结构化 | **aigen 规划层采用 LangChain4j**（AiServices + Structured Output）；提取模块可继续 `LlmChatClient` |
| 异步解耦 | 提交即返回；CPU/GPU 重步骤独立服务 |
| 结构化中间态 | 全程以 **Storyboard JSON** 为契约，禁止黑盒大段自由文本驱动渲染 |
| 模板安全 | 渲染只走**预置 Composition + props**，不执行不可信代码 |
| 可观测 | 状态机 + 分步耗时 + 错误信息 + 中间产物落盘 |
| 可扩展 | 新模板 = 新 Composition + Schema，不改流水线骨架 |
| 资源隔离 | 生成任务线程池 / 渲染服务与交易 Job、提取任务隔离 |

---

## 2. 与现有模块的关系

### 2.1 仓库现状

| 组件 | 路径 | 角色 |
|------|------|------|
| 后端 | `okx-bot` | Spring Boot：交易 + 视频提取 +（本方案）视频生成 |
| 前端 | `okx-trading-web` | Vue3：已有 `/video-extract`，新增 `/video-generate` |
| Remotion 工程 | 实现阶段新建（建议 `aigen-remotion/`） | **独立 React+Node 渲染工程**；勿塞进 Vue 前端。仓库内旧 `remotion-video` demo 已删除 |
| Whisper | `okx-bot/whisper-service` | **提取模块**用；生成模块可选（口播对齐校验），非主依赖 |
| 视频提取 | `com.dwcode.okxbot.video` | 链接 → 下载 → 转录 → 总结；**不与生成共用任务表** |

### 2.2 复用 vs 新建

```
                    ┌──────────────────────────────────────┐
                    │           可复用（共享能力）            │
                    │  JWT 鉴权 / ApiResult / user 隔离      │
                    │  ai.providers + ai_model_config       │
                    │  SSE 推送模式 / ProcessExecutor        │
                    │  本地 work-dir 按 taskId 分目录        │
                    └──────────────────────────────────────┘
                                      │
              ┌───────────────────────┴───────────────────────┐
              ▼                                               ▼
┌─────────────────────────────┐               ┌─────────────────────────────┐
│ video（已有：提取）           │               │ aigen（新建：生成）           │
│ 输入：URL                    │               │ 输入：prompt + template      │
│ 流水线：DL→ASR→LLM           │               │ 流水线：Plan→Assets→Render   │
│ LLM：LlmChatClient（现状）    │               │ LLM：LangChain4j（推荐）      │
│ 表：video_task               │               │ 表：aigen_task               │
│ 前端：/video-extract         │               │ 前端：/video-generate        │
└─────────────────────────────┘               └─────────────────────────────┘
```

**为什么独立包 / 独立表？**

1. 输入输出、状态机、中间产物完全不同，硬塞一张表会字段爆炸。  
2. 并发资源特性不同（提取偏 IO/ASR，生成偏 LLM + 渲染 CPU）。  
3. 权限与配额后续可分开计费。  
4. 代码演进互不拖累。

共享的只是 **横切能力**（LLM、鉴权、存储风格、事件推送）。

---

## 3. 总体架构

### 3.1 逻辑架构图

```
┌────────────────────────────────────────────────────────────────────────────┐
│  前端 okx-trading-web  /video-generate                                      │
│  · 提示词 / 模板 / 风格 / 时长 · 分镜编辑 · 任务列表 · 预览下载 · SSE 进度    │
└───────────────────────────────┬────────────────────────────────────────────┘
                                │ HTTPS + JWT
                                │ REST /api/v1/aigen/*
                                │ SSE  /api/v1/aigen/events
                                ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Spring Boot  okx-bot  package: com.dwcode.okxbot.aigen                     │
│                                                                              │
│  Controller → AigenTaskService → AigenTaskScheduler → AigenPipeline         │
│       │                              │                                       │
│       │              ┌───────────────┼───────────────────────────────┐       │
│       │              ▼               ▼                               ▼       │
│       │     ScriptPlanService  AssetGenService              RemotionClient   │
│       │     (LangChain4j)      (TTS / 配图 / 字幕)          (HTTP 调渲染)    │
│       │              │               │                               │       │
│       └──────────────┴───────────────┴───────────────────────────────┘       │
│                          │                                                   │
│              StorageService(Aigen) / EventPublisher / AiModelConfig           │
└───────┬──────────────────┬──────────────────────────────┬────────────────────┘
        │                  │                              │
        ▼                  ▼                              ▼
   MySQL              本地/对象存储                    Remotion Render Service
   aigen_task         data/aigen/{taskId}/            (Node.js + Remotion CLI)
   aigen_template     storyboard.json / assets/*      Chromium 无头渲染
   （复用 ai_model_config）  output.mp4                 模板库 = 独立 aigen-remotion
```

### 3.2 进程边界（生产推荐）

| 进程 | 技术 | 职责 | 资源特征 |
|------|------|------|----------|
| **okx-bot** | Java 17 / Spring Boot + **LangChain4j** | 鉴权、任务编排、LLM 分镜规划、元数据、API | 内存中等、IO 中等 |
| **remotion-render** | Node 20 + Remotion 4 | 接收 props，渲染 MP4/WebM | **CPU/内存重**，可水平扩展 |
| **TTS 服务**（可选） | 云 API 或本地 edge-tts / CosyVoice | 文案 → 音频 | 网络或 GPU |
| **Image 服务**（可选） | 云 API（DALL·E / Flux / SD） | 分镜 → 配图 | 网络或 GPU |
| **MySQL** | 8.x | 任务与模板元数据 | 常规 |
| **对象存储**（后期） | MinIO / OSS / S3 | 成品与素材 | 容量型 |

V1 可在单机跑：Java 宿主机 + Remotion 本机 Node 服务；TTS/配图先走云 API。

---

## 4. 核心设计决策

### 4.1 主路径：模板 + 结构化 Props（推荐，生产默认）

```
Prompt ──LLM──► Storyboard JSON ──填充──► Remotion Composition(props) ──render──► MP4
```

- 每种视频形态对应一个 **预置 Composition**（React 组件，代码审查后上线）。  
- LLM **只生成符合 JSON Schema 的数据**，不生成可执行代码。  
- 渲染层对 props 做 **严格校验**（Ajv / Jackson Schema）。  

**优点**：安全、可测、可回放、失败可重试、模板质量可控。  
**缺点**：表达能力受模板约束（通过增加模板解决）。

### 4.2 旁路：LLM 生成 Remotion 代码（仅开发/实验，不进 V1 主链路）

```
Prompt ──LLM──► TSX 源码 ──审核/沙箱──► 动态 bundle ──render──► MP4
```

问题：任意代码执行风险、依赖注入失控、渲染失败率高、难审计。  
可作为 **Studio 辅助写模板** 的离线工具，**不作为用户线上任务的默认路径**。

### 4.3 中间契约：Storyboard JSON

所有步骤读写同一份结构化文档（任务目录内 `storyboard.json`，同时入库 `storyboard_json`）。

```json
{
  "version": "1.0",
  "meta": {
    "title": "比特币减半通俗讲解",
    "language": "zh",
    "templateId": "knowledge-cards",
    "fps": 30,
    "width": 1080,
    "height": 1920,
    "durationInFrames": 900,
    "style": {
      "theme": "tech-dark",
      "primaryColor": "#00D4FF",
      "fontFamily": "Noto Sans SC"
    }
  },
  "audio": {
    "voiceId": "zh-CN-XiaoxiaoNeural",
    "bgmId": "upbeat-01",
    "bgmVolume": 0.15,
    "tracks": [
      { "sceneId": "s1", "text": "你知道比特币为什么每四年减半一次吗？", "src": "assets/audio/s1.mp3", "durationMs": 3200 }
    ]
  },
  "scenes": [
    {
      "id": "s1",
      "type": "title",
      "startFrame": 0,
      "durationInFrames": 90,
      "props": {
        "title": "比特币减半",
        "subtitle": "四分钟读懂",
        "background": "assets/images/bg-gradient.png"
      }
    },
    {
      "id": "s2",
      "type": "bullet-points",
      "startFrame": 90,
      "durationInFrames": 180,
      "props": {
        "heading": "核心要点",
        "items": ["供应减半", "历史行情回顾", "下一次时间点"],
        "image": "assets/images/s2.png"
      }
    }
  ],
  "subtitles": [
    { "startMs": 0, "endMs": 3200, "text": "你知道比特币为什么每四年减半一次吗？" }
  ]
}
```

**契约原则**：

1. `templateId` 决定用哪个 Composition。  
2. `scenes[].type` 必须是该模板声明支持的 scene 类型。  
3. 路径一律相对任务目录，渲染服务挂载后解析。  
4. 版本字段 `version` 支持契约演进。

### 4.4 LLM 职责切分

| 调用 | 输入 | 输出 | 温度建议 |
|------|------|------|----------|
| **Script Planner** | 用户 prompt + 模板 schema + 时长约束 | Storyboard（无音频路径） | 0.4–0.7 |
| **Copy Refiner**（可选） | 用户改稿后的场景文案 | 润色后文案 | 0.3 |
| **Subtitle Aligner**（可选） | 文案 + 音频时长 | 字幕时间轴 | 0.1 |

**实现选型（V1.1 定稿）**：

| 能力 | 技术 | 说明 |
|------|------|------|
| aigen 分镜 / 润色 | **LangChain4j** AiServices + Structured Output | 见 [§6](#6-llm-层langchain4j-集成设计) |
| 供应商与密钥 | 现有 `ai.providers.*` + `ai_model_config` | 不新建配置体系 |
| video 提取总结 | 可继续 `LlmChatClient` | 不强制同步改造 |
| 任务元数据 | `llm_provider` / `llm_model` 记入 `aigen_task` | 可观测、可复现 |

### 4.5 素材策略（可插拔）

```
AssetProvider (interface)
  ├─ TtsProvider
  │    ├─ EdgeTtsProvider（本地/免费，V1 默认）
  │    ├─ AzureTtsProvider
  │    └─ CosyVoiceProvider
  ├─ ImageProvider
  │    ├─ PlaceholderImageProvider（纯色/渐变，V1 默认）
  │    ├─ StockImageProvider（Unsplash 等）
  │    └─ AiImageProvider（云端文生图）
  └─ BgmProvider
       └─ LocalBgmLibrary（预置 BGM 库）
```

V1 先 **TTS 真声 + 占位/渐变背景 + 预置 BGM**，保证「能出片」；配图 AI 作为 Phase 2。

---

## 5. 端到端流水线

### 5.1 主路径时序

```
用户                  前端                 okx-bot                    Remotion
 │                     │                      │                          │
 │  填 prompt/模板     │                      │                          │
 │────────────────────►│  POST /aigen/tasks   │                          │
 │                     │─────────────────────►│ insert PENDING           │
 │                     │◄──── taskId ─────────│ notify scheduler         │
 │                     │  SSE 订阅            │                          │
 │                     │◄═════════════════════│ task.created             │
 │                     │                      │                          │
 │                     │                      │ PLANNING: LLM 写 storyboard
 │                     │◄═════════════════════│ task.status PLANNING     │
 │                     │                      │ ASSET_GENERATING: TTS/图  │
 │                     │◄═════════════════════│ task.status ...          │
 │                     │                      │ RENDERING ──────────────►│
 │                     │                      │   POST /render           │
 │                     │                      │   props + asset 路径      │
 │                     │                      │◄── output.mp4 ───────────│
 │                     │◄═════════════════════│ SUCCESS + previewUrl     │
 │  播放/下载          │  GET media           │                          │
 │◄────────────────────│◄─────────────────────│                          │
```

### 5.2 流水线步骤明细

| 步骤 | 状态 | 输入 | 输出 | 失败策略 |
|------|------|------|------|----------|
| 1. 校验与入库 | `PENDING` | prompt, templateId, options | task 行 | 同步拒绝（参数非法） |
| 2. 脚本规划 | `PLANNING` | prompt + 模板 schema | `storyboard.json`（无真实 asset 路径） | 可重试；LLM 超时/解析失败 |
| 3. 时长对齐 | `PLANNING` | 目标时长、场景数 | 调整 `durationInFrames` | 规则引擎，不调 LLM |
| 4. 素材生成 | `ASSET_GENERATING` | scenes 文案 | `assets/audio/*`、`assets/images/*` | 单 scene 失败可重试；整步可断点 |
| 5. 字幕合成 | `ASSET_GENERATING` | 文案 + 音频时长 | `subtitles[]` | 按音频时长均分或词级估算 |
| 6. 渲染 | `RENDERING` | storyboard + 模板 | `output.mp4`、可选 `poster.jpg` | Remotion 失败记 stderr；可重渲 |
| 7. 收尾 | `SUCCESS` | — | 写 result 元数据、耗时 | — |

### 5.3 用户编辑后重渲（关键产品能力）

```
SUCCESS / FAILED(可改)
    │ 用户改 storyboard（前端分镜编辑器）
    ▼
PUT /tasks/{id}/storyboard
    │ 仅更新 JSON，不自动渲染
    ▼
POST /tasks/{id}/rerender
    │ 状态 → ASSET_GENERATING 或直接 RENDERING
    │ （文案未变可跳过 TTS，文案变了只重生对应 scene 音频）
    ▼
SUCCESS（覆盖 output.mp4，保留版本可选）
```

**增量优化**：`asset_hash = sha256(text + voiceId)`，相同则复用音频文件。

### 5.4 与提取模块对比

| 维度 | VideoCoreExtractor | AI Video Generator |
|------|--------------------|--------------------|
| 输入 | 视频 URL | 文本 prompt |
| 核心 AI | ASR + 总结 | 分镜规划 + TTS +（可选）文生图 |
| 重计算 | yt-dlp / Whisper | Remotion Chromium 渲染 |
| 产出 | 摘要 JSON + 原视频 | **新合成 MP4** |
| 状态 | DOWNLOAD→TRANSCRIBE→SUMMARIZE | PLAN→ASSET→RENDER |

---

## 6. LLM 层：LangChain4j 集成设计

> **定稿原则**：LangChain4j 只负责 **LLM 调用与结构化产出**；任务状态机、TTS、Remotion、SSE、鉴权仍由 Spring Boot `aigen` 流水线掌控。  
> 不把「整条视频生成」做成 LangChain Agent 黑盒。

### 6.1 选型结论

| 问题 | 结论 |
|------|------|
| 后端能否使用 LangChain4j？ | **能**，与 Spring Boot 3.2 / Java 17 兼容 |
| aigen 是否采用？ | **是**，作为 Script Planner（及可选润色/字幕）默认实现 |
| video 提取是否强制迁移？ | **否**，`LlmChatClient` 可继续服务 Summarization |
| 是否用 Spring AI 替代？ | V1 **不混用**；团队后续若统一生态再评估 |

**为什么适合本项目**

1. **Structured Output**：Prompt → `StoryboardDto` 比手写字符串 JSON + 正则/容错解析更稳。  
2. **AiServices**：声明式接口，便于按模板挂不同 system prompt。  
3. **OpenAI 兼容**：与现有 `ai.providers`（NVIDIA / OpenAI / DeepSeek 等）对接成本低。  
4. **可演进**：Phase 2+ 可加 Tools（查模板库）、RAG（风格样例），而不改 Pipeline 骨架。

**明确不交给 LangChain4j 的职责**

| 职责 | 归属 |
|------|------|
| 任务排队 / 并发槽位 | `AigenTaskScheduler` |
| TTS / 配图 / BGM | `AssetGenerationService` + Provider |
| Remotion 渲染 | `RemotionRenderClient` + Node 服务 |
| SSE / 落盘 / 用户隔离 | 现有 Spring 模式 |
| 任意代码生成并执行 | **禁止**（见 §4.2） |

### 6.2 在总体架构中的位置

```
AigenPipeline (PLANNING 步骤)
        │
        ▼
 ScriptPlanService
        │
        ├─ 加载模板 schema / few-shot
        ├─ ChatModelFactory.get(provider, model)   ← 读 ai.providers + 任务级 model
        ├─ AiServices.create(ScriptPlanner.class, chatModel)
        ├─ StoryboardDto plan = planner.plan(...)  ← LangChain4j Structured Output
        ├─ StoryboardValidateService.validate(plan)
        └─ 失败 → RepairPlanner.repair(raw, errors) 再 validate
        │
        ▼
  storyboard.json 入库 / 落盘 → 进入 ASSET_GENERATING
```

### 6.3 Maven 依赖

在 `okx-bot/pom.xml` 增加（版本号实现时锁定 BOM，下表为示意）：

```xml
<properties>
    <langchain4j.version>1.0.0</langchain4j.version>
    <!-- 实现时以 Maven Central 稳定版为准，建议使用 BOM 统一管理 -->
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-bom</artifactId>
            <version>${langchain4j.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 核心 + OpenAI 兼容协议（覆盖 NVIDIA/DeepSeek 等 base-url） -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
    </dependency>
    <!-- 可选：Spring Boot 自动配置；若与现有 Bean 冲突可不用 starter，改为手动 @Bean -->
    <!--
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-spring-boot-starter</artifactId>
    </dependency>
    -->
</dependencies>
```

**建议**：V1 **不用 starter 一把梭**，改为 `ChatModelFactory` 手动构建，便于：

- 按任务动态切换 `provider` / `model`  
- 与现有 `AiProperties`、库表 `ai_model_config` 对齐  
- 避免与全局单例 ChatModel 抢配置  

### 6.4 包结构（LLM 相关）

在 `aigen` 包内（或抽到 `common.ai`，见 §6.9）：

```
com.dwcode.okxbot.aigen
├── llm/
│   ├── ChatModelFactory.java       # provider+model → ChatLanguageModel
│   ├── ScriptPlanner.java          # AiServices 接口（主规划）
│   ├── StoryboardRepairer.java     # AiServices 接口（JSON 修复）
│   ├── CopyRefiner.java            # 可选：文案润色
│   └── LlmObservability.java       # 可选：耗时、token、请求 id 日志
├── service/
│   ├── ScriptPlanService.java      # 门面：拼 prompt → 调 AiService → 校验 → repair
│   └── StoryboardValidateService.java
└── dto/
    ├── StoryboardDto.java          # Structured Output 目标类型
    ├── SceneDto.java
    └── ...
```

### 6.5 ChatModel 工厂（对接现有多供应商）

```java
/**
 * 从 AiProperties 解析 baseUrl / apiKey，按任务构建 OpenAI 兼容 ChatModel。
 * modelId 来自请求 options 或 ai_model_config 默认启用模型。
 */
public ChatLanguageModel create(String providerKey, String modelId) {
    ProviderConfig cfg = resolveProvider(providerKey); // 对齐现有 AiProperties
    requireNonBlank(cfg.getApiKey(), "供应商 api-key 未配置");
    String model = resolveModel(providerKey, modelId); // 库表优先

    return OpenAiChatModel.builder()
            .baseUrl(trimTrailingSlash(cfg.getBaseUrl())) // e.g. https://integrate.api.nvidia.com/v1
            .apiKey(cfg.getApiKey())
            .modelName(model)
            .temperature(aigenProperties.getLlm().getTemperature())
            .maxTokens(aigenProperties.getLlm().getMaxTokens())
            .timeout(Duration.ofSeconds(aigenProperties.getLlm().getTimeoutSeconds()))
            .maxRetries(aigenProperties.getLlm().getMaxRetries())
            .logRequests(false)   // 生产勿默认打全量 prompt
            .logResponses(false)
            .build();
}
```

| 配置来源 | 用途 |
|----------|------|
| `ai.providers.{key}.base-url` / `api-key` | 鉴权与端点（与 chat、video 共用） |
| `ai_model_config` | 可选模型列表、启用状态 |
| `aigen.llm.*` | temperature / max-tokens / timeout / 默认 provider |
| 任务字段 `llm_provider` / `llm_model` | 本次调用实际使用值（可复现） |

### 6.6 AiServices 接口设计

#### 6.6.1 主规划接口

```java
public interface ScriptPlanner {

    @SystemMessage("""
        你是短视频分镜编剧。根据用户主题与模板约束，生成完整分镜。
        必须遵守：
        1. templateId 固定为 {{templateId}}
        2. 场景 type 只能取：{{allowedSceneTypes}}
        3. 总时长约 {{targetDurationSec}} 秒，fps={{fps}}，分辨率 {{width}}x{{height}}
        4. 语言：{{language}}；文案口语化、适合口播
        5. 只填充文案与布局字段；不要编造远程 URL；音频/图片路径留空由系统后续写入
        """)
    StoryboardDto plan(
            @UserMessage String userPrompt,
            @V("templateId") String templateId,
            @V("allowedSceneTypes") String allowedSceneTypes,
            @V("targetDurationSec") int targetDurationSec,
            @V("fps") int fps,
            @V("width") int width,
            @V("height") int height,
            @V("language") String language
    );
}
```

创建要点**：

- 返回类型 **`StoryboardDto`**：走 LangChain4j Structured Output（JSON Schema / 工具调用，视模型能力）。  
- `@V` 注入模板运行时参数，避免把 schema 大段硬编码在 Java 字符串多处。  
- few-shot：可在 `ScriptPlanService` 里把模板示例拼进 `userPrompt` 前缀，或使用 `UserMessage.from(...)` 多段消息（实现期二选一，推荐 **模板目录 `example.json` 注入**）。

#### 6.6.2 Repair 接口

```java
public interface StoryboardRepairer {

    @SystemMessage("""
        你是 JSON 修复器。用户会提供不合法的分镜 JSON 与校验错误列表。
        请输出修复后的完整分镜对象，类型与字段必须满足模板约束。
        不要解释，不要包裹 markdown。
        """)
    StoryboardDto repair(
            @UserMessage String brokenJsonAndErrors,
            @V("templateId") String templateId,
            @V("allowedSceneTypes") String allowedSceneTypes
    );
}
```

#### 6.6.3 服务组装

```java
ChatLanguageModel model = chatModelFactory.create(provider, modelId);
ScriptPlanner planner = AiServices.builder(ScriptPlanner.class)
        .chatLanguageModel(model)
        .build();
StoryboardDto dto = planner.plan(...);
```

每次任务可 **新建 AiServices 代理**（模型不同必须重建）；同 provider/model 可做短时缓存（注意并发下不可变配置）。

### 6.7 ScriptPlanService 编排（含校验与降级）

```text
plan(task, template, options):
  1. chatModel = factory.create(task.llmProvider, task.llmModel)
  2. planner = AiServices(ScriptPlanner, chatModel)
  3. try:
       dto = planner.plan(prompt, templateVars...)
     catch (结构化解析失败 | 超时 | 5xx):
       if 支持 fallback:
         raw = 低级 ChatModel.generate(system + user)   // 纯文本
         dto = objectMapper.readValue(stripMarkdown(raw), StoryboardDto.class)
       else rethrow
  4. errors = validateService.validate(dto, template)
  5. if errors not empty && repairCount < maxRepair(默认1):
       dto = repairer.repair(dto + errors, ...)
       errors = validateService.validate(dto, template)
  6. if errors not empty → throw BusinessException(PLANNING_INVALID_STORYBOARD)
  7. normalizeFrames(dto)  // 秒→帧、对齐 fps、补 durationInFrames
  8. return dto
```

**本地校验（必须，不可只信 LLM）**

| 检查项 | 规则 |
|--------|------|
| templateId | 与任务一致 |
| scene.type | ∈ 模板白名单 |
| 时长 | 落在 `min_duration_sec`～`max_duration_sec` |
| 分辨率 | 与 aspectRatio 映射一致 |
| 路径 | 规划阶段不得出现 `..` 或绝对盘符路径 |
| 空文案 | title/items 非空 |

### 6.8 Structured Output 兼容与降级策略

不同供应商对 JSON Schema / response_format 支持不一致（尤其部分 NVIDIA 托管模型）。

| 模式 | 条件 | 行为 |
|------|------|------|
| **A. 原生 Structured** | 模型支持 json_schema / tool | AiServices 直接返回 DTO |
| **B. JSON Mode + 本地反序列化** | 仅支持 json_object | 要求输出 JSON，Jackson 映射 DTO |
| **C. 文本 + 清洗** | 弱模型 | 去 markdown 代码围栏后 parse；失败进 repair |

配置项建议：

```yaml
aigen:
  llm:
    structured-mode: auto   # auto | schema | json_object | plain
    max-repair-attempts: 1
    timeout-seconds: 120
```

`auto`：先 schema，失败一次后降级 plain + repair（实现期写清日志，便于调模型）。

### 6.9 与现有 `LlmChatClient` 共存策略

```
┌─────────────────────────────────────────────────────┐
│ AiProperties + ai_model_config（唯一配置源）          │
└──────────────────────────┬──────────────────────────┘
                           │
           ┌───────────────┴───────────────┐
           ▼                               ▼
   LlmChatClient                      ChatModelFactory
   (OkHttp 自研)                      (LangChain4j)
           │                               │
           ▼                               ▼
   video.SummarizationService         aigen.ScriptPlanService
   chat 模块（可选暂不动）              aigen.CopyRefiner 等
```

| 策略 | 说明 |
|------|------|
| **V1 推荐** | 双轨并行：生成用 LangChain4j，提取继续 `LlmChatClient` |
| **V1.5 可选** | 将 `ChatModelFactory` 抽到 `com.dwcode.okxbot.common.ai`，提取侧逐步迁移 |
| **禁止** | 两套各维护一份 api-key 配置 |

### 6.10 可观测性与安全

| 项 | 要求 |
|----|------|
| 日志 | 记录 provider、model、耗时、repair 次数；**默认不落完整 prompt/成片文案到 INFO**（DEBUG 可开关） |
| 超时 | `aigen.llm.timeout-seconds` + Pipeline 步骤超时，防止 PLANNING 挂死 |
| 重试 | 429/503：优先用模型层 maxRetries；与业务 max-repair 分开计数 |
| Token | 有则记 `promptTokens`/`completionTokens`（扩展字段可选，V1 可只记耗时） |
| 注入 | 用户 prompt 当数据不当前指令；system 中强调 scene 白名单；validate 兜底 |
| 密钥 | 仅服务端；LangChain4j 日志关闭 request body 中的 Authorization |

### 6.11 与 Spring AI 的边界

| 维度 | 本项目选择 |
|------|------------|
| V1 | **LangChain4j only**（aigen LLM 层） |
| 原因 | 结构化 DTO、AiServices、OpenAI 兼容多供应商更贴「分镜 JSON」 |
| 后续 | 若全公司统一 Spring AI，可替换 `ChatModelFactory` 实现，**AiServices 接口与 Pipeline 契约保持不变**（防腐层） |

防腐层建议：

```java
public interface ScriptPlanPort {
    StoryboardDto plan(PlanCommand cmd);
}
// LangChain4jScriptPlanAdapter implements ScriptPlanPort
```

`AigenPipeline` 只依赖 `ScriptPlanPort`，不直接依赖 `dev.langchain4j.*`（推荐，非强制）。

### 6.12 实现检查清单（LangChain4j）

- [ ] `pom.xml` 引入 BOM + `langchain4j` + `langchain4j-open-ai`  
- [ ] `ChatModelFactory` 读取 `AiProperties`，单测 mock provider  
- [ ] `StoryboardDto` 字段与 §4.3 契约一致  
- [ ] `ScriptPlanner` / `StoryboardRepairer` AiServices  
- [ ] `ScriptPlanService`：plan → validate → repair → normalize  
- [ ] `structured-mode` 降级路径可配置  
- [ ] PLANNING 失败错误信息对用户可读（不堆栈直出）  
- [ ] 与 `aigen_task.llm_provider/model`、耗时字段打通  

---

## 7. 后端包结构与职责

建议新建包（与 `video` 平级）：

```
com.dwcode.okxbot.aigen
├── agent/
│   ├── AigenPipeline.java          # 编排：Plan → Assets → Render
│   ├── AigenTaskScheduler.java     # 并发槽位 + 排队（对齐 VideoTaskScheduler）
│   └── AigenTaskAsyncRunner.java   # @Async 入口（独立 Bean，避免自调用）
├── client/
│   └── RemotionRenderClient.java   # HTTP 调 remotion-render 服务
├── config/
│   ├── AigenProperties.java        # aigen.* 配置
│   └── AigenAsyncConfig.java       # 独立线程池 aigenTaskExecutor
├── controller/
│   └── AigenTaskController.java    # REST + SSE
├── dto/
│   ├── AigenCreateRequest.java
│   ├── AigenTaskResponse.java
│   ├── StoryboardDto.java          # 与 JSON 契约对齐（LangChain4j 输出类型）
│   ├── SceneDto.java
│   └── ...
├── entity/
│   ├── AigenTaskEntity.java
│   └── AigenTemplateEntity.java    # 可选：模板元数据入库
├── enums/
│   └── AigenTaskStatus.java
├── event/
│   └── AigenTaskEventPublisher.java  # 可复用/泛化 Video 的 SSE 模式
├── llm/                              # LangChain4j 集成（见 §6）
│   ├── ChatModelFactory.java
│   ├── ScriptPlanner.java
│   ├── StoryboardRepairer.java
│   └── CopyRefiner.java              # 可选
├── mapper/
│   ├── AigenTaskMapper.java
│   └── AigenTemplateMapper.java
├── service/
│   ├── AigenTaskService.java       # 创建/查询/删除/重渲
│   ├── ScriptPlanService.java      # 门面：LangChain4j → Storyboard
│   ├── AssetGenerationService.java # TTS/图/BGM 编排
│   ├── StoryboardValidateService.java
│   ├── AigenStorageService.java    # data/aigen/{taskId}/
│   └── provider/
│       ├── TtsProvider.java
│       ├── ImageProvider.java
│       └── ...
└── util/
    └── FrameCalc.java              # 秒 ↔ 帧、对齐到 fps
```

### 7.1 关键类职责

**AigenPipeline**

```text
run(taskId):
  load task
  if terminal → release slot; return
  markRunning
  PLANNING:
    storyboard = scriptPlanService.plan(prompt, template, options)  // 内部 LangChain4j
    validate(storyboard)
    save storyboard.json + DB
  ASSET_GENERATING:
    assetGenService.generateAll(taskId, storyboard)  // 写回 audio/image 路径
    save storyboard
  RENDERING:
    remotionClient.render(templateId, storyboard, workDir)
    set outputPath, posterPath, fileSize
  SUCCESS / onException FAILED
  markFinished → tryStartNext
```

**ScriptPlanService**

- 加载模板的 **schema 说明 + few-shot 示例**（`classpath:aigen/templates/{id}/` 或 DB）。  
- 经 **`ChatModelFactory` + LangChain4j AiServices** 调用 `ScriptPlanner.plan(...)`，目标类型 `StoryboardDto`。  
- 业务校验失败则 **`StoryboardRepairer` 修复一轮**（见 §6.7）。  
- 输出未带 asset 真实路径的 storyboard；**不在此步骤调 Remotion**。

**RemotionRenderClient**

```http
POST http://127.0.0.1:3100/render
Content-Type: application/json

{
  "jobId": "2076...",
  "compositionId": "KnowledgeCards",
  "inputProps": { ...storyboard... },
  "outputFile": "/data/aigen/2076.../output.mp4",
  "codec": "h264",
  "imageFormat": "jpeg",
  "crf": 18,
  "timeoutMs": 600000
}
```

响应：`{ "success": true, "outputFile": "...", "durationMs": 12345, "stderrTail": "..." }`

---

## 8. Remotion 渲染服务

### 8.1 目录规划

**实现阶段新建**独立工程（勿放进 `okx-trading-web`；Vue 只负责提交/进度/播放）。  
建议仓库路径（可调整）：`aigen-remotion/`。

```
aigen-remotion/                       # 新建：模板 + 渲染一体或拆两包均可
├── package.json                      # remotion + react + @remotion/renderer
├── src/
│   ├── Root.tsx                      # 注册所有 Composition
│   ├── templates/
│   │   ├── KnowledgeCards/
│   │   │   ├── index.tsx
│   │   │   ├── schema.ts             # props 类型 + 运行时校验
│   │   │   └── scenes/
│   │   ├── ProductPitch/
│   │   ├── TalkingPoints/
│   │   └── TechPromo/
│   ├── components/                   # 公共字幕条、进度条、Logo 角标
│   └── lib/
│       ├── storyboard.ts
│       └── fonts.ts
├── public/                           # 预置字体、默认 BGM、占位图
├── render-service/                   # HTTP 渲染网关（可同仓子目录）
│   ├── package.json
│   ├── src/
│   │   ├── server.ts                 # Express/Fastify
│   │   ├── render.ts                 # @remotion/renderer
│   │   └── jobs.ts                   # 内存队列 / 并发限制
│   └── Dockerfile
└── README.md
```

> 历史：`remotion-video/remotion-project` 仅为早期 Demo 且不可用，**已从仓库删除**，勿再依赖。

### 8.2 Composition 注册约定

```tsx
// Root.tsx（示意）
<Composition
  id="KnowledgeCards"
  component={KnowledgeCards}
  durationInFrames={300}
  fps={30}
  width={1080}
  height={1920}
  defaultProps={defaultKnowledgeCardsProps}
  calculateMetadata={async ({ props }) => ({
    durationInFrames: props.meta.durationInFrames,
    width: props.meta.width,
    height: props.meta.height,
    fps: props.meta.fps,
  })}
/>
```

- **compositionId** 与后端 `template.composition_id` 一一对应。  
- `calculateMetadata` 根据 storyboard 动态时长/分辨率。  
- 竖屏 1080×1920（短视频）与横屏 1920×1080（宣传）均支持。

### 8.3 渲染服务 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 存活 + 队列长度 + 版本 |
| GET | `/compositions` | 列出可用 compositionId |
| POST | `/render` | 同步渲染（V1）；长任务可后续改异步 + 回调 |
| POST | `/still` | 渲染单帧海报（可选） |

**并发**：服务内 `maxConcurrentRenders`（默认 1～2，视 CPU 核数）。超出排队；队列满返回 429，Java 侧退避重试。

### 8.4 为何独立 Node 服务而不是 Java 直接 `ProcessBuilder remotion`

| 方案 | 评价 |
|------|------|
| Java 调 CLI | 简单，但进程管理、超时杀树、并发、健康检查都要自建 |
| **独立 HTTP 服务** | 可单独扩缩容、可 Docker、可热更新模板、与 Java 生命周期解耦 |
| Remotion Lambda | 成本/云绑定，后期可选，接口层预留 `RenderBackend` 抽象即可 |

V1：**本机/容器 HTTP 服务**；抽象接口：

```java
public interface VideoRenderBackend {
    RenderResult render(RenderRequest request);
}
// RemotionHttpBackend / RemotionCliBackend / RemotionLambdaBackend
```

---

## 9. 数据模型与持久化

### 9.1 表：`aigen_task`

```sql
CREATE TABLE IF NOT EXISTS aigen_task (
    id              BIGINT       NOT NULL COMMENT '主键 snowflake',
    user_id         BIGINT       NOT NULL COMMENT '所属用户',
    title           VARCHAR(256)          COMMENT '标题（LLM 生成或用户填）',
    prompt          TEXT         NOT NULL COMMENT '用户原始提示词',
    negative_prompt VARCHAR(1024)         COMMENT '负向约束（可选）',
    template_id     VARCHAR(64)  NOT NULL COMMENT '模板 ID',
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    current_step    VARCHAR(128)          COMMENT '当前步骤说明',
    progress        INT          NOT NULL DEFAULT 0 COMMENT '0-100',

    -- 生成参数
    language        VARCHAR(16)  DEFAULT 'zh',
    aspect_ratio    VARCHAR(16)  DEFAULT '9:16' COMMENT '9:16 / 16:9 / 1:1',
    target_duration_sec INT      DEFAULT 30,
    style_json      JSON                  COMMENT '主题色/字体等覆盖',
    voice_id        VARCHAR(64)           COMMENT 'TTS 音色',
    bgm_id          VARCHAR(64)           COMMENT '背景音乐',

    -- LLM
    llm_provider    VARCHAR(64),
    llm_model       VARCHAR(128),

    -- 中间态与产物
    storyboard_json LONGTEXT              COMMENT '完整分镜契约',
    storyboard_path VARCHAR(1024),
    work_dir        VARCHAR(1024),
    output_path     VARCHAR(1024),
    poster_path     VARCHAR(1024),
    output_size_bytes BIGINT,
    duration_seconds DOUBLE,

    -- 错误与耗时
    error_message   TEXT,
    plan_duration_ms     BIGINT,
    asset_duration_ms    BIGINT,
    render_duration_ms   BIGINT,
    total_duration_ms    BIGINT,

    started_at      DATETIME(3),
    finished_at     DATETIME(3),
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    INDEX idx_aigen_user_created (user_id, created_at),
    INDEX idx_aigen_status (status),
    INDEX idx_aigen_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI视频生成任务';
```

### 9.2 表：`aigen_template`（模板注册表）

```sql
CREATE TABLE IF NOT EXISTS aigen_template (
    id              VARCHAR(64)  NOT NULL COMMENT '如 knowledge-cards',
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    composition_id  VARCHAR(128) NOT NULL COMMENT 'Remotion Composition id',
    aspect_ratios  VARCHAR(128) DEFAULT '9:16,16:9',
    min_duration_sec INT DEFAULT 10,
    max_duration_sec INT DEFAULT 120,
    default_duration_sec INT DEFAULT 30,
    schema_version  VARCHAR(16)  DEFAULT '1.0',
    schema_json     JSON         COMMENT 'Storyboard 子集 schema 或引用',
    preview_url     VARCHAR(512) COMMENT '示例成片/封面',
    enabled         TINYINT      NOT NULL DEFAULT 1,
    sort_order      INT          DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI视频生成模板';
```

V1 也可先 **YAML/JSON 静态配置**（`classpath:aigen/templates.yml`），表结构预留即可。

### 9.3 任务工作目录布局

```
data/aigen/{taskId}/
├── request.json              # 原始请求快照
├── storyboard.json           # 当前分镜（含 asset 路径）
├── storyboard.plan.json      # LLM 初稿（调试用）
├── assets/
│   ├── audio/
│   │   ├── s1.mp3
│   │   └── s2.mp3
│   ├── images/
│   │   ├── s2.png
│   │   └── bg.png
│   └── bgm/
│       └── track.mp3         # 或符号链接到公共库
├── logs/
│   ├── plan.log
│   └── render.log
├── poster.jpg
└── output.mp4
```

配置：`aigen.work-dir: ./data/aigen`（对齐现有 `video.work-dir`）。

### 9.4 与 `ai_model_config` 关系

- **不新建 LLM 配置表**，继续复用 `ai_model_config` + `ai.providers.*`。  
- 前端生成页的「模型选择」可直接调用现有 `/api/v1/video/models` 或抽公共 `/api/v1/ai/models`（Phase 2 重构）。  
- **aigen 规划层**：经 `ChatModelFactory` + **LangChain4j** 使用上述配置（见 §6）。  
- **video 提取层**：可继续 `LlmChatClient`；后续可选统一到 `common.ai`。

---

## 10. 状态机

```
                    ┌─────────────┐
         提交        │   PENDING   │  排队
          ─────────►│             │
                    └──────┬──────┘
                           │ scheduler 抢到槽位
                           ▼
                    ┌─────────────┐
                    │  PLANNING   │  LLM 分镜
                    └──────┬──────┘
                           │
                           ▼
                    ┌─────────────────┐
                    │ ASSET_GENERATING│  TTS / 配图 / 字幕
                    └──────┬──────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │  RENDERING  │  Remotion
                    └──────┬──────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
       ┌─────────────┐           ┌─────────────┐
       │   SUCCESS   │           │   FAILED    │
       └──────┬──────┘           └──────┬──────┘
              │  用户改稿/重渲            │  重试
              └──────────► PENDING / ASSET_GENERATING / RENDERING
```

枚举 `AigenTaskStatus`：

| 状态 | 含义 | 终态 |
|------|------|------|
| `PENDING` | 排队 | 否 |
| `PLANNING` | 脚本规划 | 否 |
| `ASSET_GENERATING` | 素材生成 | 否 |
| `RENDERING` | 渲染中 | 否 |
| `SUCCESS` | 成片可用 | 是 |
| `FAILED` | 失败 | 是 |
| `CANCELLED` | 用户取消 | 是 |

`progress` 建议映射：PENDING=0，PLANNING=10～30，ASSET=30～70，RENDERING=70～95，SUCCESS=100。

协作式取消：对齐提取模块 `pauseRequested`，每步边界检查 `cancelRequested`。

---

## 11. REST API 设计

统一前缀：`/api/v1/aigen`  
鉴权：Bearer JWT；列表/详情按 `user_id` 隔离（超管可另开管理接口）。

### 11.1 任务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tasks` | 创建生成任务 |
| GET | `/tasks` | 分页列表 `?page&size&status` |
| GET | `/tasks/{id}` | 详情（含 progress、storyboard 摘要） |
| GET | `/tasks/{id}/storyboard` | 完整分镜 JSON |
| PUT | `/tasks/{id}/storyboard` | 用户编辑分镜（仅非运行中） |
| POST | `/tasks/{id}/rerender` | 基于当前 storyboard 重渲 |
| POST | `/tasks/{id}/retry` | 失败任务从失败步骤重试 |
| POST | `/tasks/{id}/cancel` | 取消排队或运行中任务 |
| DELETE | `/tasks/{id}` | 逻辑删 + 清理文件 |
| GET | `/tasks/{id}/media/output` | 流式输出 MP4 |
| GET | `/tasks/{id}/media/poster` | 封面图 |

### 11.2 模板与资源

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/templates` | 启用模板列表 |
| GET | `/templates/{id}` | 模板详情 + 默认参数 |
| GET | `/voices` | 可用 TTS 音色 |
| GET | `/bgm` | 可用 BGM 列表 |

### 11.3 事件

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/events` | SSE：`task.created` / `task.status` / `task.deleted` |

事件 payload 风格对齐 `VideoTaskEventPublisher`，便于前端复用 `video.events.ts` 模式。

### 11.4 创建请求示例

```json
POST /api/v1/aigen/tasks
{
  "prompt": "用通俗语言讲解比特币减半，适合短视频，语气轻松",
  "templateId": "knowledge-cards",
  "options": {
    "language": "zh",
    "aspectRatio": "9:16",
    "targetDurationSec": 45,
    "voiceId": "zh-CN-XiaoxiaoNeural",
    "bgmId": "upbeat-01",
    "style": {
      "theme": "tech-dark",
      "primaryColor": "#00D4FF"
    },
    "llmProvider": "nvidia",
    "llmModel": "meta/llama-3.1-70b-instruct"
  }
}
```

响应：

```json
{
  "code": 0,
  "data": {
    "id": "207640412656660482",
    "status": "PENDING",
    "progress": 0,
    "currentStep": "排队中",
    "templateId": "knowledge-cards",
    "createdAt": "2026-07-13 12:00:00"
  }
}
```

---

## 12. 前端模块设计

### 12.1 路由与导航

| 路径 | 页面 | 菜单分组建议 |
|------|------|--------------|
| `/video-generate` | AI 视频生成（主工作台） | AI 工具（与 video-extract 并列） |
| `/video-generate/:taskId` | 任务详情 / 分镜编辑（可选独立路由） | — |

`router/index.ts` 增加路由；`BasicLayout` / `AppHeader` 增加入口，权限与登录守卫同现有业务页。

### 12.2 前端文件规划

```
okx-trading-web/src/
├── api/
│   ├── aigen.api.ts              # REST
│   └── aigen.events.ts           # SSE（可参考 video.events.ts）
├── views/
│   └── video-generate/
│       ├── index.vue             # 左：创建表单；右：任务列表 + 进度
│       ├── components/
│       │   ├── PromptForm.vue
│       │   ├── TemplatePicker.vue
│       │   ├── TaskCard.vue
│       │   ├── StoryboardEditor.vue   # 场景列表编辑
│       │   ├── VideoPlayer.vue
│       │   └── ProgressSteps.vue      # PLAN / ASSET / RENDER
│       └── ...
└── types/
    └── api.d.ts                  # 扩展 Aigen* 类型
```

### 12.3 主界面信息架构（V1）

```
┌──────────────────────────────────────────────────────────────┐
│  AI 视频生成                                                  │
├────────────────────┬─────────────────────────────────────────┤
│  模板选择（卡片）   │  任务列表                                │
│  提示词 TextArea    │  ┌───────────────────────────────────┐ │
│  时长 / 比例 / 音色 │  │ 进行中 · SSE 进度条 · 当前步骤     │ │
│  模型（复用）       │  └───────────────────────────────────┘ │
│  [生成视频]         │  历史任务：封面 | 标题 | 状态 | 操作     │
│                    │  操作：预览 · 编辑分镜 · 重渲 · 下载 · 删 │
└────────────────────┴─────────────────────────────────────────┘
```

**分镜编辑器（V1.5）**：按 scene 展示标题/正文/时长；改完保存 storyboard → 一键重渲。不必一上来做时间轴拖拽。

### 12.4 与提取页一致性

- 请求封装：`request.ts`、错误 toast 模式一致。  
- 进度：SSE 优先，断线回退轮询 `GET /tasks/{id}`（与 video-extract 相同策略）。  
- 模型管理：可跳转复用提取页的模型管理弹窗，或顶栏共享入口。

---

## 13. 配置项

### 13.1 `application.yml` 新增段

```yaml
aigen:
  work-dir: ./data/aigen
  # 并发：与 aigenTaskExecutor / Remotion 能力对齐
  max-concurrent-tasks: 1
  # 渲染服务
  remotion:
    base-url: http://127.0.0.1:3100
    timeout-seconds: 600
    codec: h264
    crf: 18
  # LLM 规划（LangChain4j；密钥/端点复用 ai.providers）
  llm:
    provider: nvidia
    model:
    temperature: 0.5
    max-tokens: 8192
    max-retries: 3              # HTTP/模型层重试（429/503）
    timeout-seconds: 120
    # Structured Output 策略：auto 优先 schema，失败降级 plain + repair
    structured-mode: auto       # auto | schema | json_object | plain
    max-repair-attempts: 1
    log-prompts: false          # true 仅本地调试；生产保持 false
  # TTS
  tts:
    provider: edge          # edge | azure | mock
    default-voice: zh-CN-XiaoxiaoNeural
    timeout-seconds: 120
  # 配图
  image:
    provider: placeholder   # placeholder | stock | ai
  # 清理
  cleanup-on-delete: true
  retain-days: 30           # 可选定时清理失败/过期任务文件
```

### 13.2 独立线程池

```java
// AigenAsyncConfig
corePoolSize = 1
maxPoolSize  = 2
queueCapacity = 20
threadNamePrefix = "aigen-task-"
// 拒绝策略：CallerRuns 或 Abort + 友好错误（推荐 Abort，避免拖垮 HTTP 线程）
```

**注意**：Remotion 极吃 CPU，Java 侧 `max-concurrent-tasks` 建议 ≤ Remotion `maxConcurrentRenders`。

---

## 14. 并发、队列与可扩展性

### 14.1 V1：单机内存调度（对齐现网提取模块）

```
AigenTaskScheduler
  MAX_CONCURRENT = aigen.max-concurrent-tasks
  activeTaskIds + DB status 估占用
  PENDING 按 created_at FIFO
```

优点：实现快、与现有 `VideoTaskScheduler` 同构。  
局限：多实例部署时会超卖并发。

### 14.2 V2：可扩展演进路径

| 阶段 | 队列 | 渲染 | 存储 |
|------|------|------|------|
| V1 | 内存 + DB 状态 | 本机 Remotion HTTP | 本地盘 |
| V2 | Redis List / Stream 或 DB `FOR UPDATE SKIP LOCKED` | Remotion 多副本 + 负载均衡 | MinIO |
| V3 | 独立 worker 进程消费 | Remotion Lambda / GPU 节点 | OSS + CDN |

**接口预留**：

- `TaskQueue.enqueue(taskId)` / `claimNext()`  
- `VideoRenderBackend`  
- `ObjectStorage`（`put`/`get`/`presign`）

业务 Pipeline 不感知底层从本地切到 S3。

### 14.3 资源隔离矩阵

| 负载 | 线程池 / 进程 | 说明 |
|------|---------------|------|
| 交易策略 Job | Spring `@Scheduled` | 不被视频拖垮 |
| 视频提取 | `videoTaskExecutor` | 已有 |
| 视频生成 | `aigenTaskExecutor` | 新建，更保守 |
| Remotion | 独立 Node 进程 | CPU 隔离，可 `cpulimit`/K8s limits |

---

## 15. 安全与合规

| 风险 | 对策 |
|------|------|
| 提示词注入导致 LLM 输出恶意 JSON | Schema 严格校验；未知 scene type 拒绝；路径规范化禁止 `..` |
| 用户改 storyboard 指向任意本地文件 | 仅允许 `assets/` 相对路径；渲染前 canonicalize 校验在 workDir 内 |
| SSRF（若模板支持外链图片） | V1 禁止外链；后续白名单域名 + 大小限制 |
| 未授权访问成片 | JWT + `user_id` 校验；媒体接口鉴权 |
| 资源耗尽 | 时长上限、并发上限、prompt 长度限制、日配额（可后续） |
| LLM/TTS 密钥 | 仅存服务端 yml/环境变量；不回传前端 |
| 版权 | BGM/字体使用可商用素材；用户生成内容责任声明 |

---

## 16. 部署拓扑

### 16.1 本地开发

```
┌─────────────┐   ┌─────────────┐   ┌──────────────────┐
│ Vue :5173   │──►│ Java :8080  │──►│ Remotion :3100   │
└─────────────┘   │  MySQL      │   │ aigen-remotion   │
                  │  data/aigen │   │ （实现期新建）    │
                  └─────────────┘   └──────────────────┘
                         │
                         ▼
                  云端 LLM / Edge-TTS
```

### 16.2 `docker-compose` 扩展建议

在 `docker-compose.video.yml` 旁新增 `docker-compose.aigen.yml`（路径随实际工程名调整）：

```yaml
services:
  remotion-render:
    build: ../aigen-remotion/render-service
    ports:
      - "3100:3100"
    volumes:
      # 与 Java 共享任务目录
      - ./data/aigen:/data/aigen
    environment:
      MAX_CONCURRENT_RENDERS: 1
      REMOTION_PROJECT_PATH: /app
    # 渲染需要足够 shared memory
    shm_size: "1gb"
```

Java 配置：`aigen.remotion.base-url: http://127.0.0.1:3100`，`work-dir` 与 volume 一致。

### 16.3 生产要点

- Remotion 容器安装 Chromium 依赖（官方 Docker 文档）。  
- 成品走 Nginx `X-Accel-Redirect` 或对象存储预签名 URL，避免大文件打满 Java 堆。  
- 健康检查：Java `/actuator/health` + Remotion `/health`。  
- 日志：每个 taskId MDC 贯穿 plan/asset/render。

---

## 17. 分阶段落地计划

### Phase 0 — 骨架（约 2～3 天）

- [ ] 建表 SQL：`aigen_task`（+ 可选 template）  
- [ ] 包结构、`AigenProperties`、线程池、Scheduler  
- [ ] `POST/GET/DELETE /tasks` + 假流水线（mock 睡眠推进状态）  
- [ ] SSE 推送  
- [ ] 前端页面壳：表单 + 列表 + 进度  

### Phase 1 — 最小可用成片（MVP，约 1～1.5 周）

- [ ] 1 个竖屏模板 `KnowledgeCards`（标题 + 要点 + 结尾）  
- [ ] **LangChain4j 依赖 + `ChatModelFactory`**  
- [ ] `ScriptPlanner` AiServices + `ScriptPlanService`（plan → validate → repair）  
- [ ] `structured-mode` 降级路径（至少 plain + Jackson 可用）  
- [ ] TTS：Edge-TTS 或 mock 静音轨 + 字幕  
- [ ] `render-service` + Java `RemotionRenderClient`  
- [ ] 成片播放与下载  
- [ ] 失败重试、删除清理  

**MVP 验收**：输入一句提示词，数分钟内得到可播放 MP4。

### Phase 2 — 可编辑与多模板（约 1 周）

- [ ] 分镜编辑 + 重渲  
- [ ] 第 2～3 个模板（产品卖点、科技宣传）  
- [ ] BGM、主题色、音色选择  
- [ ] 分步耗时展示、poster 封面  
- [ ] asset 哈希增量复用  
- [ ] 可选：`CopyRefiner` AiService；`ScriptPlanPort` 防腐层  

### Phase 3 — 质量与规模（持续）

- [ ] AI 配图 Provider  
- [ ] 词级字幕对齐  
- [ ] Redis 队列 / 多实例 claim  
- [ ] 对象存储 + CDN  
- [ ] 用户配额与审计日志  
- [ ] 模板热更新（不发 Java 版）  
- [ ] 可选：video 提取侧 LLM 统一到 LangChain4j / `common.ai`  

---

## 18. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| Remotion 渲染慢 / OOM | 任务堆积 | 限制时长与分辨率；并发 1；Docker shm；降 CRF 换速度 |
| LLM JSON 不稳定 | PLANNING 失败率高 | LangChain4j Structured Output + 本地 validate + repair；few-shot；必要时换模型 |
| 供应商不支持 JSON Schema | 结构化调用失败 | `structured-mode: auto` 降级 plain；见 §6.8 |
| LangChain4j 版本/API 变动 | 编译或行为差异 | BOM 锁版本；`ScriptPlanPort` 防腐，便于替换实现 |
| TTS 网络波动 | 素材阶段失败 | 超时重试；mock fallback 开发环境 |
| 中文字体缺失 | 成片方框/乱码 | 模板内嵌 Noto Sans SC；Docker 打包字体 |
| 与提取模块争抢磁盘 | IO 抖动 | 分目录；定时清理；监控磁盘 |
| 提示词过短/过长 | 成片质量差 | 前端引导 + 后端长度校验 + 模板内 min 场景数 |
| Prompt/密钥进日志 | 合规风险 | `log-prompts: false`；关闭 LC4j request body 日志 |

---

## 19. 验收标准

### 19.1 功能

1. 登录用户可提交生成任务并收到 `taskId`。  
2. 任务状态按 PENDING → PLANNING → ASSET_GENERATING → RENDERING → SUCCESS 推进，SSE 实时更新。  
3. SUCCESS 后可在线播放与下载 MP4。  
4. 失败有明确 `errorMessage`，支持 retry。  
5. 用户只能看到自己的任务。  
6. 删除任务同时清理工作目录（可配置）。  
7. PLANNING 步骤经 **LangChain4j** 产出可校验的 `storyboard`（非法 JSON 经 repair 或明确失败）。  

### 19.2 非功能

| 项 | 目标（单机参考） |
|----|------------------|
| 30s 竖屏成片端到端 | P50 &lt; 3 min（含 LLM+TTS+渲染） |
| 并发 | 默认 1 路生成不拖垮交易/提取 |
| API 提交延迟 | &lt; 200ms 返回 taskId |
| 渲染服务宕机 | 任务 FAILED 可重试，Java 不崩溃 |
| LLM 超时 | 不超过 `aigen.llm.timeout-seconds`，任务可 FAILED 并重试 |

### 19.3 文档与交付物

- 本架构文档（`okx-bot/doc/AI视频生成_架构设计方案.md`，含 §6 LangChain4j）  
- SQL 迁移脚本（实现阶段：`okx-bot/doc/sql/aigen_task.sql`）  
- Remotion 模板 README（compositionId 与 props 说明）  
- 后端使用手册（对齐 VideoCoreExtractor 文档风格）  

---

## 附录 A — 推荐首批模板

| templateId | compositionId | 形态 | 典型时长 | 场景类型 |
|------------|---------------|------|----------|----------|
| `knowledge-cards` | `KnowledgeCards` | 竖屏知识卡 | 30–60s | title / bullets / outro |
| `product-pitch` | `ProductPitch` | 横/竖产品卖点 | 20–45s | hook / features / cta |
| `talking-points` | `TalkingPoints` | 口播字幕条 | 30–90s | narration（强字幕） |
| `tech-promo` | `TechPromo` | 科技感宣传 | 15–30s | logo / metrics / cta |

---

## 附录 B — System Prompt 骨架（Script Planner）

> 实现时优先落在 LangChain4j `@SystemMessage` / 模板资源文件中（见 §6.6），以下为语义骨架。

```text
你是短视频分镜编剧。根据用户主题，输出且仅输出合法 JSON（符合给定 Schema）。
约束：
1. templateId 固定为 {templateId}
2. 场景 type 只能取：{allowedSceneTypes}
3. 总时长约 {targetDurationSec} 秒，fps={fps}，分辨率 {width}x{height}
4. 每场 props 字段必须完整；文案简洁口语化，适合 {language}
5. 不要输出 markdown 代码块标记，不要解释
Schema:
{schemaJson}
示例:
{fewShotExample}
用户主题:
{userPrompt}
```

---

## 附录 C — 模块命名对照

| 概念 | 代码命名 |
|------|----------|
| 模块包 | `com.dwcode.okxbot.aigen` |
| LLM 子包 | `com.dwcode.okxbot.aigen.llm` |
| 规划接口 | `ScriptPlanner`（LangChain4j AiServices） |
| 模型工厂 | `ChatModelFactory` |
| 规划门面 | `ScriptPlanService` / 可选 `ScriptPlanPort` |
| API 前缀 | `/api/v1/aigen` |
| 任务表 | `aigen_task` |
| 工作目录 | `./data/aigen/{taskId}` |
| 前端路由 | `/video-generate` |
| 前端 API | `aigen.api.ts` |
| 渲染工程（新建） | `aigen-remotion/`（含 render-service） |
| 配置前缀 | `aigen.*` / `aigen.llm.*` |
| Maven | `langchain4j` + `langchain4j-open-ai`（BOM 锁版本） |

---

## 附录 D — 实现检查清单（开发用）

**后端**

- [ ] Entity / Mapper / SQL  
- [ ] Status 枚举 + 状态机校验  
- [ ] Scheduler + AsyncRunner + Pipeline  
- [ ] LangChain4j 依赖 + `ChatModelFactory`  
- [ ] `ScriptPlanner` / `StoryboardRepairer` + `ScriptPlanService`  
- [ ] `StoryboardValidateService` + repair  
- [ ] TtsProvider（edge）+ Placeholder Image  
- [ ] RemotionRenderClient  
- [ ] Controller + 媒体流式下载  
- [ ] SSE EventPublisher  
- [ ] 配置与 application.yml（含 `aigen.llm.structured-mode`）  

**Remotion**

- [ ] KnowledgeCards 模板 + schema  
- [ ] calculateMetadata  
- [ ] render-service HTTP  
- [ ] 中文字体  
- [ ] Docker 镜像  

**前端**

- [ ] 路由与菜单  
- [ ] 创建表单 + 模板选择  
- [ ] 任务列表 + SSE  
- [ ] 播放器 + 下载  
- [ ] 分镜编辑（Phase 2）  

---

**文档维护**：实现落地后，同步补充「后端使用与验证手册」与 `sql/aigen_task.sql`，并在本文件标注代码对齐版本号。  

**版本记录**

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-13 | 首版架构（提示词→Remotion 成片） |
| v1.1 | 2026-07-13 | 新增 §6 LLM 层 LangChain4j 集成设计；章节顺延；配置/阶段/风险同步 |
| v1.2 | 2026-07-13 | 删除无效 demo `remotion-video`；Remotion 改为实现期新建 `aigen-remotion`；另见极简说明文档 |
