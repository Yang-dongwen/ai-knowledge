# AI 视频生成 Phase 1 — 使用说明

**目标**：提示词 → 分镜（LLM）→ 素材时间轴（语音后补）→ Remotion 成片 → 网页播放  
**日期**：2026-07-13  

---

## 1. 你需要启动的进程

| 进程 | 命令 | 说明 |
|------|------|------|
| MySQL | — | 已有 `aigen_task` 表 |
| okx-bot | IDE / `mvn spring-boot:run` | 编排 + LLM；**默认可自动托管 remotion** |
| aigen-remotion | 一般无需手动开 | 见下方「进程托管」 |
| okx-trading-web | `npm run dev` | 页面 |

### 渲染服务：Java 自动托管（默认）

配置（`application.yml`）：

```yaml
aigen:
  remotion:
    manage-process: true       # 由 Java 拉起/停止 Node
    auto-start-on-boot: true   # Boot 就绪后启动；false=首次渲染再启
    project-dir: ../aigen-remotion
    node-path: node
    startup-timeout-seconds: 180
```

**首次**仍需在工程目录安装依赖（只需一次）：

```bash
cd aigen-remotion
npm install
```

之后正常启动 **okx-bot** 即可：

- 启动时（或首次渲染时）自动 `node server/index.mjs`
- 关闭 Spring 时停止**本进程拉起**的 Node（不会误杀你手动开的实例）
- 若 `3100` 上已有健康服务，则不重复启动

不需要托管时：`manage-process: false`，并自行：

```bash
cd aigen-remotion
npm run render-server
```

健康检查：`http://127.0.0.1:3100/health`（首次 bundle 可能较慢）。

---

## 2. 关键配置（application.yml）

```yaml
aigen:
  mock-pipeline: false
  max-duration-sec: 90
  steps:
    plan: real      # LLM 分镜；无 key 时可改 mock
    asset: mock     # 语音后补，保持 mock
    render: real    # 需 remotion；未启动可改 mock 只跑通状态
  remotion:
    base-url: http://127.0.0.1:3100
  work-dir: ./data/aigen   # 建议生产改绝对路径
```

| 组合 | 效果 |
|------|------|
| 全 mock | `mock-pipeline: true` |
| 只测 LLM | plan=real, asset=mock, render=mock |
| 完整出片+配音 | plan=real, asset=real, render=real |

LLM 复用 `ai.providers`（如 nvidia）与库表 `ai_model_config`。

### 配音（TTS）

```yaml
aigen:
  steps:
    asset: real
  tts:
    provider: auto              # auto | edge | windows | mock
    default-voice: zh-CN-XiaoxiaoNeural
```

**推荐安装 Edge-TTS（中文效果好）：**

```bash
pip install edge-tts
edge-tts --version
```

未安装时 `provider=auto` 会在 Windows 上回退系统 SAPI（质量一般，依赖系统中文语音包）。

详见：`AI视频生成_语音接入方案.md`。

---

## 3. 前端验证

1. 登录 → **AI 工具 → AI 视频生成**
2. 输入提示词，时长 ≤ 90s，点生成
3. 观察步骤：规划 → 素材 → 渲染 → 成功
4. 成功且有成片：点 **加载播放** / **下载 MP4**
5. 可查看 mock 分镜 JSON

播放使用 `fetch + Authorization → blob`，JWT 不进 URL。

---

## 4. 架构落点（相对 Phase 0）

- Pipeline 步骤化：`PlanStep` / `AssetStep` / `RenderStep`
- 端口：`ScriptPlanPort` / `TtsPort` / `VideoRenderPort`（配置切换）
- Storyboard 校验 + 规范化
- 路径安全：`assets/` 下解析、禁止 `..`
- 媒体接口：`GET /api/v1/aigen/tasks/{id}/media/output`
- 工程：`aigen-remotion/`（不进 Vue）
- **真模板**：
  - `knowledge-cards` → Composition `KnowledgeCards`（title/bullets/outro）
  - `insight-compare` → Composition `InsightCompare`（hook/compare/insight/metric/outro）
- TTS：`auto` 优先 Edge-TTS，否则 Windows SAPI；可用 mock

设计说明见：`AI视频生成_真模板设计指南.md`

---

## 5. 常见问题

| 现象 | 处理 |
|------|------|
| 规划失败 / 无模型 | 配置 api-key；模型管理加模型；或 `steps.plan: mock` |
| 渲染失败连不上 | 启动 `npm run render-server`；或 `steps.render: mock` |
| 成功但无播放 | 看是否有 `output.mp4`；render 是否为 real |
| JSON 非法 | LLM 不稳：可换模型或暂时 plan=mock |

---

## 6. 相关文档

- 实施规格：`AI视频生成_Phase1_架构设计.md`
- 极简认知：`AI视频生成_极简说明.md`
