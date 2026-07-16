# AI 视频生成 — Visual Timeline（画面优先）架构设计

**模块**：aigen 演进轨（Visual Timeline / VT）  
**定位**：解决「过度依赖模板、内容单一、难做复杂视频」  
**原则**：画面优先 · 音频可选 · 模板降级为包装 · 可演进可回滚  
**版本**：v1.0  
**日期**：2026-07-16  
**状态**：VT-0 / VT-1 **已落地**；**VT-1.5 已实现**（2026-07-16）  

**相关文档**：

| 文档 | 关系 |
|------|------|
| `AI视频生成_极简说明.md` | 当前「提示词 → 分镜 → TTS → Remotion」认知 |
| `AI视频生成_架构设计方案.md` | 长期总蓝图（模板+口播为主） |
| `AI视频生成_Phase1_架构设计.md` | 已落地：Storyboard + TTS + 2 个 Composition |
| `AI文生图_*` | imggen 可直接作为镜头主视觉 Provider |
| `LangChain4j_三工具切换架构设计.md` | LLM 出站层（Director 复用） |

---

## 0. 一句话与成功标准

### 0.1 一句话

```
用户主题
  → LLM 产出「镜头表 Shotlist」（画面描述 + 时长 + 叠字）
  → 素材工厂按镜生成主视觉（默认文生图，音频默认不做）
  → Remotion 按时间轴合成（全屏画面 + 运镜 + 转场 + 可选字幕/BGM）
  → 网页可播 MP4
```

### 0.2 与现状对比

| 维度 | Phase 1（现状） | Visual Timeline（目标） |
|------|-----------------|-------------------------|
| 产品假设 | 结构化口播卡片视频 | **可观看的画面短片** |
| LLM 输出 | 受限 `scene.type` + props | **镜头表**（画面 prompt 为主） |
| 视觉来源 | React 模板组件 | **AI 图/视频/上传素材** |
| 模板角色 | 内容本体 | **包装层**（叠字、角标、转场） |
| 音频 | 默认 TTS | **默认无声或 BGM；TTS 可选** |
| 复杂度上限 | 模板数量 × 组件表现力 | **镜头数 × 素材质量 × 剪辑语法** |

### 0.3 MVP 成功标准（V1）

| 验收项 | 标准 |
|--------|------|
| 端到端 | 输入主题 → 数分钟内可播竖屏/横屏 MP4 |
| 画面 | ≥ 4 个镜头，每镜有独立主视觉（非纯色占位） |
| 音频默认 | `audio.mode=none` 或 `bgm_only` 可出片；**不强制 TTS** |
| 可选 TTS | 用户勾选后可为部分/全部镜头配音 |
| 状态可观测 | 沿用任务 + SSE；步骤可读（规划 / 出图 / 合成） |
| 安全 | 任务归属、workDir 沙箱、禁止任意外链 SSRF |
| 共存 | **不破坏**现有 `insight-compare` / `knowledge` 模板链路（可配置切换） |

### 0.4 V1 明确不做

- 端到端 Sora 级「一句话出完整电影」黑盒（可作后续 Provider）
- 完整非线性剪辑器（时间轴精剪 UI）
- 角色/人脸跨镜强一致性训练
- 自动配乐版权曲库商用授权体系
- 多机分布式渲染集群

---

## 1. 问题陈述

### 1.1 现状瓶颈

当前 aigen 主链路：

```
Prompt → StoryboardDto（白名单 scene type）→ TTS → Remotion Composition（InsightCompare / KnowledgeCards）
```

导致：

1. **模板锁死表现力**：再多模板也是「字 + 形状 + 过渡」，难做出丰富画面。  
2. **LLM 能力被浪费**：只能填 `props.title/items`，无法描述真实镜头语言。  
3. **资源错配**：流水线默认生成音频，视觉却最薄。  
4. **定制成本线性**：新复杂形态 ≈ 新 React Composition + 新 schema + 新 prompt，不可规模化。

### 1.2 设计命题

> 如何在**不推倒**现有任务系统 / 调度 / SSE / Remotion 工程的前提下，  
> 把「模板填空」升级为「**画面优先的镜头合成**」，并让音频变为可选？

---

## 2. 设计原则

| 原则 | 含义 |
|------|------|
| **Visual-First** | 先保证每镜有可看主视觉，再谈旁白 |
| **Audio Optional** | `none` / `bgm_only` / `tts` 三档；默认非 TTS |
| **Template as Skin** | 模板只约束「如何叠字/转场」，不约束「画面内容从哪来」 |
| **Shot is unit** | 调度与缓存以镜头为粒度（可单镜重生图） |
| **Port 防腐** | Pipeline 只依赖 Port；LLM/FLUX/Remotion 类型不泄漏 |
| **契约校验** | LLM 输出必须过本地 Schema；非法镜头剔除或 repair |
| **与 Phase1 共存** | `pipelineMode=template \| visual`；存量任务与模板模式可继续跑 |
| **可降级** | 文生图失败 → 风格化占位；整任务可 FAILED + 重试 |

---

## 3. 总体架构

### 3.1 逻辑分层

```
┌─────────────────────────────────────────────────────────────┐
│  okx-trading-web  /video-generate（扩展模式选择）              │
│  主题 · 画幅 · 时长 · audio.mode · 风格 · 是否 TTS            │
└────────────────────────────┬────────────────────────────────┘
                             │ REST / SSE
┌────────────────────────────▼────────────────────────────────┐
│  okx-bot aigen                                               │
│                                                              │
│  AigenTaskService → Scheduler → AigenPipeline（模式分支）      │
│       │                                                      │
│       ├─ [template 模式]  现有 Plan → TTS → Render            │
│       │                                                      │
│       └─ [visual 模式]                                       │
│            ① DirectorPort      主题 → ShotlistDto             │
│            ② VisualAssetPort   每镜 → 主视觉文件               │
│            ③ Composer（Render） Shotlist+assets → Remotion    │
│            ④ AudioPort（可选）  BGM / TTS                     │
└───────┬──────────────────────┬───────────────────┬──────────┘
        │                      │                   │
        ▼                      ▼                   ▼
   ai.providers /          imggen 复用或        aigen-remotion
   LangChain4j             内嵌 ImageGenPort    VisualTimeline
   ChatModelFactory        FLUX / mock          Composition
```

### 3.2 与 Phase 1 的关系

```
                    aigen_task（同一任务表，扩展字段）
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
     pipeline_mode=template          pipeline_mode=visual
     （现网默认可保留）                 （新默认推荐）
              │                               │
     StoryboardDto                      ShotlistDto
     scene.type 白名单                    shot.visual.*
     Remotion: Insight/Knowledge        Remotion: VisualTimeline
     Asset: TTS 为主                     Asset: 出图为主，音频可选
```

**不建议**新建第二套任务表；用 `pipeline_mode` + 产物 JSON 类型区分即可。

---

## 4. 核心契约：Shotlist（镜头表）

> 替代（visual 模式下）以模板 scene type 为中心的 Storyboard。  
> Phase 1 的 `StoryboardDto` **保留**给 template 模式。

### 4.1 顶层结构

```json
{
  "version": "vt-1.0",
  "meta": {
    "title": "三分钟看懂 Agent",
    "language": "zh",
    "aspectRatio": "9:16",
    "fps": 30,
    "width": 1080,
    "height": 1920,
    "targetDurationSec": 45,
    "stylePreset": "cinematic-dark",
    "pipelineMode": "visual"
  },
  "audio": {
    "mode": "bgm_only",
    "bgmId": "pulse-soft",
    "ttsVoice": null,
    "ttsEnabledShots": []
  },
  "shots": [
    {
      "id": "shot-1",
      "order": 1,
      "durationSec": 3.5,
      "durationInFrames": 105,
      "visual": {
        "type": "ai_image",
        "prompt": "cinematic wide shot, neon cyber city rain night, moody lighting, 35mm",
        "negativePrompt": "text, watermark, logo, blurry",
        "seed": null,
        "assetPath": null
      },
      "motion": {
        "type": "ken_burns",
        "params": { "from": 1.0, "to": 1.12, "direction": "center" }
      },
      "transition": {
        "type": "crossfade",
        "durationFrames": 12
      },
      "overlay": {
        "layout": "hook-center",
        "title": "别再堆概念了",
        "subtitle": "先锁一个可验收场景",
        "bullets": [],
        "position": "center",
        "style": "bold-impact"
      },
      "narration": null,
      "notes": "开场钩子"
    }
  ]
}
```

### 4.2 字段语义（V1 必选 / 可选）

#### meta

| 字段 | 必选 | 说明 |
|------|------|------|
| version | 是 | 固定 `vt-1.0` |
| aspectRatio | 是 | `9:16` / `16:9` / `1:1` |
| targetDurationSec | 是 | 总时长目标，校验用 |
| stylePreset | 否 | 影响默认调色/字体/转场偏好 |
| fps/width/height | 是 | 可由 aspectRatio 推导后写死 |

#### audio

| mode | 含义 | V1 |
|------|------|----|
| `none` | 无音轨 | ✅ 默认推荐之一 |
| `bgm_only` | 仅背景乐 | ✅ 预置 3～5 首本地 BGM |
| `tts` | 旁白（可全片或按镜） | ✅ 可选，复用现有 TtsPort |
| `tts_bgm` | 旁白+BGM | V1.5 |

#### shot.visual.type

| type | 说明 | V1 |
|------|------|----|
| `ai_image` | 文生图（主路径） | ✅ |
| `user_image` | 用户上传 | V1.5 |
| `ai_video` | 文/图生视频片段 | V2 |
| `stock` | 素材库 | V2 |
| `solid` / `gradient` | 降级占位 | ✅ 失败兜底 |

#### shot.motion.type（Remotion 实现）

| type | 说明 |
|------|------|
| `static` | 静止 |
| `ken_burns` | 缓推/缓移（默认） |
| `pan_left` / `pan_right` | 横移 |
| `zoom_in` / `zoom_out` | 变焦 |

V1 只实现 `static` + `ken_burns` 即可覆盖 80% 观感。

#### shot.overlay.layout（包装皮肤）

| layout | 用途 |
|--------|------|
| `none` | 纯画面 |
| `hook-center` | 居中大标题 |
| `lower-third` | 底部标题条 |
| `bullets-right` | 右侧要点 |
| `caption` | 安全区字幕 |

**注意**：layout 是「怎么叠字」，**不是**「画面长什么样」。

### 4.3 校验规则（本地最终裁判）

| 规则 | 建议值（可配置） |
|------|------------------|
| shots 数量 | 4～12 |
| 单镜时长 | 1.5s～8s |
| 总时长 | 5s～90s（对齐现 aigen max） |
| visual.prompt | 非空（ai_image）；长度上限 800 字 |
| 禁止 | `http(s)://` 外链进 assetPath（防 SSRF） |
| overlay 文本 | 标题 ≤ 24 字；bullet ≤ 6 条 × 20 字 |
| aspectRatio | 枚举校验 |

校验失败：Director repair 1 次 → 仍失败则任务 FAILED。

### 4.4 与 StoryboardDto 的映射（可选兼容）

不强制双向兼容。若需「template 预览」或渐进迁移，可提供只读投影：

```
Shotlist.shot → SceneDto {
  type: "visual",
  narration: shot.narration,
  props: { title, subtitle, items: bullets }
}
```

**visual 模式渲染不依赖该投影**；仅便于前端复用部分展示组件。

---

## 5. 流水线设计

### 5.1 状态机

沿用 `AigenTaskStatus`，语义微调：

```
PENDING
  → PLANNING          # Director：主题 → Shotlist
  → ASSET_GENERATING  # 出图（主）+ 可选 BGM 拷贝 / TTS
  → RENDERING         # Remotion VisualTimeline
  → SUCCESS | FAILED | PAUSED | CANCELLED
```

| 状态 | visual 模式 currentStep 示例 |
|------|------------------------------|
| PLANNING | 正在规划镜头表… |
| ASSET_GENERATING | 正在生成画面 3/7… |
| RENDERING | 正在合成视频… |

### 5.2 步骤拆分

```
VisualPipeline（或 AigenPipeline 内 mode 分支）
│
├─ PlanStep (DirectorPort)
│    输入: prompt, aspectRatio, duration, style, audio.mode
│    输出: shotlist.json + DB 摘要字段
│
├─ VisualAssetStep
│    对每个 shot（可并行，限流）:
│      ai_image → ImageGenPort / 复用 imggen 能力
│      写 assets/visual/{shotId}.jpg
│      回写 shot.visual.assetPath（相对任务目录）
│    可选: audio.mode=bgm_only → 复制预置 BGM
│    可选: audio.mode=tts → TtsPort（按 narration）
│
└─ RenderStep (VideoRenderPort)
     compositionId = VisualTimeline
     props = shotlist（含本地 asset 相对路径）
     输出 output.mp4
```

### 5.3 并发与性能

| 点 | 建议 |
|----|------|
| 出图并发 | 2～3（避免打爆 FLUX 配额） |
| 缓存 | `hash(prompt+size+style)` 同任务内去重；跨任务 V1.5 |
| 单镜失败 | 可重试 1～2 次 → 降级 gradient 占位 + 标记 warning（或整任务失败，由配置 `visual.fail-on-shot-error`） |
| 总超时 | PLAN 120s；ASSET 按镜数 × 单镜超时；RENDER 600s |

### 5.4 音频策略（产品默认）

| 用户选项 | 默认 | 流水线行为 |
|----------|------|------------|
| 纯画面 | ✅ 推荐默认 | `audio.mode=none` |
| 仅 BGM | ✅ 推荐 | 预置 BGM 混入 Remotion |
| 口播 | 可选 | 生成 narration + TTS；可与 BGM 分轨 |

**产品文案建议**：生成页默认「精彩画面」；口播作为高级选项，避免用户以为必须配音。

---

## 6. Port 与模块边界

### 6.1 新增 / 扩展 Port

```
DirectorPort
  ShotlistDto plan(DirectorCommand cmd)

VisualAssetPort（或复用/包装 ImageGenPort）
  VisualAssetResult generate(VisualAssetCommand cmd)
  // cmd: prompt, size, shotId, taskDir

AudioMixPort（可选，V1 可简化）
  准备 bgm 路径 / tts 路径

VideoRenderPort（已有）
  增加 compositionId=VisualTimeline 的 props 契约
```

### 6.2 与 imggen 关系

| 策略 | 说明 | V1 建议 |
|------|------|--------|
| **A. 进程内复用** | aigen 直接调 `ImageGenPort` / FLUX Adapter | ✅ 推荐，延迟低 |
| B. HTTP 调 imggen 任务 | 为每镜创建 imggen_task | 过重，不推荐 V1 |
| C. 独立实现 | 复制一份 FLUX 客户端 | 避免重复 |

Director 的「画面 prompt」与 imggen 的「用户提示词」可再过一层 **PromptEnhance**（可选开关）。

### 6.3 LLM（Director）

- 复用 `common.ai.ChatModelFactory` / `LlmChatClient`  
- 建议 JSON Object 模式（与 Phase B 一致）  
- **输出 Shotlist JSON 字符串 → ObjectMapper + Validate + repair**  
- 禁止让 LLM 直接输出可执行代码或任意 Remotion 组件名  

### 6.4 Remotion：`VisualTimeline` Composition

**唯一 V1 必做 Composition**（模板模式旧 Composition 保留）。

职责：

1. 按 `shots[]` 顺序铺时间轴  
2. 每镜：全屏图/视频 + motion  
3. 镜间 transition  
4. overlay 文字层（按 layout）  
5. 可选 audio 轨（bgm / tts）  

**不负责**：调用 LLM、下载网络图（只读任务目录内相对路径）。

目录约定：

```
data/aigen/{taskId}/
  request.txt
  shotlist.json              # 规划结果（含 assetPath 回写后）
  shotlist.plan.json         # 规划原始（未填路径，可选）
  assets/
    visual/
      shot-1.jpg
      shot-2.jpg
    audio/
      bgm.mp3                # 可选
      shot-3.mp3             # 可选 TTS
  output.mp4
```

---

## 7. 数据模型与 API

### 7.1 表字段扩展（`aigen_task`）

建议增量字段（SQL 迁移另附）：

| 字段 | 类型 | 说明 |
|------|------|------|
| pipeline_mode | varchar(16) | `template` / `visual`，默认可先 `template` 保兼容，产品切换后改 `visual` |
| audio_mode | varchar(16) | `none` / `bgm_only` / `tts` |
| style_preset | varchar(64) | 风格预设 |
| shotlist_json | mediumtext | visual 模式主产物；或复用 result/storyboard 字段存 Shotlist |
| shot_count | int | 冗余，列表展示 |
| asset_done_count | int | 出图进度 SSE 用 |

**兼容策略**：

- template 模式继续写 `storyboard_json`  
- visual 模式写 `shotlist_json`（或统一 `plan_json` + `plan_kind`）  

V1 为减少迁移痛苦，可采用：

```
plan_kind: storyboard | shotlist
plan_json: 对应 JSON
```

### 7.2 创建任务请求扩展

```json
POST /api/v1/aigen/tasks
{
  "prompt": "用电影感画面讲清：什么是 AI Agent",
  "options": {
    "pipelineMode": "visual",
    "templateId": null,
    "aspectRatio": "9:16",
    "targetDurationSec": 40,
    "audioMode": "bgm_only",
    "stylePreset": "cinematic-dark",
    "llmProvider": "nvidia",
    "llmModel": "…",
    "imageProvider": "nvidia-flux",
    "enablePromptEnhance": true
  }
}
```

| 字段 | 说明 |
|------|------|
| pipelineMode | `visual` 走本方案；`template` 走旧逻辑 |
| templateId | visual 模式可空；template 模式必填 |
| audioMode | 默认 `none` 或 `bgm_only`（产品定） |

### 7.3 查询 / SSE

- 现有 task 详情扩展：`pipelineMode`、`audioMode`、`shotCount`、`assetDoneCount`  
- SSE `task.status` 增加 `currentStep` 细粒度文案即可  
- 可选事件：`task.shot.progress`（V1.5）

### 7.4 媒体接口

- 沿用 `GET .../media` 播 `output.mp4`  
- 可选：单镜预览 `GET .../shots/{shotId}/image`（调试用）

---

## 8. Director Prompt 设计要点

### 8.1 系统约束（摘要）

1. 只输出 `vt-1.0` JSON。  
2. 镜头 4～8（随时长调整）。  
3. 每镜必须有可拍摄的 **英文画面 prompt**（模型出图更稳）+ 中文叠字。  
4. 禁止连续三镜相同构图描述。  
5. 叙事可参考：钩子 → 展开 → 对比/洞察 → 收束（但不绑定 scene type）。  
6. `audio.mode` 由系统写入，模型勿改（或仅填 narration 当 tts 开启时）。  
7. 不要输出 URL、文件路径、base64。

### 8.2 风格预设（stylePreset）

| preset | 画面倾向 | 叠字风格 |
|--------|----------|----------|
| cinematic-dark | 暗调电影感 | 少字、大标题 |
| clean-tech | 干净科技蓝 | 信息图感 lower-third |
| vibrant-social | 高饱和短视频 | hook-center 多 |
| soft-knowledge | 柔和知识向 | bullets + caption |

预设影响：**Director 的 system 附加说明** + **Remotion 默认调色/字体**。

---

## 9. 前端（okx-trading-web）

### 9.1 生成页改动

| 项 | 说明 |
|----|------|
| 模式切换 | 「模板口播」\|「画面短片」（visual） |
| visual 表单 | 主题、画幅、时长、风格、音频模式 |
| 弱化 | templateId 选择（visual 下隐藏） |
| 进度文案 | 「规划镜头 → 生成画面 n/m → 合成」 |
| 结果页 | 播放器为主；可选镜头缩略图条（V1.5） |

### 9.2 交互原则

- 默认选 **画面短片 + 无配音/仅 BGM**  
- 高级选项折叠：模型、是否润色出图 prompt、TTS 音色  

---

## 10. 配置项（建议）

```yaml
aigen:
  # 默认流水线：visual | template
  default-pipeline-mode: visual
  visual:
    max-shots: 12
    min-shots: 4
    image-concurrency: 2
    fail-on-shot-error: false   # false=单镜降级占位
    default-audio-mode: bgm_only
    default-style-preset: cinematic-dark
    motion-default: ken_burns
    bgm-dir: ./data/aigen/_bgm   # 预置 BGM
  remotion:
    # composition 名
    visual-composition-id: VisualTimeline
```

---

## 11. 安全

| 风险 | 控制 |
|------|------|
| 路径穿越 | assetPath 必须相对任务目录且 canonicalize 在 workDir 内 |
| SSRF | 禁止 shot 带外链 URL；出图仅服务端 Provider |
| 越权 | 任务/媒体 API 校验 userId |
| Prompt 注入 | Schema 白名单 visual.type / motion / layout |
| 资源 | 时长、镜数、出图并发、渲染超时 |
| 内容 | 出图侧沿用现有安全策略；成品仅登录可访问 |

---

## 12. 观测与失败

| 指标 | 来源 |
|------|------|
| plan_ms / asset_ms / render_ms | 现有分步耗时字段 |
| shot_success_rate | asset 成功镜数 / 总镜数 |
| image_provider_error | 日志 + errorMessage 摘要 |
| fallback_placeholder_count | 降级占位次数 |

失败文案示例：

- 规划失败：镜头表无法通过校验  
- 出图失败：画面生成失败（可重试）  
- 渲染失败：合成服务不可用  

重试：整任务重跑；V1.5 支持「只重生失败镜头」。

---

## 13. 实施分期

### Phase VT-0：契约与壳（0.5～1 人日）

- Shotlist JSON schema + Java DTO  
- `pipeline_mode` 字段 / 请求扩展  
- 文档与前端模式开关占位  
- Remotion 空 `VisualTimeline`（纯色序列可播）

### Phase VT-1：MVP 真画面（主交付，约 3～5 人日）

1. `DirectorPort` + LangChain4j Adapter（JSON + validate + repair）  
2. `VisualAssetStep` + FLUX/imggen 复用  
3. Remotion `VisualTimeline`：全屏图 + ken_burns + crossfade + 2～3 种 overlay  
4. `audio.mode=none|bgm_only`  
5. 前端 visual 模式提交 / 进度 / 播放  
6. 与 template 模式开关共存  

**验收**：无 TTS 也能产出「多镜头有画面」的短片。

### Phase VT-1.5：体验增强（已实现）

- TTS 可选挂载（`tts` / `tts_bgm`）+ 时长按口播重排  
- 镜头缩略图 API、单镜重生、用户上传图入镜  
- Remotion：`pan_left` / `pan_right` / `zoom_in` / `zoom_out` / `ken_burns`  
- 出图 prompt 润色（`enhanceImagePrompt`）  
- 前端：镜头条、重生/上传、润色勾选、TTS 音色  


### Phase VT-2：真视频素材

- `visual.type=ai_video` Provider  
- 混剪：图镜 + 视频镜  
- 简单自动字幕（可选 Whisper 对齐）  

### Phase VT-3：智能增强

- 参考视频拆解 → 节奏/风格迁移  
- 角色一致性  
- 粗剪建议 / 多版本输出  

---

## 14. 风险与决策

| # | 决策 | 推荐 |
|---|------|------|
| D1 | 是否废弃模板模式？ | **否**，双轨共存，产品默认可切 visual |
| D2 | Shotlist 是否强绑 AiServices 结构化 DTO？ | **否**，JSON 字符串 + 业务反序列化（SceneProps 教训） |
| D3 | 默认是否 TTS？ | **否** |
| D4 | 出图失败是否整单失败？ | 默认 **否**（占位+warning），可配置 |
| D5 | 是否新建任务表？ | **否** |
| D6 | Remotion 是否多 Composition？ | V1 **一个** VisualTimeline + 皮肤 layout 足够 |
| D7 | 端到端视频模型何时上？ | VT-2 作为 Provider，不替代导演层 |

---

## 15. 测试计划（VT-1）

| 类型 | 用例 |
|------|------|
| 单测 | Shotlist 校验：镜数/时长/非法 type |
| 单测 | assetPath 沙箱拒绝 `../` |
| 集成 | mock Director + mock 出图 + mock Render 跑通状态机 |
| 手工 | real：主题 → 出片；audio=none / bgm_only |
| 回归 | `pipelineMode=template` 旧链路仍可用 |
| 性能 | 6 镜出图并发 2，总时长可接受 |

---

## 16. PR 拆分建议（实施时）

| PR | 内容 |
|----|------|
| PR1 | DTO + schema + DB 字段 + API options |
| PR2 | DirectorPort + Adapter + PlanStep 分支 |
| PR3 | VisualAssetStep + Image 复用 |
| PR4 | Remotion VisualTimeline + Render 对接 |
| PR5 | 前端 visual 模式 + 文案/默认项 |
| PR6 | BGM 与（可选）TTS 挂钩 |

---

## 17. 总结

1. **问题本质**不是模板数量不够，而是「模板=内容」的架构假设错了。  
2. **答案**是 Visual Timeline：镜头表 + 素材工厂 + 剪辑台；模板退化为叠字皮肤。  
3. **音频默认不做**，把算力与产品焦点放在画面。  
4. **与现网共存**：`pipeline_mode` 双轨，任务表/调度/SSE 复用。  
5. **MVP 可落地**：复用 imggen + 一个 Remotion Composition + Director JSON。  

---

## 18. 评审清单（请拍板）

- [ ] 产品默认是否改为 `pipelineMode=visual`？  
- [ ] 默认音频：`none` 还是 `bgm_only`？  
- [ ] 单镜出图失败：占位继续 vs 整单失败？  
- [ ] VT-1 是否必须含 TTS 开关，还是 VT-1.5？  
- [ ] 画幅 V1 是否只做 `9:16`？  

确认后可按 §13 Phase VT-0 → VT-1 开工实现。
