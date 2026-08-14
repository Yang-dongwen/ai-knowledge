# auto-exchange

多模块工程：认证与 AI 工具链（对话、视频提取、AI 成片、文生图等）。

## 目录索引

| 目录 | 说明 | 文档 |
|------|------|------|
| **[okx-bot](./okx-bot/)** | 主后端（Spring Boot 3 / Java 17）。认证、聊天、视频提取、AI 视频生成、文生图 | **[完整操作指南](./okx-bot/README.md)** · [详细设计 doc/](./okx-bot/doc/) |
| **[aigen-remotion](./aigen-remotion/)** | Remotion 模板与 HTTP 渲染服务（默认 `:3100`，可由 okx-bot 托管） | [README](./aigen-remotion/README.md) |
| **[okx-trading-web](./okx-trading-web/)** | Vue 3 前端（AI 工具台 + 知识库 PC 端） | 见该目录 `package.json` 脚本 |
| **[kb-miniprogram](./kb-miniprogram/)** | 知识库微信小程序 | [README](./kb-miniprogram/README.md) |
| **[deploy](./deploy/)** | 部署脚本、compose、环境变量模板 | [deploy/README.md](./deploy/README.md) |

辅助服务（在 okx-bot 内）：

| 路径 | 说明 |
|------|------|
| [okx-bot/whisper-service](./okx-bot/whisper-service/) | 本地 faster-whisper 转录（默认 `:8000`） |
| [okx-bot/docker-compose.video.yml](./okx-bot/docker-compose.video.yml) | Whisper 等视频相关容器编排 |

## 快速入口

- **后端部署 / 外部工具 / 配置 / API / 排障** → 阅读 **[okx-bot/README.md](./okx-bot/README.md)**
- **部署** → **[deploy/README.md](./deploy/README.md)**（先看这一页的四层说明）
  - 本机：[deploy/local/README.md](./deploy/local/README.md)
  - 一键上 AWS：[deploy/aws/README.md](./deploy/aws/README.md)
- **视频提取运行流程 / 工具串联 / Mermaid 一页纸** → [okx-bot/doc/VideoCoreExtractor_视频提取运行流程教程.md](./okx-bot/doc/VideoCoreExtractor_视频提取运行流程教程.md)
- **AI 成片渲染联调** → [aigen-remotion/README.md](./aigen-remotion/README.md) + okx-bot 中 `aigen.remotion` 配置
- **模块级深度文档**（提取、生成、文生图、Auth、LangChain4j 等）→ [okx-bot/doc/](./okx-bot/doc/)

## 默认端口（本地）

| 服务 | 端口 |
|------|------|
| okx-bot | 8080 |
| Halo 博客（AWS 上） | 内部 8090；对外 `https://blog.dwcode.cloud` |
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
