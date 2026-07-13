# aigen-remotion

AI 视频生成 — Remotion 模板与 HTTP 渲染服务（Phase 1）。

## 启动

```bash
cd aigen-remotion
npm install
npm run render-server
```

默认：`http://127.0.0.1:3100`

- `GET /health`
- `POST /render`

可选环境变量：

| 变量 | 说明 |
|------|------|
| `PORT` | 默认 3100 |
| `HOST` | 默认 127.0.0.1 |
| `MAX_CONCURRENT_RENDERS` | 默认 1 |
| `AIGEN_RENDER_TOKEN` | 与 Java `aigen.remotion.render-token` 一致 |
| `ALLOWED_WORK_ROOT` | 限制 workDir 根路径（建议生产配置） |

## Studio 预览

```bash
npm run dev
```

## 与 Java 联调

1. Java `aigen.steps.render=real`
2. `aigen.work-dir` 使用**绝对路径**更稳妥（与 Node 读同一目录）
3. 本服务与 okx-bot 同机运行

## Compositions（真模板）

| CompositionId | 模板 ID（Java） | 场景类型 | 说明 |
|---------------|-----------------|----------|------|
| `KnowledgeCards` | `knowledge-cards` 等 | title / bullets / outro | 知识卡片列表风 |
| `InsightCompare` | `insight-compare` | hook / compare / insight / metric / outro | 洞察对比（左右分栏） |

Studio 预览第二个模板：打开 Remotion Studio 后选择 **InsightCompare**。

Golden 样片数据：`golden/insight-compare.storyboard.json`

## 说明

- 音频经 `http://127.0.0.1:3100/media/{taskId}/...` 注入
- 首次渲染会 webpack bundle，较慢
- 新增模板需：Remotion Composition + Java `TemplateRegistry` + prompt/validate 对齐
