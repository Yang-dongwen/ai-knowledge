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

## 说明

- Phase 1 仅 `KnowledgeCards` Composition
- 暂不依赖真实音频文件（字幕条展示 narration）
- 首次渲染会 webpack bundle，较慢
