# 知识库 → Halo 发文（代码入口）

- **本机怎么跑**：仓库根 [README.md](../../README.md)

## 代码入口

| 层 | 位置 |
|---|---|
| 配置 | `halo.*`（平台站，仅 `SUPER_ADMIN`）；`HALO_TOKEN_SECRET` 加密用户 PAT |
| 解析 | `blog.HaloClientResolver`：超管走 yml，其他人必须 `user_halo_binding` |
| 绑定 | `GET/PUT/DELETE /api/v1/kb/blog/binding` |
| 端口 | `com.dwcode.okxbot.blog.port.HaloPublishPort` |
| HTTP | `blog.adapter.HaloHttpPublishAdapter`（按用户现场构造） |
| 未绑定 | 普通用户 `403` 请先关联博客账户 |
| 业务 | `kb.service.KbBlogPublishService` |
| 正文改写 | `kb.service.KbHaloContentRewriter`（仅改发往 Halo 的副本，不改知识库原文） |
| HTTP | `GET /api/v1/kb/notes/{id}/publish-blog`（分类/标签选项） |
| HTTP | `POST /api/v1/kb/notes/{id}/publish-blog`（body：`categoryNames` / `tagNames` 展示名） |
| 表 | Flyway `V6__kb_note_halo.sql` |

## 图片与附件

发文前扫描正文 `/api/v1/kb/files/{id}/content` 以及该笔记已绑定附件，经 Halo UC 附件接口上传，再用公开 permalink 替换正文。未插入正文的附件追加在文末「附件」列表。知识库原文保持私有路径。正文第一张图（没有则用第一张图片附件）写入 Halo `spec.cover` 作为封面。

## 分类与标签

选项来自 Halo 站点（`/apis/content.halo.run/v1alpha1/categories|tags`），不是知识库文件夹。发文弹窗可多选；新标签名会在 Halo 创建。已发布文章回填当前分类/标签。

## 谁能发到哪

- `SUPER_ADMIN`：平台博客（yml 的 `halo.base-url` / `halo.token`）。yml 没配则超管也不能发。
- `USER` / `MEMBER`：自己绑定的 Halo 站点 + PAT。不回落平台令牌。未绑定禁止发布。
- 工具台不代开 Halo 账号。用户在自己的站点建号、开 PAT，再到个人资料或发布弹窗里关联。

表：Flyway `V7__user_halo_binding.sql`。

不要把 Halo 源码或 JAR 引进本模块。改博客页面用 Halo 主题，不改这里。
