# 知识库 → Halo 发文（代码入口）

- **本机怎么跑**：[deploy/docs/local-run.md](../../deploy/docs/local-run.md)  
- **服务器怎么部署**：[deploy/docs/remote-deploy.md](../../deploy/docs/remote-deploy.md)  
- 细节与排障：[deploy/docs/halo-blog.md](../../deploy/docs/halo-blog.md)

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
