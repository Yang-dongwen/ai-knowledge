# auto-exchange

多模块工程：认证与 AI 工具链（对话、视频提取、AI 成片、文生图等）。

## 目录索引

| 目录 | 说明 | 文档 |
|------|------|------|
| **[okx-bot](./okx-bot/)** | 主后端（Spring Boot 3 / Java 17）。认证、聊天、视频提取、AI 视频生成、文生图 | **[完整操作指南](./okx-bot/README.md)** · [详细设计 doc/](./okx-bot/doc/) |
| **[aigen-remotion](./aigen-remotion/)** | Remotion 模板与 HTTP 渲染服务（默认 `:3100`，可由 okx-bot 托管） | [README](./aigen-remotion/README.md) |
| **[okx-trading-web](./okx-trading-web/)** | Vue 3 前端（AI 工具台） | 见该目录 `package.json` 脚本 |
| **[polymarket-ai-trader](./polymarket-ai-trader/)** | 独立 Python 交易脚本（与 okx-bot 无强耦合） | [docs/](./polymarket-ai-trader/docs/) |
| [desgin/](./desgin/) · [first/](./first/) · [doc/](./doc/) | 早期设计稿、方案与运营类文档 | 按需查阅 |

辅助服务（在 okx-bot 内）：

| 路径 | 说明 |
|------|------|
| [okx-bot/whisper-service](./okx-bot/whisper-service/) | 本地 faster-whisper 转录（默认 `:8000`） |
| [okx-bot/docker-compose.video.yml](./okx-bot/docker-compose.video.yml) | Whisper 等视频相关容器编排 |

## 快速入口

- **后端部署 / 外部工具 / 配置 / API / 排障** → 阅读 **[okx-bot/README.md](./okx-bot/README.md)**
- **一键部署（本机 / GitHub）** → **[deploy/CI_CD.md](./deploy/CI_CD.md)** · [app.env.example](./deploy/app.env.example)
  - 本机：`pwsh deploy/scripts/deploy-local.ps1`
  - GitHub：push `main` 或 Actions → Deploy EC2
- **视频提取运行流程 / 工具串联 / Mermaid 一页纸** → [okx-bot/doc/VideoCoreExtractor_视频提取运行流程教程.md](./okx-bot/doc/VideoCoreExtractor_视频提取运行流程教程.md)
- **AI 成片渲染联调** → [aigen-remotion/README.md](./aigen-remotion/README.md) + okx-bot 中 `aigen.remotion` 配置
- **模块级深度文档**（提取、生成、文生图、Auth、LangChain4j 等）→ [okx-bot/doc/](./okx-bot/doc/)

## 默认端口（本地）

| 服务 | 端口 |
|------|------|
| okx-bot | 8080 |
| whisper-service | 8000 |
| aigen-remotion | 3100 |
| okx-trading-web（dev） | 以 Vite 配置为准 |

## 技术栈一览

```text
okx-trading-web (Vue)  ──REST/SSE──►  okx-bot (Spring Boot)
                                         │
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
              MySQL / JWT          yt-dlp + FFmpeg      NVIDIA 等 LLM/FLUX
                                         │
                              whisper-service · aigen-remotion
```

## 建议阅读顺序

1. 本页（仓库地图）
2. [okx-bot/README.md](./okx-bot/README.md)（从零跑起后端）
3. 按功能深入 `okx-bot/doc/` 对应手册
