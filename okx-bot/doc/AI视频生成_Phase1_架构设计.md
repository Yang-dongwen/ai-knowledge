# AI 视频生成 — Phase 1 架构设计

**目标**：在 Phase 0 骨架上，打通 **「提示词 → 真实可播放 MP4」** 最小闭环  
**原则**：可扩展 · 健壮 · 安全  
**前置**：Phase 0 已交付（任务表 / 调度 / SSE / 前端壳 / mock 流水线）  
**版本**：v1.0  
**日期**：2026-07-13  
**相关文档**：
- 极简认知：`AI视频生成_极简说明.md`
- 总体方案：`AI视频生成_架构设计方案.md`（v1.2）
- Phase 0 使用：`AI视频生成_Phase0_使用说明.md`

---

## 0. 一句话与成功标准

```
用户提示词
  → LangChain4j 产出合法 Storyboard
  → TTS 生成分镜音频
  → Remotion 渲染 MP4
  → 前端在线播放 / 下载
```

| 验收项 | 标准 |
|--------|------|
| 端到端 | 登录用户提交提示词，数分钟内网页可播 MP4 |
| 状态 | 仍走 PENDING→PLANNING→ASSET→RENDER→SUCCESS，SSE 实时 |
| 失败 | 任一步失败 → FAILED + 可读 errorMessage，支持 retry |
| 安全 | 仅本人任务可看/播/删；素材路径不可逃逸 workDir |
| 扩展 | 换 TTS / 换模板 / 关 mock 不改 Pipeline 主骨架 |

**Phase 1 明确不做**：分镜编辑器、AI 配图、多模板全量、Redis 队列、对象存储、计费配额。

---

## 1. 设计原则（强制）

### 1.1 可扩展（Open for extension）

| 原则 | 做法 |
|------|------|
| 端口与适配器 | Pipeline 只依赖接口：`ScriptPlanPort` / `TtsPort` / `VideoRenderPort` / `ImagePort` |
| 步骤插件化 | 每步实现 `PipelineStep`，可单独测、单独换 |
| 模板注册表 | 模板元数据 + schema + compositionId 可配置；新增模板尽量不改 Java 核心 |
| 配置驱动 | `aigen.mock-pipeline`、`tts.provider`、`remotion.base-url` 可切换实现 |
| 防腐层 | 业务 DTO 不泄漏 `dev.langchain4j.*`、不泄漏 Remotion 细节到 Controller |

### 1.2 健壮（Fail loud, recover where safe）

| 原则 | 做法 |
|------|------|
| 分步超时 | PLAN / ASSET / RENDER 各自 timeout，避免整任务挂死 |
| 可重试边界 | 网络/5xx 可重试；校验失败可 repair 一次；渲染失败可整步 retry |
| 协作取消 | 步骤边界检查 cancel；长 IO 尽量可中断 |
| 幂等落盘 | storyboard / audio / output 路径约定固定；重试清理或覆盖策略明确 |
| 观测 | 分步耗时已有字段；日志带 taskId；禁止默认打印完整 prompt/密钥 |
| 降级 | LLM 结构化失败 → plain JSON + repair；TTS 失败可选 mock 静音（**仅 dev**） |

### 1.3 安全（Trust nothing from LLM/user）

| 风险 | 控制 |
|------|------|
| 越权 | 所有 task/media API 校验 `userId`（延续 Phase 0） |
| 路径穿越 | 仅允许相对 `assets/**`；canonicalize 必须在 `workDir` 内 |
| Prompt 注入 | System 约束 scene 白名单；**本地 Schema 校验是最终裁判** |
| SSRF | Phase 1 禁止 storyboard 外链图片/音频 URL |
| 资源耗尽 | 时长 5–180s、prompt 长度、并发槽位、渲染超时 |
| 密钥 | 仅 yml/环境变量；媒体接口鉴权，禁止裸路径暴露 |
| 内容 | 成品仅登录用户可访问；删除同步清文件 |

---

## 2. 总体架构（Phase 1）

```
┌──────────────────────┐
│  okx-trading-web     │  提交 / SSE / 播放器 / 下载
│  /video-generate     │
└──────────┬───────────┘
           │ JWT + REST/SSE
┌──────────▼───────────────────────────────────────────────┐
│  okx-bot  com.dwcode.okxbot.aigen                         │
│                                                           │
│  Controller → AigenTaskService → Scheduler → AigenPipeline│
│       │                              │                    │
│       │         ┌────────────────────┼────────────────┐   │
│       │         ▼                    ▼                ▼   │
│       │   ScriptPlanPort      AssetPipeline     VideoRenderPort
│       │   (LangChain4j)       (TtsPort …)       (HTTP→Remotion)
│       │         │                    │                │   │
│       │         └──────────┬─────────┴────────────────┘   │
│       │                    ▼                              │
│       │           StoryboardValidateService               │
│       │           AigenStorageService                     │
└───────┴────────────────────┬──────────────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
           MySQL        data/aigen/    aigen-remotion
         aigen_task     {taskId}/      (Node HTTP :3100)
```

### 2.1 进程与职责

| 进程 | 职责 | Phase 1 要求 |
|------|------|----------------|
| Java okx-bot | 编排、鉴权、LLM、TTS 调用、落盘 | 必跑 |
| Node aigen-remotion | Composition + 渲染 MP4 | 必跑（本机或 Docker） |
| Vue | UI | 必跑 |
| 云端 LLM | 分镜 | 复用 `ai.providers` |

### 2.2 配置开关

```yaml
aigen:
  mock-pipeline: false          # Phase 1 生产路径关 mock
  # 逐步灰度：也可 per-step mock（见 §5）
  steps:
    plan: real                  # real | mock
    asset: real
    render: real
  max-concurrent-tasks: 1
  work-dir: ./data/aigen
  llm: { ... }                  # 已有
  tts:
    provider: edge              # edge | mock
    default-voice: zh-CN-XiaoxiaoNeural
    timeout-seconds: 120
  remotion:
    base-url: http://127.0.0.1:3100
    timeout-seconds: 600
    shared-work-dir: ./data/aigen   # 与 Java work-dir 同一物理路径
```

**说明**：保留 `mock-pipeline=true` 时整链路 mock（回归 Phase 0）。  
`steps.*.real|mock` 支持只 mock 某一步（开发期极有用）。

---

## 3. 核心契约：Storyboard v1

Phase 1 **唯一中间态**。LLM 产出、TTS 回填路径、Remotion `inputProps` 共用此结构。

### 3.1 JSON 形态（精简版）

```json
{
  "version": "1.0",
  "meta": {
    "title": "比特币减半",
    "language": "zh",
    "templateId": "knowledge-cards",
    "fps": 30,
    "width": 1080,
    "height": 1920,
    "durationInFrames": 900
  },
  "style": {
    "theme": "tech-dark",
    "primaryColor": "#6366F1"
  },
  "scenes": [
    {
      "id": "s1",
      "type": "title",
      "startFrame": 0,
      "durationInFrames": 90,
      "narration": "你知道比特币为什么每四年减半吗？",
      "props": {
        "title": "比特币减半",
        "subtitle": "四分钟读懂"
      }
    },
    {
      "id": "s2",
      "type": "bullets",
      "startFrame": 90,
      "durationInFrames": 180,
      "narration": "第一，供应减半……",
      "props": {
        "heading": "三个要点",
        "items": ["供应减半", "历史行情", "下次时间"]
      }
    },
    {
      "id": "s3",
      "type": "outro",
      "startFrame": 270,
      "durationInFrames": 60,
      "narration": "关注获取更多科普。",
      "props": {
        "title": "感谢观看",
        "cta": "点赞收藏"
      }
    }
  ],
  "audio": {
    "voiceId": "zh-CN-XiaoxiaoNeural",
    "tracks": [
      {
        "sceneId": "s1",
        "src": "assets/audio/s1.mp3",
        "durationMs": 3200
      }
    ]
  },
  "subtitles": [
    { "startMs": 0, "endMs": 3200, "text": "你知道比特币为什么每四年减半吗？" }
  ]
}
```

### 3.2 校验规则（`StoryboardValidateService`）

| 规则 | 说明 |
|------|------|
| version | 必须 `1.0` |
| templateId | 与任务一致，且在白名单 |
| scene.type | ∈ 模板声明集合（knowledge-cards: `title`/`bullets`/`outro`） |
| scene.id | 非空、任务内唯一 |
| frames | start/duration ≥ 0；总时长 ≈ targetDuration（允许 ±15% 或规范化重算） |
| resolution | 与 aspectRatio 映射一致 |
| narration | 非空（TTS 依赖） |
| paths | `src` 仅相对路径、禁止 `..`、禁止 `http(s):` |
| 条数 | scenes 2～12（防爆炸） |

**规范化（normalize）**：校验通过后统一重算 `startFrame` 连续铺开、对齐 fps、补 `durationInFrames`，避免 LLM 帧数乱七八糟导致渲染花屏。

### 3.3 路径安全

```text
resolveAsset(workDir, relativeSrc):
  base = workDir.normalize().toAbsolutePath()
  target = base.resolve(relativeSrc).normalize().toAbsolutePath()
  if (!target.startsWith(base)) throw SecurityException
  // 仅允许 base/assets/ 下
  if (!target.startsWith(base.resolve("assets"))) throw SecurityException
  return target
```

---

## 4. 流水线重构设计

### 4.1 从「大 if mock」到「步骤链」

```
AigenPipeline.run(taskId):
  ctx = PipelineContext.load(taskId)
  for step in steps:   # PlanStep → AssetStep → RenderStep
    if cancelled: mark CANCELLED; return
    updateStatus(step.status, step.label, progress)
    try:
      step.execute(ctx)           # 内部超时
      persist(ctx)
      publish SSE
    catch:
      FAILED + message; return
  SUCCESS
```

```java
public interface PipelineStep {
    String name();
    AigenTaskStatus runningStatus();
    int progressStart();
    void execute(PipelineContext ctx) throws Exception;
}
```

| 步骤 | 类 | 端口 |
|------|-----|------|
| Plan | `PlanStep` | `ScriptPlanPort` |
| Asset | `AssetStep` | `TtsPort`（Phase 1 仅 TTS；ImagePort 预留 no-op） |
| Render | `RenderStep` | `VideoRenderPort` |

`mock-pipeline=true` → 注入 `MockPlanStep` 等或 `Mock*` Port 实现。  
**不要**再在 Pipeline 里堆一大段 if-else。

### 4.2 PipelineContext

```text
taskId, entity, workDir, storyboard (mutable),
cancelCheck: BooleanSupplier,
clock / metrics
```

### 4.3 取消与超时

| 机制 | 实现 |
|------|------|
| 取消 | 每步开始/内部循环调用 `scheduler.isCancelRequested` |
| 步骤超时 | `CompletableFuture.orTimeout` 或守护计时；超时 → FAILED「某步超时」 |
| 渲染超时 | HTTP client readTimeout = remotion.timeout-seconds |

### 4.4 重试策略

| 步骤 | 自动重试 | 用户 retry |
|------|----------|------------|
| Plan | LLM 429/503：模型层 maxRetries；JSON 非法：repair 1 次 | 整任务重跑 Plan 起 |
| Asset | 单 scene TTS 失败重试 1 次 | 可只重跑 Asset+Render（Phase 1.1 可选；P1 整任务重跑即可） |
| Render | HTTP 5xx 重试 1 次 | 整任务或从 Render（预留） |

Phase 1 用户侧 **retry = 清空中间产物后从 PENDING 再跑**（与 Phase 0 一致，实现简单稳健）。

---

## 5. 端口与实现（扩展点）

### 5.1 ScriptPlanPort

```java
public interface ScriptPlanPort {
    StoryboardDto plan(PlanCommand cmd);
}
```

| 实现 | 说明 |
|------|------|
| `LangChain4jScriptPlanAdapter` | **默认 real** |
| `MockScriptPlanAdapter` | mock / 单测 |

**LangChain4j 设计要点**（对齐总方案 §6，此处落地约束）：

1. `ChatModelFactory` 读 `AiProperties` + 任务级 provider/model。  
2. `AiServices` → `StoryboardDto`；失败则 plain 文本清洗 + Jackson。  
3. `StoryboardRepairer` 最多 1 次。  
4. **禁止** Pipeline 直接依赖 langchain4j 类型。  
5. Prompt 模板放 `classpath:aigen/prompts/plan-system.txt` + 模板 few-shot `classpath:aigen/templates/knowledge-cards/example.json`。

**模型选择 Phase 1**：

- 优先任务 options / 默认 `aigen.llm`  
- 未配置 model 时：可复用 `AiModelConfigService.firstEnabledModelId`（与提取模块一致）  
- 无可用 key → 创建任务时即可拒绝（同步校验，体验更好）

### 5.2 TtsPort

```java
public interface TtsPort {
    /** 返回相对 workDir 的 src 与 durationMs */
    TtsResult synthesize(TtsCommand cmd);
}
```

| 实现 | 说明 |
|------|------|
| `EdgeTtsProvider` | **推荐默认**：edge-tts CLI 或等价 HTTP；中文音色 |
| `MockTtsProvider` | 生成静音/短 beep wav/mp3 + 按字数估 duration（无外网环境） |

**AssetStep 流程**：

```
for scene in storyboard.scenes:
  if blank(narration): skip or fail
  out = assets/audio/{sceneId}.mp3
  if exists && hash(narration+voice) match: reuse
  else tts.synthesize → write out
  fill audio.tracks[]
rebuild subtitles from tracks (startMs 累加)
persist storyboard.json
```

**健壮性**：单 scene 失败重试 1 次；仍失败整步 FAILED。  
**安全**：输出路径经 `resolveAsset`。

### 5.3 VideoRenderPort

```java
public interface VideoRenderPort {
    RenderResult render(RenderCommand cmd);
}
```

| 实现 | 说明 |
|------|------|
| `RemotionHttpRenderAdapter` | **默认**：POST `{remotion.base-url}/render` |
| `MockRenderAdapter` | 写假文件（仅 mock） |

**请求契约**：

```json
POST /render
{
  "jobId": "2076...",
  "compositionId": "KnowledgeCards",
  "inputProps": { /* storyboard */ },
  "workDir": "/abs/path/data/aigen/2076...",
  "outputFile": "output.mp4",
  "codec": "h264",
  "crf": 18
}
```

**响应**：

```json
{
  "success": true,
  "outputFile": "/abs/.../output.mp4",
  "durationMs": 45000,
  "error": null
}
```

**关键约束**：

- Java 与 Node **共享同一 workDir 物理路径**（本机绝对路径或 Docker volume）。  
- Remotion 只读 `workDir` 下 assets，**禁止**再请求外网。  
- 渲染服务无业务鉴权时，**仅绑定 127.0.0.1**（Phase 1）；上公网必须加 token / 内网。

### 5.4 ImagePort（预留）

```java
public interface ImagePort {
    Optional<String> generate(ImageCommand cmd); // 相对路径
}
```

Phase 1：`NoOpImagePort` 或模板内置渐变背景，不调 AI 绘图。

---

## 6. aigen-remotion 工程设计

### 6.1 目录（新建于仓库根）

```
aigen-remotion/
├── package.json
├── remotion.config.ts
├── src/
│   ├── index.ts
│   ├── Root.tsx
│   ├── templates/
│   │   └── KnowledgeCards/
│   │       ├── index.tsx
│   │       ├── types.ts
│   │       └── scenes/
│   ├── components/
│   │   ├── Subtitles.tsx
│   │   └── SafeAudio.tsx
│   └── lib/
│       └── storyboard.ts          # 运行时轻量校验
├── public/
│   └── fonts/NotoSansSC-*.otf     # 中文字体打包
├── server/
│   ├── index.ts                   # Fastify/Express
│   ├── render.ts                  # @remotion/renderer
│   └── queue.ts                    # 并发=1
├── Dockerfile
└── README.md
```

### 6.2 Composition 约定

| templateId | compositionId | 画幅默认 |
|------------|---------------|----------|
| knowledge-cards | KnowledgeCards | 9:16 1080×1920 |

`calculateMetadata` 从 props.meta 读 duration/width/height/fps。

### 6.3 渲染并发与资源

- `MAX_CONCURRENT_RENDERS=1`  
- 队列满返回 429，Java 侧失败信息友好  
- Docker 需 `shm_size` ≥ 1gb（后续）

### 6.4 安全

- 监听 `127.0.0.1:3100`  
- 校验 `workDir` 在允许根目录前缀下（环境变量 `ALLOWED_WORK_ROOT`）  
- 拒绝 absolute 输出路径跳出 root  
- 请求体大小限制（storyboard JSON）

---

## 7. 后端包结构（Phase 1 增量）

在现有 `aigen` 上扩展，不推翻 Phase 0：

```
aigen/
├── agent/
│   ├── AigenPipeline.java          # 改为步骤链
│   ├── AigenTaskScheduler.java
│   ├── AigenTaskAsyncRunner.java
│   └── step/
│       ├── PipelineStep.java
│       ├── PipelineContext.java
│       ├── PlanStep.java
│       ├── AssetStep.java
│       ├── RenderStep.java
│       └── mock/Mock*Step.java     # 或 Port mock
├── port/                           # 对外端口
│   ├── ScriptPlanPort.java
│   ├── TtsPort.java
│   ├── VideoRenderPort.java
│   └── ImagePort.java
├── adapter/
│   ├── llm/
│   │   ├── ChatModelFactory.java
│   │   ├── LangChain4jScriptPlanAdapter.java
│   │   ├── ScriptPlanner.java      # AiServices 接口
│   │   └── StoryboardRepairer.java
│   ├── tts/
│   │   ├── EdgeTtsProvider.java
│   │   └── MockTtsProvider.java
│   └── render/
│       ├── RemotionHttpRenderAdapter.java
│       └── MockRenderAdapter.java
├── domain/                         # 领域模型
│   ├── StoryboardDto.java
│   ├── SceneDto.java
│   └── ...
├── service/
│   ├── AigenTaskService.java       # + 创建时校验 LLM 可用
│   ├── AigenStorageService.java    # + resolveAsset 安全解析
│   ├── StoryboardValidateService.java
│   ├── StoryboardNormalizeService.java
│   └── TemplateRegistry.java       # 模板白名单 + schema
├── config/
│   ├── AigenProperties.java        # 扩展 steps/tts
│   └── AigenBeanConfig.java        # 按配置装配 Port 实现
└── controller/
    └── AigenTaskController.java    # + media 流
```

**依赖**：`pom.xml` 增加 `langchain4j` BOM + `langchain4j-open-ai`（版本实现时锁定）。

---

## 8. API 增量（相对 Phase 0）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/tasks/{id}/media/output` | 鉴权后流式 MP4（`inline` 播放） |
| GET | `/tasks/{id}/media/poster` | 可选封面（P1 可后置） |
| GET | `/tasks/{id}/storyboard` | 已有；SUCCESS 后含 audio 路径 |

创建任务时同步校验：

- prompt / template / duration（已有）  
- **新增**：`mock-pipeline=false` 时检查 LLM provider 有 api-key；Remotion `/health` 可选预检（失败仅 warn 或 soft-fail 配置）

---

## 9. 前端增量

| 改动 | 说明 |
|------|------|
| 播放器 | SUCCESS 且 `outputAvailable` 时 `<video :src="mediaUrl">` |
| mediaUrl | `/api/v1/aigen/tasks/{id}/media/output` + 带 Token 的 blob 方案 **或** 短期 query token |
| 下载 | 同 URL `download` |
| 文案 | 去掉「Phase 0 无成片」；失败展示 errorMessage |
| 步骤条 | 保持；真实进度仍靠后端 progress |

**播放鉴权推荐（安全）**：

1. **优先**：`fetch` + `Authorization` → `blob:` URL 赋给 video（与 SSE 一致，不把 JWT 塞进 query）。  
2. 备选：一次性 `playToken` 短 TTL（实现成本高，P1.1）。

禁止：静态目录不经鉴权直接暴露 `data/aigen`。

---

## 10. 安全设计专章

### 10.1 认证与授权

- 所有 `/api/v1/aigen/**` 需登录（现有 Security 已覆盖）。  
- `requireOwnedTask` 贯穿 media / storyboard / delete。  

### 10.2 输入限制

| 字段 | 限制 |
|------|------|
| prompt | 1～4000 字符；可拒纯空白 |
| targetDurationSec | 5～90（Phase 1 收紧到 90，降低渲染成本） |
| scenes | 校验后 ≤ 12 |
| 单 scene narration | ≤ 500 字 |

### 10.3 LLM 输出不信任

```
LLM JSON → Jackson → Validate → Normalize → 仅使用白名单字段
```

丢弃未知字段；scene.type 非法直接失败或 repair，**不**映射到任意 React 组件名。

### 10.4 渲染服务

- 本机 loopback  
- `ALLOWED_WORK_ROOT`  
- 可选共享密钥头 `X-Aigen-Render-Token`（yml 配置，Java/Node 一致）

### 10.5 日志红线

- 默认不打完整 prompt / storyboard 全文（DEBUG 开关）  
- 不打 api-key、Authorization  

---

## 11. 健壮性与故障模型

| 故障 | 用户可见 | 系统行为 |
|------|----------|----------|
| LLM 全挂 | FAILED：规划失败 | 重试耗尽后失败 |
| JSON 非法 | FAILED 或 repair 后成功 | repair 1 次 |
| TTS 失败 | FAILED：素材失败 | 单 scene 重试 |
| Remotion 宕机 | FAILED：渲染服务不可用 | 健康检查日志 |
| 渲染 OOM | FAILED：渲染失败 | stderr 截断入库 |
| 用户取消 | CANCELLED | 步骤边界退出 |
| 磁盘满 | FAILED | IOException 包装 |

**工作目录约定**：

```
data/aigen/{taskId}/
  request.json
  storyboard.json
  storyboard.plan.json      # LLM 初稿（调试）
  assets/audio/*.mp3
  logs/plan.log | render.log
  output.mp4
  poster.jpg                # 可选
```

重试：删除 `assets/audio`、`output.mp4`、重置 storyboard 音频段（或整目录按 Phase 0 deleteTaskDir 子集清理）。

---

## 12. 实现分期（开发顺序）

建议 **按垂直切片提交**，每步可独立验证：

### Slice A — 契约与校验（无外网）

1. `StoryboardDto` + Validate + Normalize  
2. `PipelineStep` 重构；mock 走 Port  
3. 单测：非法路径 / 非法 type / 帧规范化  

### Slice B — LLM 规划

1. LangChain4j 依赖 + `ChatModelFactory`  
2. `LangChain4jScriptPlanAdapter` + repair  
3. 创建任务校验 api-key  
4. 手工：mock-pipeline=false 且仅 plan=real 时落 storyboard  

### Slice C — TTS

1. `EdgeTtsProvider` + `MockTtsProvider`  
2. `AssetStep` 写音频、回填 tracks/subtitles  
3. Windows 文档：edge-tts 安装方式  

### Slice D — Remotion

1. 初始化 `aigen-remotion` + KnowledgeCards  
2. `server` `/health` `/render`  
3. `RemotionHttpRenderAdapter`  
4. 联调出片  

### Slice E — 前端播放

1. blob 播放 / 下载  
2. 成功态 UI  
3. Phase0 文案清理  

### Slice F — 硬化

1. 渲染 token、路径根校验  
2. 超时与错误文案  
3. README / Phase1 使用说明  

**预估**：熟悉环境下约 1～1.5 周；Remotion 环境问题通常占最多时间。

---

## 13. 测试策略

| 层级 | 内容 |
|------|------|
| 单元 | Validate、path resolve、normalize、prompt 清洗 |
| 组件 | PlanAdapter mock ChatModel；AssetStep mock TtsPort |
| 集成 | mock-pipeline 回归；可选 Testcontainers 不做 Phase1 强制 |
| 手工 | 真 LLM + 真 TTS + 真 Remotion 一条黄金路径 |

---

## 14. 风险与决策记录

| 决策 | 选择 | 原因 |
|------|------|------|
| 中间态 | 强 Schema Storyboard | 安全、可测、可重渲 |
| LLM 框架 | LangChain4j + 防腐 Port | 结构化输出；可替换 |
| TTS | **Phase 1 仅 Mock**（语音后补）；接口预留 Edge | 先出片；后续换 TtsPort 实现 |
| 渲染 | 独立 Node 服务 | 与 Vue 解耦；可扩缩 |
| 播放鉴权 | fetch+blob | 不把 JWT 放 URL |
| 时长上限 P1 | 90s | 控渲染成本 |
| 用户 retry | 全量重跑 | 实现简单、状态清晰 |

| 风险 | 缓解 |
|------|------|
| NVIDIA JSON 不稳定 | plain + repair；可换模型 |
| Windows edge-tts 环境 | MockTts + 文档；或 Python 脚本封装 |
| 中文字体 | 字体打进 remotion public |
| 共享路径不一致 | 配置 `shared-work-dir` 绝对路径；启动自检 |

---

## 15. Phase 1 完成定义（DoD）

- [ ] `mock-pipeline=false` 下黄金路径可出片  
- [ ] 前端可播放、可下载  
- [ ] 失败可 retry，取消可用  
- [ ] 越权访问 media 返回 403  
- [ ] 路径穿越单测通过  
- [ ] Port 可切换 mock/real  
- [ ] `aigen-remotion/README` + `Phase1 使用说明`  
- [ ] 默认日志无密钥、无全量 prompt  

---

## 16. 与 Phase 0 / 总方案关系

| 文档 | 角色 |
|------|------|
| 总方案 | 长期蓝图（多模板、配图、队列…） |
| **本文** | **Phase 1 实施规格**（以本文为准开发） |
| Phase 0 说明 | 骨架行为；P1 后 mock 仅作回归开关 |

冲突时：**Phase 1 以本文为准**；总方案作扩展参考。

---

## 17. 建议开工命令（实现阶段）

开发默认：

```yaml
aigen:
  mock-pipeline: false
  steps:
    plan: real
    asset: real   # 无 edge-tts 时先 mock
    render: real
```

联调顺序：`plan` 单独 real → `asset` → `render` → 前端播放。

---

**下一步**：确认本文决策后，按 **Slice A → F** 开工实现。  
若需调整（例如 P1 暂不做真实 TTS、先静音出画），在开工前改 §14 决策表即可。
