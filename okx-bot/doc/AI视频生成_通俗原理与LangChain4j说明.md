# AI 视频生成：通俗原理 + LangChain4j 是什么

**写给谁**：想搞懂「一句话怎么变成 MP4」、以及架构文档里「LangChain4j 结构化 Storyboard」是什么意思的人。  
**不需要**：会 Remotion / 会 LangChain 才能读完。  
**版本**：v1.0 · 2026-07-13  
**相关代码**：`okx-bot` 的 `aigen` 包 · 旁路工程 `aigen-remotion`

---

## 0. 先用一句话记住

> 系统**不会**让 AI 直接「画」出整段视频。  
> AI 只负责写一份**分镜剧本（Storyboard）**；  
> 再用 TTS 配音、用 Remotion 按剧本**合成画面**，最后得到 MP4。

类比拍短视频：

| 角色 | 现实中 | 本项目 |
|------|--------|--------|
| 导演写分镜 | 人脑 + 文档 | **大模型（LLM）** |
| 配音员 | 人 / 录音棚 | **Edge-TTS / Windows 语音** |
| 剪辑 / 动画师 | PR / 剪映 | **Remotion（程序化动画）** |
| 制片统筹 | 项目经理 | **Java 流水线 AigenPipeline** |

---

## 1. 整条生成逻辑（从你点「生成」开始）

```
你在网页输入提示词（+ 时长、模板、模型…）
        │
        ▼
┌───────────────────────────────────────┐
│  Spring Boot 记一条任务（数据库）      │
│  状态：PENDING → 排队执行             │
└───────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────┐
│  第 1 步 PLAN（规划）                 │
│  提示词 → 大模型 → Storyboard JSON    │
│  状态：PLANNING                       │
└───────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────┐
│  第 2 步 ASSET（素材）                │
│  每个镜头的旁白 → 语音文件 mp3        │
│  按真实时长重排时间轴 / 字幕          │
│  状态：ASSET_GENERATING               │
└───────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────┐
│  第 3 步 RENDER（渲染）               │
│  把 Storyboard + 音频交给 Remotion    │
│  渲染出 output.mp4（再混音旁白）      │
│  状态：RENDERING → SUCCESS            │
└───────────────────────────────────────┘
        │
        ▼
  前端用鉴权接口拉 MP4 → 在线播放 / 下载
```

任务目录大致长这样（`data/aigen/{任务id}/`）：

```
request.txt              # 你当初的提示词
storyboard.plan.json     # 规划刚出来时的分镜
storyboard.json          # 配音后带时间轴的分镜（给渲染用）
assets/audio/scene-*.mp3 # 每场口播
output.mp4               # 成品
```

### 1.1 为什么要拆成三步？

| 步骤 | 干什么 | 为什么单独拆 |
|------|--------|--------------|
| **Plan** | 想清楚「播什么」 | 慢、贵、容易 JSON 不合法；失败可重试规划 |
| **Asset** | 生成「听什么」 | 依赖本机 TTS；时长以音频为准 |
| **Render** | 生成「看什么」 | CPU 重、要 Node/Remotion；与 LLM 解耦 |

流水线代码：`AigenPipeline` 按顺序跑 `PlanStep` → `AssetStep` → `RenderStep`。  
暂停/取消目前主要在**步骤边界**生效（一步跑完再停），不是中途掐断 FFmpeg。

### 1.2 Storyboard 是什么？（核心中间产物）

**Storyboard = 整条片子的「结构化剧本」**，一份大家都能读的 JSON。

LLM、TTS、Remotion **共用同一份结构**（见 `StoryboardDto`）：

```json
{
  "version": "1.0",
  "meta": {
    "title": "标题",
    "templateId": "knowledge-cards",
    "fps": 30,
    "width": 1080,
    "height": 1920,
    "durationInFrames": 900
  },
  "style": { "theme": "dark-tech", "primaryColor": "#00E5FF" },
  "scenes": [
    {
      "id": "scene-1",
      "type": "title",
      "startFrame": 0,
      "durationInFrames": 90,
      "narration": "口播要念的话……",
      "props": {
        "title": "画面大标题",
        "subtitle": "副标题",
        "items": ["要点1", "要点2"]
      }
    }
  ],
  "audio": { "tracks": [ /* 配音后写入路径 */ ] },
  "subtitles": [ /* 字幕时间轴 */ ]
}
```

通俗理解每个字段：

| 字段 | 人话 |
|------|------|
| `scenes` | 几个镜头，像 PPT 几页 |
| `type` | 镜头类型：标题页 / 要点页 / 结尾（模板白名单） |
| `narration` | **配音员念的字**（给 TTS） |
| `props` | **画面上显示的字**（给 Remotion） |
| `startFrame` / `durationInFrames` | 从第几帧开始、播多久（30fps 时 90 帧 ≈ 3 秒） |
| `audio.tracks` | 素材步骤写好的 mp3 路径 |

**重要**：大模型一般只「写剧本」；真正的 mp3 路径、按音频校准的帧数，是 **Asset 步骤**补上的。

---

## 2. 「LangChain4j 结构化 Storyboard」到底是什么意思？

拆开四个词：

### 2.1 Storyboard

就是上一节的**分镜 JSON**。不是视频文件，是剧本。

### 2.2 结构化（Structured Output）

意思是：希望大模型**不要闲聊**，而是**稳定吐出符合约定字段的对象**。

| 方式 | 模型大概回什么 | 后端好不好用 |
|------|----------------|--------------|
| 非结构化 | 「好的，我帮你写个视频……第一幕……」 | 很难自动用 |
| 半结构化（当前） | 一大段文本里夹着 JSON（可能还带 \`\`\`json） | 要自己抠、修、校验 |
| **结构化输出** | 尽量直接是「字段齐全、类型正确」的 JSON/对象 | 校验失败率更低 |

「结构化 Storyboard」= **规划结果直接是（或可稳定映射成）`StoryboardDto` 这种固定结构**，而不是自由散文。

### 2.3 LangChain4j

**LangChain4j** 是 Java 里调用大模型的框架（类似 Python 的 LangChain），常见能力：

- 统一对接 OpenAI 兼容 API（含很多国内/云厂商兼容端点）
- **AiServices**：写一个 Java 接口，框架帮你调模型
- **Structured Output**：把返回结果绑到某个 Java 类（例如 `StoryboardDto`）
- 重试、工具调用、消息拼装等（本项目规划层用到的主要是前几项）

架构文档里写的推荐写法，语义上类似：

```text
接口：ScriptPlanner.plan(提示词, 模板…)  →  返回类型：StoryboardDto
框架：尽量让模型按 StoryboardDto 的 schema 吐结果
业务：再 validate / normalize，不合格再 repair 一次
```

### 2.4 合起来那句话

> **「LangChain4j 结构化 Storyboard」**  
> = 用 LangChain4j 做规划调用，并尽量让模型输出**可直接变成 `StoryboardDto` 的结构化结果**，而不是靠人肉解析一段乱七八糟的文本。

它**只解决「规划这一步」**，不负责 TTS，也不负责画视频。

---

## 3. 引入 LangChain4j 和「当前实现」有什么区别？

### 3.1 当前实际怎么规划的？

实现类：`LlmChatScriptPlanAdapter`（**没有**引入 LangChain4j 依赖）。

流程：

```
1. 拼很长的 System Prompt（规定只能输出 JSON、场景类型白名单等）
2. 用现有 LlmChatClient 调模型（和视频提取共用一套 chat）
3. 从返回字符串里「抠」JSON（去 markdown 代码块等）
4. Jackson 反序列化成 StoryboardDto
5. Normalize（补默认值、对齐时长/分辨率）
6. Validate（类型、路径、字段是否合法）
7. 不合法 → 再调一次模型做 repair
8. 仍不合法 → 任务失败，提示规划失败
```

特点：**能用、可控、不增加新依赖**；坏处是结构化能力全靠 prompt + 自己写解析，模型一飘就靠 repair 硬扛。

### 3.2 文档推荐的 LangChain4j 方式？

```
1. pom 引入 langchain4j + open-ai 兼容模块
2. ChatModelFactory 按任务选的模型建 ChatModel
3. AiServices 创建 ScriptPlanner
4. 声明：plan(...) 返回 StoryboardDto  ← 结构化输出
5. 同样再走 Validate / Normalize / Repair
6. 对外仍只暴露 ScriptPlanPort（流水线无感）
```

### 3.3 对照表（重点）

| 维度 | **当前（LlmChatScriptPlanAdapter）** | **文档推荐（LangChain4j）** |
|------|--------------------------------------|-----------------------------|
| 依赖 | 无新库，复用 `LlmChatClient` | 新增 `langchain4j*` |
| 模型怎么调 | 手写 HTTP/chat 封装 | 框架统一 `ChatModel` |
| 期望输出 | Prompt 里写「只输出 JSON」 | 接口返回类型 + Structured Output / JSON Schema |
| 解析 | 自己正则抠 JSON + Jackson | 框架尽量直接映射到 Java 对象 |
| 失败修复 | 已有 validate + repair 一轮 | 通常仍建议保留本地校验 + repair |
| 流水线 | `ScriptPlanPort` | 同一个 Port，只换适配器 |
| 对用户可见效果 | 一样：得到分镜再配音再渲染 | 理想情况：**规划更稳、少失败** |
| 对成片画面 | **几乎无差别** | 画面仍由 Remotion 决定 |

### 3.4 容易误解的点

| 误解 | 真相 |
|------|------|
| 「上了 LangChain4j 视频会更炫」 | 否。它主要影响 **分镜 JSON 稳不稳**。 |
| 「现在没有 AI」 | 否。现在已经在用大模型做规划。 |
| 「LangChain4j = 整条 Agent 黑盒出片」 | 否。文档明确：**只把 LLM 规划交给它**，状态机/TTS/渲染仍归 Spring。 |
| 「必须上 LangChain4j 才能 Phase1」 | 否。当前路线已能出片；LC4j 是**规划层的工程升级选项**。 |

---

## 4. 用「做一道菜」再串一遍

1. **点菜（前端）**  
   你说：做一道「AI Agent 入门」知识短片，30 秒，竖屏。

2. **写菜单与步骤（Plan / LLM）**  
   模型写出：第 1 页标题说什么、第 2 页三个要点、旁白念什么。  
   → 这就是 Storyboard。  
   → 「结构化」= 菜单必须是固定表格，不能写成散文。  
   → LangChain4j = 帮厨按表格自动填表的工具；现在是人（代码）对着乱写的纸条抄进表格。

3. **备料（Asset / TTS）**  
   每句旁白录成 mp3；量一下实际几秒，调整每页停留时间。

4. **炒菜出锅（Render / Remotion）**  
   模板（KnowledgeCards）按表格做动画：标题飞入、要点列表、结尾 CTA。  
   再和旁白混成一条 `output.mp4`。

5. **上桌（前端）**  
   带登录态拉视频，播放或下载。

中间任何一步失败 → 任务 `FAILED`，可以 retry（清空后再跑或按策略重跑）。

---

## 5. 各进程 / 模块各干什么（防止概念搅在一起）

| 组件 | 职责 | 不负责 |
|------|------|--------|
| **okx-trading-web** | 表单、进度、播放器 | 不跑 LLM、不渲染 |
| **okx-bot（aigen）** | 任务、鉴权、三步编排、落盘、SSE | 不在 Java 里做动画 |
| **大模型 API** | 生成/修复分镜 JSON | 不生成 mp4 |
| **Edge-TTS / SAPI** | 文本 → 语音文件 | 不管画面 |
| **aigen-remotion** | 按 Storyboard 渲画面 + 混音 | 不管业务库、不管用户登录 |
| **LangChain4j（未引入）** | （若引入）只服务「规划调用更结构化」 | 替代不了 Remotion / TTS |

配置开关（`application.yml` 的 `aigen`）：

- `mock-pipeline: true` → 三步全假跑（调试骨架）
- `steps.plan/asset/render: mock|real` → 可单独假/真
- `tts.provider: auto|edge|windows|mock`
- `remotion.*` → 渲染服务地址、是否 Java 托管 Node 进程

---

## 6. 数据在时间上怎么「变厚」

```
Plan 之后：
  scenes 有了标题、要点、narration
  audio.tracks 往往还是空的 / 占位

Asset 之后：
  assets/audio/scene-1.mp3 …
  每场 durationInFrames 按真实语音重算
  subtitles / audio.tracks 写上相对路径

Render 之后：
  output.mp4 出现
  任务状态 SUCCESS，前端可播
```

所以：**规划差 → 内容空洞或 JSON 挂；配音差 → 有画无声/时长短；渲染差 → 有素材无片。**  
排查问题也按这三段拆。

---

## 7. 和「视频提取」模块别混了

| | **视频提取（video）** | **AI 视频生成（aigen）** |
|--|----------------------|--------------------------|
| 方向 | 已有视频 → 文案/摘要 | 提示词 → 新视频 |
| 主工具 | yt-dlp、FFmpeg、Whisper、LLM 总结 | LLM 分镜、TTS、Remotion |
| LLM 用途 | 总结转录 | 写 Storyboard |
| 共用 | 登录、模型配置、`LlmChatClient` 等 | 同上 |

两者都是「AI 工具」，但流水线完全不同。

---

## 8. 总结（可转发的短版）

1. **生成逻辑** = 写剧本（LLM）→ 配音（TTS）→ 程序化合成（Remotion），Java 只做统筹。  
2. **Storyboard** = 贯穿全程的结构化分镜 JSON，不是成品视频。  
3. **结构化输出** = 逼模型按固定字段交剧本，而不是自由发挥聊天。  
4. **LangChain4j** = Java 侧更好用的 LLM 工具箱；文档希望用它做「结构化 Storyboard」。  
5. **当前项目** = 已用自研 `LlmChatClient` 做到「能规划、能出片」；**尚未**引入 LangChain4j。  
6. **引入 LC4j 的主要收益** = 规划更稳、代码更标准；**不是**换一套渲染引擎。

---

## 9. 想继续深入时看这些文件

| 文件 | 内容 |
|------|------|
| `doc/AI视频生成_极简说明.md` | 最短认知 |
| `doc/AI视频生成_Phase1_架构设计.md` | Phase1 实施规格 |
| `doc/AI视频生成_架构设计方案.md` §6 | LangChain4j 详细设计 |
| `aigen/adapter/llm/LlmChatScriptPlanAdapter.java` | **当前**规划实现 |
| `aigen/agent/AigenPipeline.java` | 三步流水线 |
| `aigen/domain/StoryboardDto.java` | 分镜契约 |
| `../aigen-remotion/` | 真正出画的工程 |

---

*本文只做概念解释，不改变线上行为。若后续落地 LangChain4j，建议仍通过 `ScriptPlanPort` 替换适配器，保持流水线不动。*
