# AI 文生图 — 后端实现与开发手册

**模块名称**：AI Image Generator（提示词 → 图片）  
**代码包**：`com.dwcode.okxbot.imggen`  
**架构方案**：同目录 [`AI文生图_架构设计方案.md`](./AI文生图_架构设计方案.md)  
**SQL**：[`sql/imggen_task.sql`](./sql/imggen_task.sql)  
**文档版本**：v1.0（对齐当前仓库代码）  
**日期**：2026-07-14  

> 本文面向**二次开发 / 排障**，按「请求怎么进来 → 任务怎么调度 → 流水线怎么跑 → 图从哪来 → 前端怎么拿」说明。  
> 读完应能：定位类、改配置、接新 Provider、排查失败任务。

---

## 目录

1. [一句话主链路](#1-一句话主链路)
2. [与 video / aigen 的对照](#2-与-video--aigen-的对照)
3. [包结构与类地图](#3-包结构与类地图)
4. [端到端调用链](#4-端到端调用链)
5. [核心类与方法说明](#5-核心类与方法说明)
6. [流水线步骤详解](#6-流水线步骤详解)
7. [Provider 适配（FLUX / Mock / 润色）](#7-provider-适配flux--mock--润色)
8. [状态机与并发调度](#8-状态机与并发调度)
9. [数据模型与落盘](#9-数据模型与落盘)
10. [REST API 一览](#10-rest-api-一览)
11. [配置项](#11-配置项)
12. [扩展指南](#12-扩展指南)
13. [排障清单](#13-排障清单)

---

## 1. 一句话主链路

```
前端 POST /api/v1/imggen/tasks
    → ImgGenTaskService.create()          校验、写库 PENDING
    → ImgGenTaskScheduler.notifyPending() 抢并发槽
    → ImgGenTaskAsyncRunner.runAsync()    线程池异步
    → ImgGenPipeline.run()
          ├─（可选）EnhanceStep           LLM 润色 prompt
          └─ GenerateStep                 ImageGenPort 出图
    → SUCCESS / FAILED + SSE 推送
前端 GET media 拉 PNG/JPEG
```

**设计口号**：业务只依赖 Port；出图用 NVIDIA GenAI；润色用 Chat；编排用 Spring 任务机。

---

## 2. 与 video / aigen 的对照

| 维度 | video 提取 | aigen 视频生成 | **imggen 文生图** |
|------|------------|----------------|-------------------|
| 包名 | `...video` | `...aigen` | **`...imggen`** |
| 入口 | URL | prompt + 模板 | **prompt + 比例** |
| 流水线 | DL→ASR→总结 | Plan→TTS→Render | **Enhance?→Generate** |
| 重计算 | Whisper | Remotion | **NVIDIA FLUX** |
| 任务表 | `video_task` | `aigen_task` | **`imggen_task`** |
| 线程池 | `videoTaskExecutor` | `aigenTaskExecutor` | **`imggenTaskExecutor`** |
| 共享能力 | JWT、`ai.providers`、SSE 模式 | 同左 | 同左 |

**刻意独立**：表、包、线程池分开，避免字段爆炸与资源互相拖死。  
**刻意复用**：鉴权、`AiProperties`、`LlmChatClient`、任务+SSE 骨架与 aigen 同构。

---

## 3. 包结构与类地图

```
com.dwcode.okxbot.imggen
├── controller/
│   └── ImgGenTaskController          # REST + SSE 入口
├── service/
│   ├── ImgGenTaskService             # 创建/查询/取消/重试/删/读图
│   └── ImgGenStorageService          # work-dir 沙箱与清理
├── agent/
│   ├── ImgGenTaskScheduler           # 并发槽 + PENDING FIFO
│   ├── ImgGenTaskAsyncRunner         # @Async 触发 pipeline
│   ├── ImgGenPipeline                # 步骤编排与状态收尾
│   └── step/
│       ├── PipelineStep              # 步骤接口
│       ├── PipelineContext           # 可变上下文
│       ├── EnhanceStep               # 可选润色
│       └── GenerateStep              # 核心出图
├── port/                             # 业务边界（禁止泄漏厂商 SDK）
│   ├── ImageGenPort / Command / Result / ImageAsset
│   └── PromptEnhancePort
├── adapter/
│   ├── flux/NvidiaFluxImageAdapter   # 真出图
│   ├── mock/MockImageGenAdapter      # 假图
│   └── llm/LlmPromptEnhanceAdapter   # Chat 润色
├── config/
│   ├── ImgGenProperties              # yml 绑定
│   ├── ImgGenAsyncConfig             # 线程池 Bean
│   └── ImgGenBeanConfig              # Port 实现选择
├── event/ImgGenTaskEventPublisher    # SSE fan-out
├── entity / mapper / dto / enums / util
```

### 3.1 分层职责（看代码先认层）

| 层 | 干什么 | 不干什么 |
|----|--------|----------|
| **Controller** | 参数绑定、返回 `ApiResult`、打开 SSE | 不写业务状态机 |
| **Service** | 校验、落库、权限、media 鉴权 | 不直接调 FLUX HTTP |
| **Scheduler / AsyncRunner** | 排队、并发、异步入口 | 不解析图片 |
| **Pipeline / Step** | 状态推进、调 Port、记耗时 | 不拼各家 HTTP |
| **Port** | 稳定接口契约 | 无实现细节 |
| **Adapter** | 厂商协议 / Mock | 不写任务表状态 |
| **EventPublisher** | 把任务轻量字段推给前端 | 不推大图 base64 |

---

## 4. 端到端调用链

### 4.1 创建任务（同步部分）

```
ImgGenTaskController.create(request)
  └─ ImgGenTaskService.create(request)
       ├─ 校验 enabled / prompt 非空
       ├─ AspectRatioMapper.map(aspect) → width/height
       ├─ 校验 n、steps；可选校验润色 LLM key
       ├─ 真出图时校验 ai.providers.nvidia.api-key
       ├─ 组装 ImgGenTaskEntity → insert PENDING
       ├─ taskScheduler.notifyPending()
       ├─ eventPublisher.publishEntity(..., TYPE_CREATED)
       └─ return toResponse(entity)
```

**要点**：HTTP 立即返回 `taskId`，真正出图在后台线程。

### 4.2 调度与执行（异步部分）

```
ImgGenTaskScheduler.tryStartNext()
  ├─ 统计 DB 中 PROMPT_ENHANCING/GENERATING + 内存 active 槽
  ├─ 取 PENDING 最早任务
  └─ activeTaskIds.add(id) → asyncRunner.runAsync(id)

ImgGenTaskAsyncRunner.runAsync(taskId)   // @Async("imggenTaskExecutor")
  └─ ImgGenPipeline.run(taskId)
```

### 4.3 流水线内部

```
Pipeline.run(taskId)
  ├─ 加载任务；终态直接 return
  ├─ markRunning；清取消/暂停标记
  ├─ ensureTaskDir(data/imggen/{taskId}/)
  ├─ 组装 steps = [EnhanceStep?] + [GenerateStep]
  ├─ for step:
  │     边界检查取消/暂停
  │     updateStatus(step.runningStatus, label, progress)
  │     step.execute(ctx)
  │     写 enhanceDurationMs / generateDurationMs
  │     SSE task.status
  ├─ SUCCESS + progress=100
  └─ finally markFinished → 再 tryStartNext
```

### 4.4 读图（同步）

```
GET /tasks/{id}/media/{fileName}
  └─ ImgGenTaskService.openMedia
       ├─ requireOwnedTask（JWT userId 一致）
       ├─ 路径限制在 work-dir/{taskId}/outputs/
       └─ FileSystemResource + image/* Content-Type
```

---

## 5. 核心类与方法说明

### 5.1 `ImgGenTaskController`

| 方法 | 路径 | 说明 |
|------|------|------|
| `create` | `POST /tasks` | 提交生成 |
| `list` | `GET /tasks` | 分页列表（当前用户） |
| `get` | `GET /tasks/{id}` | 详情（含 `images[]`） |
| `media` | `GET /tasks/{id}/media/{fileName}` | 鉴权读图 |
| `cancel` / `pause` / `retry` | `POST ...` | 生命周期 |
| `delete` | `DELETE /tasks/{id}` | 删库+可选删盘 |
| `events` | `GET /events` | SSE（需 Bearer） |

前缀：`/api/v1/imggen`。

---

### 5.2 `ImgGenTaskService`（业务入口）

| 方法 | 职责 |
|------|------|
| **`create`** | 参数归一化、配额式校验（n、比例）、写 `PENDING`、触发调度 |
| **`listTasks` / `getTask`** | 用户隔离分页 / 详情 |
| **`cancelTask`** | PENDING 直接 CANCELLED；进行中 `requestCancel` 协作式取消 |
| **`pauseTask`** | 步骤边界暂停（与 aigen 同思想） |
| **`retryTask`** | 清空 work-dir 产物与结果字段，重回 PENDING |
| **`deleteTask`** | 取消进行中 + 删库 + `cleanupOnDelete` 时删目录 |
| **`openMedia`** | 防路径穿越的安全读文件 |
| **`toResponse` / `parseImages`** | Entity → DTO；从 `result_json` 解析 `images` 与 `mediaUrl` |
| **`requireOwnedTask`** | 不存在 404；非所有者 403 |

**`create` 关键字段写入：**

| 字段 | 来源 |
|------|------|
| `prompt` / `title` | 用户输入；title 截断前 40 字 |
| `aspectRatio` / `width` / `height` | options + `AspectRatioMapper` |
| `n` / `steps` / `seed` | options；steps 夹在 1～8 |
| `enhanceEnabled` | options.enhancePrompt |
| `provider` / `model` | `imggen.flux.provider-key` / `model-path` |
| `llmProvider` / `llmModel` | 仅润色开启时解析 |

---

### 5.3 `ImgGenTaskScheduler`

| 方法 | 职责 |
|------|------|
| `notifyPending` / `tryStartNext` | 有空槽则启动 PENDING |
| `markRunning` / `markFinished` | 维护 `activeTaskIds`；结束时再调度 |
| `requestCancel` / `isCancelRequested` | 协作取消标记 |
| `requestPause` / `isPauseRequested` | 协作暂停标记 |
| `recoverOrphanRunningTasks` | 应用启动：把残留进行中任务标 FAILED，避免占死并发 |

**并发上限**：`imggen.max-concurrent-tasks`（默认 2）。  
**占用计算**：`max(DB 进行中数量, 内存 active 数)`，防止进程重启后 DB 与内存不一致。

---

### 5.4 `ImgGenTaskAsyncRunner`

```java
@Async(ImgGenAsyncConfig.IMGGEN_TASK_EXECUTOR)
public void runAsync(Long taskId) {
    pipeline.run(taskId);
}
```

必须是**独立 Bean**，否则同类自调用 `@Async` 不生效（与 aigen 相同坑）。

---

### 5.5 `ImgGenPipeline`

| 方法 | 职责 |
|------|------|
| **`run(taskId)`** | 流水线主入口 |
| `shouldStopAtBoundary` | 每步前后检查取消/暂停 |
| `updateStatus` | 写状态/步骤文案/进度 + SSE |
| `markPaused` / `markCancelled` / `failTask` | 终态收尾 |
| `mergeOutputs` | 步骤产物字段合并回最新 DB 行，防并发覆盖 |

**`run` 内步骤列表构建逻辑：**

```text
if (enhanceStep.shouldRun(ctx)) steps.add(enhanceStep);
steps.add(generateStep);   // 始终执行
```

`ctx.finalPrompt` 初始 = 原始 `prompt`；润色成功后改为增强文本。

---

### 5.6 `PipelineContext`

| 字段 | 含义 |
|------|------|
| `taskId` / `task` | 当前任务 |
| `workDir` | `data/imggen/{id}` 绝对路径 |
| `finalPrompt` | 真正送给生图模型的文案 |
| `images` | 本轮生成的 `ImageAsset` 列表 |
| `cancelCheck` | 步骤内可轮询的取消/暂停谓词 |
| `pipelineStartMs` | 总耗时起点 |

---

### 5.7 `PipelineStep` 接口

```java
String name();
ImgGenTaskStatus runningStatus();  // 进入该步时的状态
String stepLabel();                // 前端 currentStep 文案
int progressPercent();             // 约略进度
void execute(PipelineContext ctx) throws Exception;
```

新增步骤：实现接口 → Pipeline 按序加入 → 可选在 Pipeline 里记耗时字段。

---

### 5.8 `ImgGenStorageService`

| 方法 | 职责 |
|------|------|
| `resolveWorkRoot` / `resolveTaskDir` | 解析配置的 work-dir |
| `ensureTaskDir` | 创建 `outputs/`、`provider/` |
| `resolveUnderTask` | 相对路径防 `..` 穿越 |
| `writeRequestSnapshot` | 写 `request.txt` 便于排障 |
| `deleteTaskDir` | 递归删除任务目录（仅 work-root 下） |

---

### 5.9 `ImgGenTaskEventPublisher`

| 常量 | 含义 |
|------|------|
| `task.created` | 新建 |
| `task.status` | 状态/进度变化 |
| `task.deleted` | 删除 |
| `connected` / `ping` | 握手与心跳 |

**序列化约定**：`SseEmitter` 的 data 用 **已 JSON 字符串 + TEXT_PLAIN**，避免双重编码（与 aigen 注释一致）。

`toLightData`：推送轻量字段；**不推图片二进制**。成功后前端再 `GET /tasks/{id}` 取 `images` 与 media URL。

---

### 5.10 `ImgGenBeanConfig`（Port 装载）

| Bean | 条件 |
|------|------|
| `PromptEnhancePort` | 固定 `LlmPromptEnhanceAdapter` |
| `ImageGenPort` | `mock-pipeline=true` 或 `steps.generate=mock` → Mock；否则 **NvidiaFluxImageAdapter** |

换供应商：新增 Adapter 实现 `ImageGenPort`，改 Bean 装配逻辑即可。

---

## 6. 流水线步骤详解

### 6.1 EnhanceStep（可选）

| 项 | 内容 |
|----|------|
| 状态 | `PROMPT_ENHANCING` |
| 进度 | ~25% |
| 何时跑 | `enhanceEnabled==1` 且 `steps.enhance` 不是 `off` 且非整管 mock 关断逻辑见 `shouldRun` |

**`execute` 逻辑：**

1. `steps.enhance=mock`：假增强字符串，测骨架。  
2. `real`：调 `PromptEnhancePort.enhance(...)`（底层 `LlmChatClient`）。  
3. 成功：写 `task.enhancedPrompt`、`ctx.finalPrompt`，可选落盘 `prompt.enhanced.txt`。  
4. 失败：若 `prompt-enhance.fallback-on-error=true` → **降级用原文** 继续；否则抛错整任务 FAILED。

**System Prompt 意图**：把用户想法扩成 **英文、单段、FLUX 友好** 的画面描述（见 `LlmPromptEnhanceAdapter`）。

---

### 6.2 GenerateStep（核心，必跑）

| 项 | 内容 |
|----|------|
| 状态 | `GENERATING` |
| 进度 | ~70% |

**`execute` 逻辑：**

1. `prompt = ctx.finalPrompt`（空则回退 `task.prompt`）。  
2. 组装 `ImageGenCommand`（宽高、steps、n、seed、workDir、outputsDir）。  
3. `imageGenPort.generate(cmd)`。  
4. 结果写入：
   - `task.resultJson`：`{ images:[{index,path,width,height,seed}], provider, model, latencyMs }`
   - `task.coverPath`：首图绝对路径  
   - `task.providerRequestId`：供应商侧 id（若有）

---

## 7. Provider 适配（FLUX / Mock / 润色）

### 7.1 端口契约

```text
ImageGenPort.generate(ImageGenCommand) → ImageGenResult
  Command: prompt, width, height, steps, n, seed, workDir, outputsDir
  Result:  List<ImageAsset>, latencyMs, requestId, rawMetaJson

ImageAsset: index, relativePath(如 outputs/img-01.png), width, height, seed
```

业务层 **只认相对路径 + 落盘文件**，不认临时 URL。

---

### 7.2 `NvidiaFluxImageAdapter`（真出图）

| 项 | 说明 |
|----|------|
| 鉴权 | `ai.providers.{imggen.flux.provider-key}.api-key` |
| URL | `imggen.flux.invoke-url`（默认 FLUX.1-schnell GenAI） |
| 请求体 | `{ prompt, width, height, seed, steps }` |
| 多图 | 循环 `n` 次，seed 递增 |
| 响应解析 | 兼容 `artifacts[0].base64` / `image` / `b64_json` / `data` 等字段 |
| 落盘 | `outputs/img-01.png` 或检测到 JPEG 则 `.jpg` |
| 排障 | 截断 raw JSON 写到 `provider/raw-XX.json` |

**重要**：这是 **`ai.api.nvidia.com/v1/genai/...`**，**不是**  
`integrate.api.nvidia.com/v1/chat/completions`。  
**不要**用现有 `LlmChatClient` 去调 FLUX。

---

### 7.3 `MockImageGenAdapter`

用 `BufferedImage` 画渐变 + 文案，写 PNG。  
用途：无 key、CI、前端联调。

---

### 7.4 `LlmPromptEnhanceAdapter`

- 依赖：`video.client.LlmChatClient`（OpenAI 兼容 Chat）。  
- 后续可换成 LangChain4j `ChatModel` / `AiServices`，**不改 Pipeline**，只换 `PromptEnhancePort` 实现。  
- **不要**用 LangChain4j `OpenAiImageModel` 硬套 NVIDIA FLUX（协议不同）。

---

### 7.5 `AspectRatioMapper`

| 产品比例 | FLUX 分辨率 |
|----------|-------------|
| `1:1` | 1024×1024 |
| `16:9` | 1344×768 |
| `9:16` | 768×1344 |
| `4:3` / `3:4` | 1152×896 / 896×1152 |

非法比例 → 创建任务时 400。

---

## 8. 状态机与并发调度

### 8.1 状态枚举 `ImgGenTaskStatus`

```
PENDING
  → PROMPT_ENHANCING   （可选）
  → GENERATING
  → SUCCESS | FAILED | CANCELLED | PAUSED
```

| 状态 | 含义 |
|------|------|
| PENDING | 已入库，等槽位 |
| PROMPT_ENHANCING | LLM 润色中 |
| GENERATING | 调 FLUX / Mock 出图 |
| SUCCESS | 有结果（通常有 cover） |
| FAILED | `errorMessage` 有原因 |
| CANCELLED | 用户取消 |
| PAUSED | 步骤边界暂停，可 retry |

`isRunning()` = 润色中或出图中。  
`isTerminal()` = 成功/失败/取消/暂停。

### 8.2 取消 / 暂停语义

- **PENDING**：Service 直接改终态。  
- **进行中**：Scheduler 打标记 → Pipeline 在**步骤边界**检查 → 中断。  
- **不会**中途强杀 OkHttp 调用（当前未做 cancel 传递到 HTTP）。

### 8.3 重试语义

`retryTask`：

1. 仅 FAILED / CANCELLED / PAUSED / SUCCESS。  
2. 删除任务目录旧图。  
3. 清空 `resultJson` / `coverPath` / 耗时 / enhanced 等。  
4. 回到 PENDING，再调度。

---

## 9. 数据模型与落盘

### 9.1 表 `imggen_task`

见 `doc/sql/imggen_task.sql` / `resources/db/schema.sql`。  
实体：`ImgGenTaskEntity`（MyBatis-Plus，`IdType.ASSIGN_ID` 雪花）。

关键列：

| 列 | 说明 |
|----|------|
| `prompt` / `enhanced_prompt` | 原文 / 润色 |
| `status` / `current_step` / `progress` | 进度展示 |
| `width` / `height` / `n` / `seed` / `steps` | 生图参数 |
| `result_json` | 多图元数据 |
| `cover_path` | 首图绝对路径 |
| `enhance_duration_ms` / `generate_duration_ms` / `total_duration_ms` | 可观测 |

### 9.2 磁盘布局

```
{imggen.work-dir}/{taskId}/
  request.txt
  prompt.enhanced.txt          # 可选
  outputs/
    img-01.png | img-01.jpg
    img-02.png ...
  provider/
    raw-01.json                # 截断后的供应商响应
```

默认 `work-dir`：`./data/imggen`（相对 okx-bot 运行目录）。

### 9.3 `result_json` 示例

```json
{
  "images": [
    {
      "index": 1,
      "path": "outputs/img-01.png",
      "width": 1024,
      "height": 1024,
      "seed": 42
    }
  ],
  "provider": "nvidia",
  "model": "black-forest-labs/flux.1-schnell",
  "latencyMs": 12345
}
```

响应 DTO 中会补 `mediaUrl`：  
`/api/v1/imggen/tasks/{id}/media/img-01.png`。

---

## 10. REST API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/imggen/models` | 生图模型目录（yml） |
| POST | `/api/v1/imggen/tasks` | 创建 |
| GET | `/api/v1/imggen/tasks?page&size&status` | 列表 |
| GET | `/api/v1/imggen/tasks/{id}` | 详情 |
| GET | `/api/v1/imggen/tasks/{id}/media/{file}` | 读图 |
| POST | `/api/v1/imggen/tasks/{id}/cancel` | 取消 |
| POST | `/api/v1/imggen/tasks/{id}/pause` | 暂停 |
| POST | `/api/v1/imggen/tasks/{id}/retry` | 重试/再生成 |
| DELETE | `/api/v1/imggen/tasks/{id}` | 删除 |
| GET | `/api/v1/imggen/events` | SSE |

### 创建 body 示例

```json
{
  "prompt": "赛博朋克东京雨夜街道",
  "negativePrompt": "blurry, watermark",
  "options": {
    "aspectRatio": "16:9",
    "n": 1,
    "seed": null,
    "imageModel": "black-forest-labs/flux.1-schnell",
    "enhancePrompt": true,
    "llmProvider": "nvidia",
    "llmModel": "meta/llama-3.1-8b-instruct"
  }
}
```

| 模型类型 | 配置来源 | 前端选择 | 任务字段 |
|----------|----------|----------|----------|
| **生图** | `ai_model_config`（`capability=image`）+ `GET /imggen/models` | 必选；管理页可 CRUD | `provider` + `model` |
| **润色 Chat** | `ai_model_config`（`capability=chat`）+ `GET /video/models` | 开启润色时必选 | `llm_provider` + `llm_model` |

生图模型库字段：`invoke_url`、`default_steps`、`max_steps`、`protocol`；  
迁移脚本：`sql/ai_model_config_capability_alter.sql`、`sql/ai_model_config_image_protocol_qwen.sql`。

### 生图协议（protocol）

| protocol | Adapter | 典型模型 |
|----------|---------|----------|
| `nvidia-flux` | `NvidiaFluxImageAdapter` | FLUX 云端 `.../v1/genai/black-forest-labs/...` |
| `nvidia-qwen` | `NvidiaQwenImageAdapter` → **POST /v1/images/generations** | Qwen-Image（官方 OpenAPI） |
| `nvidia-openai-images` | 同上 | 显式 OpenAI Images |
| `nvidia-qwen-infer` | 同上 → **POST /v1/infer** | 自托管 NIM 原生体 |
| （空） | 按 modelId/url 推断（含 `qwen` → qwen） | — |

路由入口：`CompositeImageGenPort`。

**重要**：Qwen 官方文档 [qwen-image.html](https://docs.nvidia.com/nim/visual-genai/latest/api/qwen-image.html) 面向 **Visual GenAI NIM 自托管**，路径是 `/v1/images/generations` 与 `/v1/infer`，**不是** FLUX 的 `ai.api.nvidia.com/v1/genai/qwen/...`（后者会纯文本 `404 page not found`）。  
本地示例：`http://127.0.0.1:8000/v1/images/generations`，先 `docker/NIM` 部署 qwen-image。

鉴权：与其它 API 一样，JWT Bearer（除登录白名单外）。

---

## 11. 配置项

`application.yml` 片段：

```yaml
imggen:
  enabled: true
  work-dir: ./data/imggen
  max-concurrent-tasks: 2
  mock-pipeline: false
  mock-step-delay-ms: 400
  cleanup-on-delete: true
  max-n: 4
  steps:
    enhance: real     # off | real | mock
    generate: real    # real | mock
  prompt-enhance:
    fallback-on-error: true
    provider:         # 空 → ai.default-provider
    model: ""
  flux:
    provider-key: nvidia
    invoke-url: https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell
    model-path: black-forest-labs/flux.1-schnell
    default-steps: 4
    timeout-seconds: 120
```

| 场景 | 推荐配置 |
|------|----------|
| 正常真出图 | `generate: real`，nvidia api-key 已配 |
| 无额度联调前端 | `generate: mock` 或 `mock-pipeline: true` |
| 关闭润色能力 | `enhance: off`（用户勾选也不跑） |
| 润色失败仍出图 | `fallback-on-error: true`（默认） |

密钥：`ai.providers.nvidia.api-key`（与 Chat 共用）。

---

## 12. 扩展指南

### 12.1 接入第二个生图供应商

1. 新建 `adapter/xxx/XxxImageAdapter implements ImageGenPort`。  
2. 在 `ImgGenBeanConfig` 按配置选择实现（或工厂按 task.provider 选）。  
3. **禁止**在 `GenerateStep` / `Service` 里写 HTTP。  
4. 保持输出：写到 `outputs/`，返回 `ImageAsset.relativePath`。

### 12.2 润色改用 LangChain4j

1. 引入 `langchain4j` + open-ai 兼容模块。  
2. 新实现 `PromptEnhancePort`（ChatModel / AiServices）。  
3. `ImgGenBeanConfig` 注入新实现。  
4. Pipeline / Controller 零改动。

### 12.3 给 aigen 分镜配图复用

1. 将 `ImageGenPort` 提到公共包或让 aigen 依赖同一 Bean。  
2. aigen `AssetStep` 调 `generate`，路径写到 `data/aigen/{id}/assets/images/`。  
3. **不要**把 aigen 任务塞进 `imggen_task` 表。

### 12.4 增加 img2img

1. `ImageGenCommand` 增加 `referenceImage` / `strength`。  
2. Adapter 按厂商协议扩展 body。  
3. Service create 支持 multipart 上传参考图到 task 目录。

---

## 13. 排障清单

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 创建 400「NVIDIA API Key 未配置」 | yml 无 key 且 generate=real | 配 `ai.providers.nvidia.api-key` 或改 mock |
| 任务一直 PENDING | 并发占满 / 调度未触发 | 查 `max-concurrent-tasks`；重启会 `recoverOrphan` |
| FAILED HTTP 502 + body | FLUX 额度/审核/参数 | 看 `errorMessage` 与 `provider/raw-*.json` |
| 无法解析 base64 | 响应结构变更 | 改 `extractImageBytes` 兼容字段 |
| 润色了但图仍像中文 prompt | enhance 失败且 fallback | 看日志「降级使用原文」；检查 chat 模型 |
| 前端有 SUCCESS 无图 | media 403/404 或路径错 | 查 `cover_path`、`result_json`、user 隔离 |
| SSE 不更新 | 断线 | 前端会 poll；后端看 emitter 是否连上 |
| 编译 OK 启动表不存在 | 未执行 SQL | 跑 `doc/sql/imggen_task.sql` |

### 日志关键字

```
创建 imggen 任务
调度 imggen 任务
异步启动 imggen 任务
NVIDIA FLUX 请求
文生图完成
imggen 流水线失败
```

### 开发自测最小路径

1. 执行建表 SQL。  
2. `imggen.steps.generate: mock` 启动。  
3. `POST /api/v1/imggen/tasks` 带 prompt。  
4. 轮询或 SSE 到 SUCCESS。  
5. `GET .../media/img-01.png` 能打开。  
6. 再改 `generate: real` 验真 FLUX。

---

## 附录 A：类速查表

| 类 | 一句话 |
|----|--------|
| `ImgGenTaskController` | HTTP 门面 |
| `ImgGenTaskService` | 任务 CRUD + 鉴权 media |
| `ImgGenStorageService` | 目录沙箱 |
| `ImgGenTaskScheduler` | 并发与排队 |
| `ImgGenTaskAsyncRunner` | 异步触发 |
| `ImgGenPipeline` | 步骤状态机 |
| `EnhanceStep` | 可选润色 |
| `GenerateStep` | 出图编排 |
| `ImageGenPort` | 生图抽象 |
| `NvidiaFluxImageAdapter` | NVIDIA FLUX |
| `MockImageGenAdapter` | 假图 |
| `PromptEnhancePort` | 润色抽象 |
| `LlmPromptEnhanceAdapter` | Chat 润色 |
| `ImgGenTaskEventPublisher` | SSE |
| `ImgGenProperties` | 配置 |
| `ImgGenBeanConfig` | Port 装配 |
| `AspectRatioMapper` | 比例→像素 |

## 附录 B：相关文档

| 文档 | 用途 |
|------|------|
| `AI文生图_架构设计方案.md` | 产品/架构决策、与市面对照 |
| `AI视频生成_架构设计方案.md` | aigen 同构参考 |
| `VideoCoreExtractor_核心逻辑与后端实现文档.md` | video 流水线参考 |
| `sql/imggen_task.sql` | 建表 |

---

## 变更记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-14 | 初版：对齐当前 imggen 实现的开发手册 |
