# 知识库 → Halo 发文（代码入口）

- **本机怎么跑**：仓库根 [README.md](../../README.md)

## 代码入口

| 层 | 位置 |
|---|---|
| 配置 | `halo.*`（`application.yml` / `application-ec2.yml`） |
| 端口 | `com.dwcode.okxbot.blog.port.HaloPublishPort` |
| HTTP | `blog.adapter.HaloHttpPublishAdapter` |
| 未配置 | `blog.adapter.DisabledHaloPublishAdapter` |
| 业务 | `kb.service.KbBlogPublishService` |
| HTTP | `POST /api/v1/kb/notes/{id}/publish-blog` |
| 表 | Flyway `V6__kb_note_halo.sql` |

不要把 Halo 源码或 JAR 引进本模块。改博客页面用 Halo 主题，不改这里。
