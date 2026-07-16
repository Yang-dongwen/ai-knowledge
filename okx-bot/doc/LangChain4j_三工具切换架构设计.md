# LangChain4j 三工具切换架构设计

**范围**：视频提取（`video`）· AI 视频生成（`aigen`）· AI 文生图（`imggen`）  
**目标**：评估工作量，给出**可快速、可回滚**的切换思路；**本文只做架构设计，不写实现 PR**  
**日期**：2026-07-16  
**Phase A 状态**：**已落地**（`common.ai` + `ai.chat-engine` + `LlmChatClient` 委托；默认 `langchain4j`）  
**Phase B 状态**：**已落地**（aigen `LangChain4jScriptPlanAdapter` + JSON Object 模式；video 摘要 JSON 模式 + POJO 解析）  
**结论先行**：**工作量中小，适合「一键式」分阶段切换**；不是重写三套流水线。核心只需替换 **Chat 出口** 与 2～3 个 Adapter，**不要**用 LangChain4j 接管任务调度 / TTS / Remotion / FLUX 生图。

---

## 1. 结论与工作量判断

### 1.1 一句话

| 问题 | 答案 |
|------|------|
| 能不能「不大改业务」切到 LangChain4j？ | **能**。现有 Port + 单点 `LlmChatClient` 已预留替换位 |
| 是否一键改完所有业务逻辑？ | **不是**。一键的是 **Chat 调用栈**；Pipeline / 状态机 / 调度 **不动** |
| 预估工作量 | **约 1～2 人日**（核心 Chat 替换 + 三工具接上 + 冒烟）；结构化输出增强再 +0.5～1 人日 |
| 风险 | 低：Adapter 双实现 + 配置开关可回滚到 OkHttp 版 |

### 1.2 现状盘点（代码事实）

```
                    ┌─────────────────────────────────────┐
                    │  AiProperties (yml: ai.providers.*) │
                    │  ai_model_config (库表模型列表)       │
                    └─────────────────┬───────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────┐
                    │  video.client.LlmChatClient         │  ← 唯一 Chat 出口
                    │  (手写 OkHttp + OpenAI chat 协议)    │
                    │  重试 / 超时 / reasoning_content      │
                    └───────┬─────────────┬───────────────┘
           ┌────────────────┼─────────────┼────────────────┐
           ▼                ▼             ▼                ▼
   SummarizationService  LlmChatScript  LlmPromptEnhance  VideoProcessService
   (video 总结)          PlanAdapter    Adapter           .testLlmModel
                         (aigen 分镜)   (imggen 润色)
```

| 模块 | LLM 相关入口 | 是否已有 Port | 是否走 `LlmChatClient` |
|------|--------------|---------------|------------------------|
| **视频提取** | `SummarizationService`；`testLlmModel` | 无 Port（Service 直依赖） | ✅ |
| **视频生成** | `ScriptPlanPort` → `LlmChatScriptPlanAdapter` | ✅ 已有 | ✅ |
| **图片生成（润色）** | `PromptEnhancePort` → `LlmPromptEnhanceAdapter` | ✅ 已有 | ✅ |
| **图片生成（出图）** | `ImageGenPort` → `NvidiaFluxImageAdapter` | ✅ | ❌ **专用 FLUX 协议，不走 Chat** |
| 交易侧聊天 | `ChatService` 自建 OkHttp 流式 | 独立 | ❌（**本次可不纳入**，见 §8） |

**依赖**：`pom.xml` **尚未**引入 `langchain4j`；设计文档（aigen/imggen）已多次预留替换说明。

### 1.3 明确「切换什么 / 不切换什么」

| ✅ 切换（Chat 文本能力） | ❌ 不切换 / 不硬套 LangChain4j |
|--------------------------|--------------------------------|
| 视频提取：字幕 → 结构化摘要 | 任务表、Scheduler、SSE、鉴权 |
| 视频生成：prompt → Storyboard JSON | TTS、Remotion、`VideoRenderPort` |
| 图片生成：prompt 润色 | **NVIDIA FLUX 生图**（`NvidiaFluxImageAdapter` 保留） |
| 模型连通性测试 `POST /models/test` | Whisper、yt-dlp、FFmpeg |
| （可选）统一重试/超时策略 | 整条流水线做成 LangChain Agent 黑盒 |

> **禁止**：用 `OpenAiImageModel` 硬套 NVIDIA FLUX 端点；协议不同，现有 Adapter 正确。

---

## 2. 目标架构（切换后）

### 2.1 分层原则

```
Controller / Pipeline / Step          ← 业务编排，零 langchain4j import
        │
        ▼
Port 接口（防腐）                      ← ScriptPlanPort / PromptEnhancePort / (新建) SummaryPort?
        │
        ▼
Adapter 实现                           ← 唯一允许 import dev.langchain4j.* 的业务边界
        │
        ▼
common.ai（共享 LLM 基础设施）
  ├─ ChatModelFactory                 ← 按 provider+model 构建 ChatModel
  ├─ LlmChatGateway（可选门面）        ← chat(system,user,provider,model) 兼容旧 API
  └─ 重试 / 超时 / 内容兜底（reasoning_content 等）
        │
        ▼
LangChain4j OpenAiChatModel            ← baseUrl + apiKey 对接 ai.providers
        │
        ▼
NVIDIA / DeepSeek / OpenAI …（OpenAI 兼容）
```

**铁律**：

1. **Pipeline / Controller / Entity 禁止依赖** `dev.langchain4j.*`。  
2. **任务级动态 model**：不用全局单例 `ChatModel` Bean 绑死一个模型；Factory 按任务参数创建或缓存。  
3. **配置不重复造**：继续 `ai.providers.*` + `ai_model_config`，不引入第二套密钥体系。  
4. **不用 starter 一把梭**（建议）：避免与现有多供应商动态切换冲突；手动 `ChatModelFactory` 更可控。

### 2.2 推荐包结构

```
com.dwcode.okxbot.common.ai
├── ChatModelFactory.java          # create(providerKey, modelId, options) → ChatModel
├── LlmChatGateway.java            # 薄门面：chat / testModel（替代 LlmChatClient 对外契约）
├── LlmCallOptions.java            # temperature / maxTokens / timeout / maxRetries
├── LlmContentHelper.java          # 抽 content / reasoning_content / 截断
└── config/
    └── LangChain4jConfig.java     # 可选；Bean 注册 Factory

com.dwcode.okxbot.video
├── client/LlmChatClient.java      # 【过渡期】委托 LlmChatGateway 或标记 @Deprecated
├── service/SummarizationService   # 改依赖 Gateway 或 SummaryPort
└── adapter/llm/                   # 【可选】LangChain4jSummarizationAdapter

com.dwcode.okxbot.aigen.adapter.llm
├── LlmChatScriptPlanAdapter.java  # 保留作 fallback 或删
└── LangChain4jScriptPlanAdapter.java  # 新默认：AiServices / Structured Output

com.dwcode.okxbot.imggen.adapter.llm
├── LlmPromptEnhanceAdapter.java
└── LangChain4jPromptEnhanceAdapter.java  # 新默认
```

### 2.3 与现有三工具的挂接关系

```
【视频提取】
POST /process → … → Pipeline SUMMARIZING
  → SummarizationService
       → LlmChatGateway.chat(...)  或  SummaryPort.summarize(...)
            → ChatModelFactory → OpenAiChatModel

【视频生成】
AigenPipeline PLANNING
  → ScriptPlanPort.plan(PlanCommand)
       → LangChain4jScriptPlanAdapter   ★ 只换 Adapter Bean
            → ChatModelFactory + AiServices / chat + JSON 解析
       （mock 路径不变）

【图片生成】
ImgGenPipeline EnhanceStep
  → PromptEnhancePort.enhance(...)
       → LangChain4jPromptEnhanceAdapter  ★ 只换 Adapter Bean
            → ChatModelFactory
GenerateStep
  → ImageGenPort → NvidiaFluxImageAdapter  ★ 不动
```

---

## 3. 切换策略：两阶段「快速切换」

> 不做大爆炸重写。**Phase A 替换传输层；Phase B 增强结构化能力。**

### 3.1 Phase A — 传输层一键切换（推荐先做，0.5～1 人日）

**目标**：所有 Chat 调用走 LangChain4j，**业务 prompt / JSON 解析逻辑基本不动**。

```
步骤：
1. pom 引入 langchain4j-bom + langchain4j + langchain4j-open-ai
2. 实现 ChatModelFactory（读 AiProperties）
3. 实现 LlmChatGateway，API 对齐现有 LlmChatClient.chat / testModel
4. LlmChatClient 内部改为委托 Gateway（或直接改引用方）
5. 冒烟：提取总结 / 视频分镜 / 图片润色 / models/test
```

**效果**：

- `SummarizationService`、`LlmChatScriptPlanAdapter`、`LlmPromptEnhanceAdapter` **可零改或改 import 一行**。  
- 重试、超时、日志收敛到 Factory/Gateway。  
- 若 LangChain4j 某供应商异常：Gateway 内开关回 OkHttp 实现（双实现）。

```yaml
# 建议配置（示例）
ai:
  chat-engine: langchain4j   # langchain4j | okhttp（回滚）
```

**兼容点（必须在 Factory 里处理）**：

| 现有能力 | 切换注意 |
|----------|----------|
| 动态 `base-url` + Bearer | `OpenAiChatModel.builder().baseUrl(...).apiKey(...)` |
| `max_tokens` / temperature | 映射到 builder 或 request parameters |
| 429/503 指数退避 | LangChain4j 自带有限；**建议 Gateway 外包一层重试**（与现网行为一致） |
| 部分模型 `reasoning_content` | `LlmContentHelper` 兜底（与现 `parseContent` 对齐） |
| 测试短超时 | Factory 支持 `timeout` 参数；`testModel` 用独立 options |

### 3.2 Phase B — 结构化输出增强（+0.5～1 人日，可后置）

**目标**：减少手写 JSON 抽取 / 校验失败率；**仍不改 Pipeline**。

| 模块 | 增强方式 | 收益 |
|------|----------|------|
| **aigen** | `AiServices` + 输出 `StoryboardDto`（或中间 DTO）+ 现有 `Validate` + repair | 分镜最受益 |
| **video** | AiServices 输出 `VideoSummaryPart` 或 JSON schema 约束 | 摘要字段更稳 |
| **imggen 润色** | 保持纯文本即可；可选 SystemMessage 模板化 | 收益小，可不做 |

**aigen 示意（设计级，非落地代码）**：

```java
// Port 不变
public interface ScriptPlanPort {
    StoryboardDto plan(PlanCommand command);
}

// Adapter 内：
ChatModel model = chatModelFactory.create(cmd.getLlmProvider(), cmd.getLlmModel(), options);
ScriptPlanner planner = AiServices.builder(ScriptPlanner.class)
        .chatModel(model)
        .build();
StoryboardDto dto = planner.plan(systemConstraints, userPrompt);
// 仍走 StoryboardNormalizeService + Validate + repair（repair 也可再调一次 AI）
```

**约束**：Schema 很复杂（`SceneProps` 多态）时，**不要第一天就 100% 依赖框架反序列化**；  
策略：**LangChain4j 生成 JSON 字符串 → 现有 ObjectMapper + Normalize/Validate** 更稳，再逐步收紧 Structured Output。

### 3.3 配置切换与 Bean 装配（与现有 mock/real 一致）

沿用 `AigenBeanConfig` / `ImgGenBeanConfig` 模式：

```java
// aigen：按 steps.plan + 引擎开关选实现
@Bean
ScriptPlanPort scriptPlanPort(...) {
  if (mock) return mockAdapter;
  if ("okhttp".equals(engine)) return llmChatScriptPlanAdapter;      // 旧
  return langChain4jScriptPlanAdapter;                               // 新默认
}
```

```java
// imggen
@Bean
PromptEnhancePort promptEnhancePort(...) {
  return engineOkHttp ? oldAdapter : langChain4jAdapter;
}
```

视频提取可：

- **最小改动**：`SummarizationService` 只换 `LlmChatClient` → `LlmChatGateway`；或  
- **更干净**：抽出 `VideoSummaryPort`，再挂 LangChain4j Adapter（与 aigen/imggen 对称）。

---

## 4. 三工具切换清单（按模块）

### 4.1 视频提取 `video`

| 项 | 动作 | 改动量 |
|----|------|--------|
| `LlmChatClient` | 委托 `LlmChatGateway` 或删除后改引用 | 中（集中 1 文件） |
| `SummarizationService` | 换依赖；prompt/parse 可暂留 | 小 |
| `VideoProcessService.testLlmModel` | 调 Gateway.testModel | 小 |
| Pipeline / Scheduler / Whisper / 下载 | **不动** | 0 |
| （可选）Structured `VideoSummaryPart` | Phase B | 中 |

**调用链（切换后）**：

```
POST /process → … → VideoProcessingPipeline
  → SummarizationService.summarize(...)
       → LlmChatGateway.chat(system, user, provider, model)
            → ChatModelFactory → ChatModel.chat(...)
```

### 4.2 视频生成 `aigen`

| 项 | 动作 | 改动量 |
|----|------|--------|
| `ScriptPlanPort` | **接口不动** | 0 |
| 新增 `LangChain4jScriptPlanAdapter` | 实现 Port | 中 |
| `AigenBeanConfig` | real 模式默认挂新 Adapter | 小 |
| `LlmChatScriptPlanAdapter` | 保留作 `chat-engine=okhttp` 回滚 | 小 |
| PlanStep / Pipeline / TTS / Remotion | **不动** | 0 |

**为何 aigen 最适合先吃 Structured Output**：分镜失败主要在 JSON 形态；且已有 normalize + validate + repair 闭环。

### 4.3 图片生成 `imggen`

| 项 | 动作 | 改动量 |
|----|------|--------|
| `PromptEnhancePort` | **接口不动** | 0 |
| 新增 `LangChain4jPromptEnhanceAdapter` | chat 润色 | 小 |
| `ImgGenBeanConfig` | 切换 Bean | 小 |
| `NvidiaFluxImageAdapter` | **明确不迁** | 0 |
| EnhanceStep / GenerateStep | **不动** | 0 |

---

## 5. `ChatModelFactory` 设计要点

### 5.1 职责

```
输入：providerKey, modelId, LlmCallOptions(timeout, temp, maxTokens, …)
输出：dev.langchain4j.model.chat.ChatModel（或 StreamingChatModel 若以后要）

内部：
  1. AiProperties.getProvider(providerKey) → baseUrl / apiKey
  2. modelId 空 → 与现逻辑一致：配置默认 / 库表 firstEnabled
  3. 规范化 baseUrl（是否带 /v1）
  4. OpenAiChatModel.builder()...
  5. 可选：按 (provider, model, optionsHash) 做短时缓存（注意超时不同的 test vs 任务）
```

### 5.2 为什么不做成「全局唯一 ChatModel Bean」

- 任务提交时用户可选不同供应商/模型。  
- `testModel` 需要更短超时。  
- aigen / video 的 maxTokens、temperature 不同。  

→ **Factory 按次创建（或带参数缓存）** 才匹配现有产品行为。

### 5.3 与 `LlmChatClient` 的 API 对齐（降低改动面）

建议 `LlmChatGateway` 保持：

```text
String chat(String system, String user, String provider, String model)
LlmModelTestResponse testModel(String provider, String model)
```

这样 **三工具 Adapter 几乎可机械替换依赖类名**，实现「快速切换」。

---

## 6. 回滚与灰度

| 机制 | 说明 |
|------|------|
| `ai.chat-engine=okhttp \| langchain4j` | 运行时/配置切换传输实现 |
| aigen/imggen 双 Adapter Bean | mock / real / engine 三维组合 |
| 行为对账 | 同一 prompt 对比旧客户端 vs 新 Gateway 输出（摘要字段、分镜 validate 通过率） |
| 监控 | 现有 step 耗时字段 + 日志 `provider/model`；可选记 `engine=langchain4j` |

回滚路径：**改配置 + 重启**（或动态 `@RefreshScope` 若后续需要），无需回退业务代码。

---

## 7. 工作量拆分与实施顺序

### 7.1 建议顺序（风险从低到高）

```
① 公共层 ChatModelFactory + LlmChatGateway + 单测/手工 testModel
        ↓
② LlmChatClient 委托 Gateway（全站 Chat 隐式切换）  ← 「一键」核心
        ↓
③ 冒烟三工具：提取总结 / aigen plan / imggen enhance
        ↓
④ aigen 专用 LangChain4jScriptPlanAdapter（可选 Structured）
        ↓
⑤ video 摘要结构化（可选）
        ↓
⑥ 删除或归档 OkHttp 实现（稳定 1～2 周后）
```

### 7.2 人日粗估

| 项 | 人日 |
|----|------|
| 依赖 + Factory + Gateway + 配置开关 | 0.5 |
| `LlmChatClient` 委托 + 三工具冒烟 | 0.5 |
| aigen 新 Adapter + repair 对齐 | 0.5 |
| imggen 新 Adapter（可与 ② 合并，极小） | 0.1 |
| 文档 / 回滚说明 / 回归清单 | 0.2 |
| **合计（Phase A 为主）** | **~1.5** |
| Phase B 结构化增强 | +0.5～1 |

### 7.3 验收清单（切换完成定义）

- [ ] `POST /api/v1/video/models/test` 可用  
- [ ] 视频提取任务 `SUMMARIZING` → `SUCCESS`，summary 字段完整  
- [ ] aigen `steps.plan=real` 产出合法 storyboard，能过 Validate  
- [ ] imggen enhance=real 润色英文 prompt 非空，FLUX 出图仍成功  
- [ ] `ai.chat-engine=okhttp` 可回滚  
- [ ] Pipeline / 调度 / SSE / 表结构 **无强制变更**

---

## 8. 范围外与后续

| 项 | 建议 |
|----|------|
| `ChatService` 流式对话 | **二期**：需 `StreamingChatModel`；与三工具同步会放大工期 |
| Whisper ASR | 保持现有 HTTP 微服务，不进 LangChain4j |
| FLUX 文生图 | 保持 `NvidiaFluxImageAdapter` |
| Spring AI | V1 **不混用**，避免双框架 |
| Agent / Tool / RAG | 仅在 Port 稳定后再加；禁止把整个 AigenPipeline 塞进 Agent |

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 供应商非标准 OpenAI 字段 | 解析空 content | 保留 `reasoning_content` 兜底 |
| 重试语义不一致 | 瞬时 429 失败率上升 | Gateway 外层保留指数退避 |
| Structured Output 与复杂 DTO 不匹配 | aigen plan 失败 | Phase B 渐进；先字符串 JSON + 现有 Validate |
| 依赖版本与 Spring Boot 冲突 | 编译/运行失败 | BOM 锁定；不用 starter 先探路 |
| 误把 FLUX 迁到 ImageModel | 出图全挂 | 架构上 ImageGenPort 不经过 common.ai Chat |

---

## 10. 决策记录（建议直接拍板）

| # | 决策 | 推荐 |
|---|------|------|
| D1 | 是否三工具一起切 Chat？ | **是**，共享 Factory，一次到位 |
| D2 | 是否第一天上 Structured Output？ | **否**，Phase A 先换传输；aigen 可紧接着做 |
| D3 | 是否删 `LlmChatClient`？ | **先委托后删除**，避免大范围改 import 风险 |
| D4 | FLUX 是否碰？ | **否** |
| D5 | Chat 模块流式是否纳入本次？ | **否** |
| D6 | 配置开关 | **要**（`ai.chat-engine`） |

---

## 11. 与现有文档关系

| 文档 | 关系 |
|------|------|
| `AI视频生成_架构设计方案.md` §6 | aigen 侧 LangChain4j 细节已有，本文补齐 **三工具统一切换路径** |
| `AI视频生成_Phase1_架构设计.md` | Port 防腐与 Adapter 约定与本文一致 |
| `AI文生图_*` | 已写「润色可换 LangChain4j、FLUX 不换」，本文固化 |
| `VideoCoreExtractor_核心接口调用路径.md` | 流水线路径不变，仅 Summarize 出口换实现 |

---

## 12. 总结

1. **工作量不大**：真正要换的是「Chat 出站层」+ 最多 3 个 Adapter；**不是**重写三套工具。  
2. **快速切换路径** = `ChatModelFactory` + `LlmChatGateway` 替换 `LlmChatClient` 内核，三工具自动受益。  
3. **架构正确姿势** = Port 防腐 + 共享 common.ai；LangChain4j **只碰 LLM**，不碰调度/渲染/ASR/FLUX。  
4. **可回滚** = 双引擎配置；稳定后再删 OkHttp 实现。  
5. **增值做 Phase B**：aigen 分镜结构化输出优先，提取摘要次之，润色可忽略。

---

### Phase A 落地清单（已完成）

| 项 | 位置 |
|----|------|
| BOM `1.17.2` + `langchain4j` / `langchain4j-open-ai` | `pom.xml` |
| `ChatModelFactory` / `LlmChatGateway` / `LlmCallOptions` / `LlmContentHelper` | `com.dwcode.okxbot.common.ai` |
| `ai.chat-engine`（默认 `langchain4j`） | `AiProperties` + `application.yml` |
| `LlmChatClient` 双引擎委托 | 已迁至 `common.ai.LlmChatClient`（全局入口） |
| baseUrl 规范化单测 | `ChatModelFactoryTest` |

回滚：`ai.chat-engine: okhttp` 后重启即可。

### Phase B 落地清单（已完成）

| 项 | 说明 |
|----|------|
| `LlmCallOptions.responseFormat` | 支持 `json_object`；Factory / Client / okhttp 路径均生效 |
| `LlmContentHelper.extractJsonObject` | 统一 JSON 抽取 |
| `LangChain4jScriptPlanAdapter` | AiServices `StoryboardJsonPlanner` + Normalize/Validate/repair |
| `AigenBeanConfig` | `ai.chat-engine=langchain4j` 时默认挂新 Adapter；okhttp 回退旧 Adapter |
| `aigen.llm.structured-mode` | auto/json 开 JSON 模式，off 关闭 |
| `SummarizationService` | JSON 模式 + `VideoSummaryPart` 优先直转 |
| imggen 润色 | 保持纯文本（按设计不做结构化） |

**后续可选**：

1. 稳定后删除 OkHttp 路径 / 旧 Adapter  
2. 聊天模块 `ChatService` 流式迁 StreamingChatModel
