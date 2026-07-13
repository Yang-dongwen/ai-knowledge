# AI 视频生成 — 真模板设计指南

**目标**：回答「第二个（以及以后的）真模板应该怎么设计、怎样更好看」  
**前提**：架构仍是 **LLM 填数据 → Storyboard JSON → Remotion 组件出画**  
**版本**：v1.0 · 2026-07-13  
**关联**：`aigen-remotion`、`TemplateRegistry`、`StoryboardDto`

---

## 0. 一句话原则

> **好看的模板 = 固定的「视觉母版 + 动效语法」+ 严格但够用的「数据槽位」**  
> LLM 只负责填槽；**版式、字体、节奏、层次全部由设计师/前端写死在 Remotion 里**。

不要让模型「自由设计 UI」——那既不稳，也难好看。

---

## 1. 当前为什么「单一」

| 层 | 现状 | 结果 |
|----|------|------|
| Remotion Composition | 只有 `KnowledgeCards` | 画面骨架永远一套 |
| 场景类型 | 仅 `title` / `bullets` / `outro` | 镜头语言只有 3 种 |
| 视觉 | 同一渐变底 + 中心字 + 圆点列表 | 换文案仍像同一条片子 |
| 注册表 | 多个 templateId 共用 KnowledgeCards | **假多模板** |

所以「真模板」至少要满足：

1. **独立 CompositionId**（如 `InsightCompare`，不是换个中文名）  
2. **独立场景类型集合**（或同一 composition 内明显不同的 type 布局）  
3. **独立 prompt 约束**（告诉 LLM 该怎么填这些槽）  
4. **独立视觉语言**（颜色/版式/动效至少有一项与 KnowledgeCards 拉开）

---

## 2. 模板设计的三层结构（推荐固定下来）

每个真模板按三层想，不要一上来画页面：

```
┌─────────────────────────────────────┐
│ ① 叙事骨架 Narrative                │  这条片子讲什么故事结构？
│    开场 → 展开 → 高潮/对比 → 收尾    │
├─────────────────────────────────────┤
│ ② 场景类型 Scene Types              │  有哪些「镜头模具」？
│    hook / compare / metric / …      │
├─────────────────────────────────────┤
│ ③ 视觉系统 Visual System            │  字体、色、间距、动效、字幕位
└─────────────────────────────────────┘
```

### 2.1 叙事骨架（先定「片感」）

| 模板定位 | 典型节奏 | 适合内容 |
|----------|----------|----------|
| **知识卡片**（现有） | 封面 → 3～5 条要点 → CTA | 科普、清单 |
| **洞察对比**（推荐第 2 个） | 钩子 → 左右/前后对比 → 结论 → CTA | 选型、利弊、误区 vs 正解 |
| **时间线旅程** | 起点 → 步骤 1..n → 成果 | 教程、路线、演进史 |
| **数据看板** | 总览数字 → 拆分指标 → 结论 | 报告、成绩单风格 |

**一个模板只服务一种叙事**，不要做成「万能神器」。万能 = 又回到单一。

### 2.2 场景类型（数据契约的核心）

每个 type = **一个 React 子组件 + 固定 props 形状**。

设计时问三句：

1. 口播在念什么？（`narration`）  
2. 画面上**最多**出现几个信息块？（超过 4 块就该拆场景）  
3. 这些字段 LLM 能不能稳定填？（避免嵌套过深）

### 2.3 视觉系统（好看的工程化）

每个模板固定一套 **Design Tokens**，不要每场自己发明：

| Token | 建议 | 说明 |
|-------|------|------|
| 画幅 | 9:16 主，可选 16:9 | 竖屏短视频优先 |
| 安全区 | 上下各 ≥ 8%、左右 ≥ 6% | 防裁切、给字幕留位 |
| 主色 | 1 个 primary + 1 个 accent | 来自 `style.primaryColor`，其余写死 |
| 字体阶梯 | 显示标题 56–72 / 正文 32–40 / 辅助 24–28 | **少档位**，显得专业 |
| 字重 | 展示用 700–800，正文 500–600 | 中文避免过细 |
| 圆角 / 卡片 | 统一 16–24 | 界面感来自卡片，不是花边 |
| 动效 | 统一缓动：淡入 + 轻微位移 12～20 帧 | 忌每元素不同 easing |
| 字幕 | 底部固定槽，不与关键数字重叠 | 全局一层即可 |

---

## 3. 怎样设计才「更好看」——可执行清单

### 3.1 版式：层次 > 装饰

| 做得好 | 做得差 |
|--------|--------|
| 一屏 **1 个主信息 + 最多 3 个辅信息** | 一屏塞满 8 条 bullet |
| 大标题 + 小标签（eyebrow） | 满屏同样大的字 |
| 卡片/分栏制造「界面感」 | 只有居中一段字 |
| 留白 30%+ | 贴边、密、挤 |

短视频的「高级感」多半来自：**大字号 + 留白 + 明确主次**，不是来自更多特效。

### 3.2 动效：克制、同构、跟节奏

```
入场 0～15 帧：背景稳、主标题 opacity + translateY
  15～40 帧：内容块依次 stagger（间隔 6～10 帧）
  收尾前 10 帧：可轻微 hold，不要再跳新元素
```

原则：

- **同一 type 的入场方式永远一致**（用户潜意识认「这是对比页」）  
- 动效服务阅读顺序：先看标题，再看左右/数字，最后 CTA  
- 避免：360 旋转、夸张弹跳、满屏粒子（Remotion 能做，但不适合口播知识）

### 3.3 颜色：少而准

KnowledgeCards 现在是「深蓝紫渐变 + 一点 primary」。第二模板要**故意换语言**，否则用户仍觉得一样：

| 模板 | 建议调性 |
|------|----------|
| KnowledgeCards | 深色科技渐变（保持） |
| InsightCompare | **浅色纸感 / 或高对比深底 + 双边色块**（左冷右暖） |
| Timeline | 深底 + 轴线 + 节点高亮 |

左右对比用 **冷暖分色**（如左 `#64748b` 右 `#22c55e`）比换一句文案更有辨识度。

### 3.4 信息设计：为口播服务

- 画面字 **短于** 口播：`props` 是摘要，`narration` 是展开  
- 数字单独放大（`metric` 场景）：`88%` 用 96px，说明用 28px  
- 列表 **3 条黄金**（竖屏）；4 条以上拆两场  

### 3.5 节奏：模板规定「场次数」

在 prompt / validate 里写死偏好：

| 时长 | 建议场数 | 结构示例（对比模板） |
|------|----------|----------------------|
| 20–30s | 4–5 | hook → compare → insight → outro |
| 30–45s | 5–7 | hook → compare → compare → metric → outro |
| 45–90s | 6–8 | 可多一场 deepdive，仍忌连续 4 场同 type |

**禁止** LLM 连续输出 5 个 bullets——那是单一感的元凶。

---

## 4. 推荐首个真模板：`InsightCompare`（洞察对比）

### 4.1 为什么选它做第 2 个

| 理由 | 说明 |
|------|------|
| 和 KnowledgeCards **视觉差最大** | 分栏/对撞布局，不是再列一遍 bullet |
| 内容覆盖广 | 优缺点、前后对比、误区正解、A vs B |
| 契约简单 | props 仍是字符串为主，LLM 不易翻车 |
| 实现量可控 | 约 4～6 个场景组件，1～2 天可出片 |

### 4.2 叙事骨架

```
hook（钩子提问/痛点）
  → compare（核心对比，可 1～2 场）
  → insight 或 metric（结论 / 关键数字）
  → outro（行动号召）
```

### 4.3 场景类型与 Props 契约

| type | 画面职责 | props（建议） | 口播 |
|------|----------|---------------|------|
| `hook` | 大问题 / 痛点句 | `eyebrow`, `title`, `subtitle?` | 抛出冲突 |
| `compare` | 左右两栏 | `leftLabel`, `rightLabel`, `leftItems[]`, `rightItems[]`, `heading?` | 对照说明 |
| `insight` | 结论条 + 3 点内 | `heading`, `items[]`（≤3） | 总结 takeaway |
| `metric` | 大数字强调 | `value`, `unit?`, `label`, `hint?` | 强化记忆点 |
| `outro` | 收尾 CTA | `title`, `cta` | 关注/行动 |

**刻意没有 `bullets` 主类型**——逼规划走对比叙事，避免又变回知识点列表。

### 4.4 Storyboard 示例（片段）

```json
{
  "meta": { "templateId": "insight-compare", "fps": 30, "width": 1080, "height": 1920 },
  "style": { "theme": "compare-duo", "primaryColor": "#0ea5e9" },
  "scenes": [
    {
      "id": "s1",
      "type": "hook",
      "narration": "很多人学 AI，一上来就追最新模型，结果三个月还是不会落地。",
      "props": {
        "eyebrow": "常见误区",
        "title": "先追模型，还是先追场景？",
        "subtitle": "差的是路径，不是智商"
      }
    },
    {
      "id": "s2",
      "type": "compare",
      "narration": "左边是堆概念，右边是拿一个真实任务跑通最小闭环。",
      "props": {
        "heading": "两种学习路径",
        "leftLabel": "无效路径",
        "rightLabel": "有效路径",
        "leftItems": ["只收藏教程", "频繁换模型", "没有验收标准"],
        "rightItems": ["锁定一个场景", "最小可运行版本", "用结果迭代"]
      }
    },
    {
      "id": "s3",
      "type": "metric",
      "narration": "把范围收窄以后，两周就能做出第一个能演示的版本。",
      "props": { "value": "14", "unit": "天", "label": "从零到可演示", "hint": "范围足够小的时候" }
    },
    {
      "id": "s4",
      "type": "outro",
      "narration": "先选场景，再选工具。关注我，下期拆一条可复制的落地清单。",
      "props": { "title": "先场景，后模型", "cta": "关注 · 下期清单" }
    }
  ]
}
```

### 4.5 视觉草图（竖屏）

**hook**

```
┌─────────────────────┐
│  小标签 eyebrow      │
│                     │
│   超大标题           │
│   （最多两行）       │
│                     │
│   灰色副标题         │
│                     │
│         ▂ 字幕槽     │
└─────────────────────┘
```

**compare**

```
┌─────────────────────┐
│  heading            │
│  ┌──────┐ ┌──────┐  │
│  │ 左栏  │ │ 右栏  │  │
│  │ 冷色  │ │ 暖色  │  │
│  │ ···   │ │ ···   │  │
│  └──────┘ └──────┘  │
│         ▂ 字幕槽     │
└─────────────────────┘
```

**metric**

```
┌─────────────────────┐
│                     │
│       14 天          │  ← 巨型数字
│   从零到可演示       │
│   一行 hint          │
│         ▂            │
└─────────────────────┘
```

### 4.6 动效语法（写进组件，不交给 LLM）

| 场景 | 动效 |
|------|------|
| hook | 标题 0→18 帧上移淡入；eyebrow 更早 4 帧 |
| compare | 左栏从左入、右栏从右入（对称）；条目 stagger |
| metric | 数字 scale 0.85→1 + 可选简易 count-up（可选 P1） |
| outro | 轻微 scale + CTA 按钮延迟出现 |

---

## 5. 工程落点（实现时按此接线）

### 5.1 文件与注册

```
aigen-remotion/src/
  templates/
    KnowledgeCards.tsx          # 保留
    InsightCompare.tsx          # 新 Composition 根组件
    insight/
      HookScene.tsx
      CompareScene.tsx
      MetricScene.tsx
      InsightScene.tsx
      OutroScene.tsx
  Root.tsx                      # 注册 Composition id="InsightCompare"
  types.ts                      # 扩展 SceneProps 字段

okx-bot:
  TemplateRegistry              # insight-compare → compositionId InsightCompare
                                # allowedSceneTypes = hook,compare,insight,metric,outro
  SceneProps.java               # 增加 leftLabel/rightLabel/...（可空字段，兼容旧模板）
  LlmChatScriptPlanAdapter      # 按 templateId 换 system prompt 片段
  StoryboardValidateService     # 按 type 校验必填 props
```

### 5.2 扩展 Props 的原则

- **可空字段累加**，不要为每个模板造一套完全不同的 JSON 根结构  
- 旧字段 `title/items/cta` 保留，KnowledgeCards 零破坏  
- 新字段只在新 type 使用；validate 按 type 检查  

### 5.3 Prompt 要按模板分叉

不要再用「万能 bullets 提示词」。每个模板一份 **scene recipe**：

```
你正在写 insight-compare 模板分镜。
必须使用的结构：hook → 至少 1 个 compare → insight 或 metric → outro。
禁止连续 3 个同类场景。
compare 的 leftItems/rightItems 各 2～4 条，短语，每条≤16字。
...
```

**模板差异的一半在 prompt，一半在组件。**

### 5.4 前端

创建任务时选模板 `insight-compare`，后端 `compositionId` 已映射即可；播放逻辑不用改。

---

## 6. 设计评审清单（每个新模板过一遍）

**叙事**

- [ ] 30 秒内能否讲完一个完整观点？  
- [ ] 是否避免「全是列表」？  

**契约**

- [ ] 每个 type 的必填 props ≤ 6 个字段？  
- [ ] LLM 填错时 validate 能指出「哪场缺什么」？  
- [ ] items 数量有上下限？  

**视觉**

- [ ] 静帧截图能否 1 秒内认出「这是对比片 / 知识片」？  
- [ ] 9:16 与 16:9 是否都做了安全区？  
- [ ] 字幕是否挡住数字/双栏？  

**动效**

- [ ] 同 type 入场一致？  
- [ ] 单场动效是否在前 40 帧内完成阅读引导？  

**工程**

- [ ] 独立 CompositionId？  
- [ ] TemplateRegistry + prompt + validate 三处对齐？  
- [ ] 有一份手写 golden storyboard 可无 LLM 渲通？  

---

## 7. 好看的反模式（尽量别做）

| 反模式 | 为什么糟 |
|--------|----------|
| 一个模板 12 种 type | LLM 选错 type，画面更乱 |
| props 里塞 HTML/颜色/字号 | 模型乱设计，品牌崩坏 |
| 为「炫」上 3D/粒子 | 口播知识片信息密度被抢 |
| 假多模板（同 composition） | 用户觉得换汤不换药 |
| 画面文字 = 全文口播 | 密、丑、读不过来 |
| 先写组件后定契约 | 前后端/LLM 对不齐，返工 |

---

## 8. 路线图建议

| 阶段 | 做什么 | 观感收益 |
|------|--------|----------|
| **现在** | 落地 `InsightCompare` 真模板 | ⭐⭐⭐⭐ 最大 |
| 随后 | KnowledgeCards 小升级：eyebrow、卡片底、条数限制 | ⭐⭐ 旧模板也提升 |
| 再后 | `ProcessTimeline`（步骤轴） | ⭐⭐⭐ 第三种片感 |
| 素材 | BGM 音量槽、轻量图标集（固定 SVG，不 AI 乱画） | ⭐⭐ |
| 规划 | 多 type 稳定后再考虑 LangChain4j | 稳，不直接好看 |

---

## 9. 总结

1. **真模板 = 独立 Composition + 独立场景类型 + 独立视觉 + 独立 prompt**，缺一仍是换皮。  
2. **更好看**靠：留白与字阶、一屏少信息、冷暖/分栏制造界面感、克制同构动效、模板规定叙事节奏——**不是靠更聪明的模型**。  
3. **首推第二模板 `InsightCompare`**：钩子 → 对比 → 数字/洞察 → 收尾，和现有知识卡片一眼能分清。  
4. LLM 永远只填槽；**美是设计进组件的**。

---

## 10. 下一步实现时可直接开工的任务包

1. 扩展 `SceneProps` / `types.ts` 字段  
2. 实现 `InsightCompare` 五个场景组件 + Root 注册  
3. `TemplateRegistry` 增加 `insight-compare`  
4. 分模板 system prompt + validate  
5. 手写 `golden-insight-compare.json` 渲一条样片验收  
6. 前端模板列表确认可选  

若确认按本指南实施，实现阶段以本文 §4～§5 为准。
