# Halo 博客旁挂与知识库发文

**状态（2026-08-14）**：代码与服务器侧（库、容器、管理员、PAT）已落地；**只差 DNS** `blog.dwcode.cloud` A → `13.201.82.24`。

**先看这两份（更通俗）：**

- [local-run.md](./local-run.md) — 本机怎么跑  
- [remote-deploy.md](./remote-deploy.md) — 服务器怎么部署、脚本干了什么  

本文是补充：决策、代码位置、服务器操作流水、排障。

---

## 1. 决策（为什么这么接）

| 项 | 选择 | 原因 |
|---|---|---|
| 进程 | Halo **独立容器**，不并进 `okx-bot` | Halo 是 Java 21 + WebFlux；现网是 Java 17 + MVC。合进一个 JVM 不可行 |
| 源码 | 官方镜像 `halohub/halo:2.25`，**不**克隆 `halo-me` | 体积大、GPL、难升级；改页面用主题/插件 |
| 对外地址 | `https://blog.dwcode.cloud` | Halo **不支持**反代到子目录（如 `/blog`） |
| 打通方式 | `okx-bot` HTTP + PAT 发文 | 单向薄适配器；不共享用户表、不共享库 |
| 发布入口 | 知识库笔记「发布到博客」 | 笔记是可编辑成稿；文章提取先入库再发 |
| 同机 | 与工具台挤在 **2G EC2** | Halo 堆 256M、容器上限 420M；会用一点 swap |

**明确不做**

- 把 Halo JAR / 源码引进 `okx-bot`
- 用户 SSO / 共用 JWT
- 文章提取一键发博客（后续）
- 笔记私有附件自动上传 Halo（v1 原样发出，响应里标 `unresolvedMedia`）

---

## 2. 架构

```text
okx-trading-web  /kb  「更多 → 发布到博客」
        │ JWT
        ▼
okx-bot  POST /api/v1/kb/notes/{id}/publish-blog
        │
        │  HaloPublishPort（唯一认识 Halo 的地方）
        │  Authorization: Bearer pat_…
        ▼
halo :8090  （Docker 网络内；不映射宿主机端口）
        ▲
        │  Caddy  blog.dwcode.cloud
浏览器 ─┘
```

数据：读笔记标题/正文/格式 → Halo UC API 建或更新 Post → 回写 `kb_note.halo_post_name` / `halo_permalink` / `halo_published_at`。再次发布走更新。

发文协议对标 [vscode-extension-halo](https://github.com/halo-sigs/vscode-extension-halo)：

1. `POST /apis/uc.api.content.halo.run/v1alpha1/posts`（annotation `content.halo.run/content-json`）
2. 已有 `halo_post_name` 则 GET/PUT 文章 + PUT draft
3. `publish-on-create=true` 时再 `PUT .../publish`

正文：`html` → `rawType=HTML`；`markdown` → `rawType=MARKDOWN`。

---

## 3. 仓库里改了什么

### 3.1 部署

| 路径 | 作用 |
|---|---|
| `deploy/stack/compose.blog.yml` | Halo overlay，`--profile blog` |
| `deploy/stack/Caddyfile` | 站点 `blog.dwcode.cloud` → `halo:8090` |
| `deploy/scripts/server-deploy.sh` / `up.sh` | 发现 `compose.blog.yml` 则带 `--profile blog` |
| `deploy/scripts/init-rds.sh` | 额外 `CREATE DATABASE halo` |
| `deploy/scripts/bootstrap-halo.sh` | 初始化站点 + 写 PAT（一次性/补救） |
| `deploy/env/app.env.example` | Halo 变量模板（无密钥） |

`server-deploy` 仍会给 `okx-bot` 生成 **绝对路径** `env_file` override。只 `docker compose up` 某一个服务、漏掉这个 override 时，会出现 `jdbcUrl, ${SPRING_DATASOURCE_URL}`，容器反复重启。正确做法是再跑一遍 `server-deploy.sh`（`SKIP_GIT=1` 即可）。

### 3.2 后端（`okx-bot`）

| 路径 | 作用 |
|---|---|
| `com.dwcode.okxbot.blog.*` | `HaloProperties`、`HaloPublishPort`、HTTP / Disabled 适配器、`SlugUtil` |
| `KbBlogPublishService` | 鉴权属主 → Port → 回写三字段 |
| `POST /api/v1/kb/notes/{id}/publish-blog` | 已登录即可 |
| `db/migration/V6__kb_note_halo.sql` | `halo_post_name` / `halo_permalink` / `halo_published_at` |
| `application.yml` / `application-ec2.yml` | `halo.*` 配置 |

未配 `HALO_PAT` 或 `HALO_ENABLED=false` 时走 `DisabledHaloPublishAdapter`，接口 503，知识库其它功能不受影响。

### 3.3 前端（`okx-trading-web`）

知识库「更多」菜单：未发过显示「发布到博客」，已有 `haloPermalink` 显示「更新到博客」。成功后 toast，并打开 permalink。

小程序 / `kb-mobile` 未改。

### 3.4 测试

`SlugUtilTest`、`HaloHttpPublishAdapterTest`（MockWebServer）、`KbBlogPublishServiceTest`。

---

## 4. 配置项

写在 `deploy/env/app.env`（**不提交**）。模板见 `app.env.example`。

| 变量 | 含义 |
|---|---|
| `HALO_ENABLED` | 总开关；还须有 PAT |
| `HALO_PAT` | 个人令牌 `pat_…` |
| `HALO_BASE_URL` | 容器内访问 Halo，compose overlay 设为 `http://halo:8090` |
| `HALO_PUBLIC_BASE_URL` | 对外站点，拼 permalink |
| `HALO_R2DBC_URL` | `r2dbc:pool:mysql://{RDS}:3306/halo` |
| `HALO_DB_USERNAME` / `HALO_DB_PASSWORD` | 可与工具台同一 RDS 用户 |
| `HALO_JVM_OPTS` | 须加引号，例如 `"-Xms128m -Xmx256m"`（中间有空格） |
| `HALO_ADMIN_USERNAME` / `HALO_ADMIN_PASSWORD` | 控制台登录；密码只放 env |
| `HALO_ADMIN_EMAIL` / `HALO_SITE_TITLE` | 初始化用 |

Spring 侧：

```yaml
halo:
  enabled: ${HALO_ENABLED:false}
  base-url: ${HALO_BASE_URL:http://127.0.0.1:8090}
  token: ${HALO_PAT:}
  public-base-url: ${HALO_PUBLIC_BASE_URL:https://blog.dwcode.cloud}
  publish-on-create: ${HALO_PUBLISH_ON_CREATE:true}
```

---

## 5. 服务器上已经做过的事（2026-08-14）

主机：EC2 `13.201.82.24`，2G RAM，项目 `/home/ubuntu/auto-exchange`。

1. 同步 `app.env`（Halo 段），去掉 BOM；`HALO_JVM_OPTS` 加上引号。
2. `init-rds.sh`：RDS 新建空库 `halo`（`okx_bot` 未动）。
3. 上传 `compose.blog.yml`、Caddyfile、发版脚本、`okx-bot` / 前端源码。
4. `server-deploy.sh SKIP_GIT=1`：拉 `halohub/halo:2.25`，重建 okx-bot / web。
5. 宿主机 **8090 已被占用**，因此 **不映射** Halo 端口；只在 Docker 网络 `auto-exchange-lite_appnet` 里用 `http://halo:8090`。
6. 强制重建 Caddy，加载 `blog.dwcode.cloud` 站点。
7. 等 Halo `Started Application`（约 40s）。
8. 初始化走官方表单，不是 JSON API：
   - `GET /system/setup` 取 CSRF
   - `POST /system/setup`（`application/x-www-form-urlencoded`）
   - 字段：`language=zh-CN`、`externalUrl=https://blog.dwcode.cloud`、`siteTitle`、`username`、`email`、`password`
   - 成功：**HTTP 204**
9. Basic Auth 已开（`HALO_SECURITY_BASIC_AUTH_DISABLED=false`）。  
   `POST /apis/uc.api.security.halo.run/v1alpha1/personalaccesstokens` 建 PAT，写入服务器与本机 `app.env` 的 `HALO_PAT`。
10. 用绝对路径 `env_file` override 重建 `okx-bot`。Flyway **V6 kb note halo** 已 success。容器内 `HALO_ENABLED=true`，PAT 已注入。

当时容器：

| 容器 | 状态 |
|---|---|
| `auto-exchange-lite-halo-1` | Up，仅 `8090/tcp` 在内部网络 |
| `auto-exchange-lite-okx-bot-1` | healthy |
| `auto-exchange-lite-web-1` | healthy，宿主机 `8088` |
| `auto-exchange-lite-caddy-1` | 80/443 |

内存约：已用 1.2G / 可用 ~650M / swap ~500M。紧，但能同居。

---

## 6. 你还要做的（仅 DNS）

DNSPod：

| 主机记录 | 类型 | 记录值 |
|---|---|---|
| `blog` | A | `13.201.82.24` |

不要开 Cloudflare 橙云。Caddy 会自动签 `blog.dwcode.cloud` 证书。

生效后：

- 前台 https://blog.dwcode.cloud
- 后台 https://blog.dwcode.cloud/console
- 用户名 `admin`
- 密码：`deploy/env/app.env` 的 `HALO_ADMIN_PASSWORD`

工具台：打开知识库 → 保存笔记 → 更多 → **发布到博客**。

DNS 未好时，发文接口已能走内网；浏览器还打不开博客域名。本机可临时写 hosts：`13.201.82.24 blog.dwcode.cloud`（证书可能报警，等正式 DNS 即可）。

---

## 7. 以后日常操作

### 发版

`git push` / `server-deploy.sh` 会带上 `compose.blog.yml --profile blog`。不要单独 `compose up okx-bot` 而丢掉 env override。

### 改博客页面 / UI

只动 Halo 工作目录，不必改本仓库发文代码：

```text
宿主机: /data/auto-exchange/halo/themes
```

装主题、改 HTML/CSS，或写插件。控制台：外观 → 主题。

### 改发文逻辑

只动 `com.dwcode.okxbot.blog`。Halo API 变了只改 `HaloHttpPublishAdapter`。

### 停博客（不卸工具台）

```bash
cd /home/ubuntu/auto-exchange
docker compose -f deploy/stack/compose.lite.yml \
  -f deploy/stack/compose.blog.yml --profile blog \
  --env-file deploy/env/app.env stop halo
```

`app.env` 设 `HALO_ENABLED=false` 后重启 okx-bot，发布按钮会 503。

### 本机开发

```bash
# 可选：本机也起 Halo，或只连已部署的实例
# app.env / application-local.yml:
#   halo.enabled=true
#   halo.base-url=http://127.0.0.1:8090
#   halo.token=pat_…
```

---

## 8. 排障

| 现象 | 处理 |
|---|---|
| okx-bot 重启，日志 `jdbcUrl, ${SPRING_DATASOURCE_URL}` | 漏了 env_file override。`SKIP_GIT=1 bash deploy/scripts/server-deploy.sh` |
| 发布 503「博客未配置」 | 检查 `HALO_ENABLED`、`HALO_PAT`，重建 okx-bot |
| 发布 502 鉴权失败 | PAT 过期或权限不够；控制台个人中心重建令牌 |
| `blog.dwcode.cloud` 502 / 无证书 | DNS 未指到本机，或 Caddy 未加载新 Caddyfile：`compose up -d --force-recreate --no-deps caddy` |
| Halo 起不来 / OOM | `docker logs auto-exchange-lite-halo-1`；`free -m`；不要再开 whisper/remotion |
| `source app.env` 报 `-Xmx256m: command not found` | `HALO_JVM_OPTS` 必须带引号 |
| 博客图片裂 | 正文里的 `/api/v1/kb/files/{id}/content` 对游客不可见；v1 已知限制 |
| 初始化 API 全是 302 `/login` | 正确入口是 **`/system/setup` 表单 POST**，不是 `/apis/.../initialize` |

探活（在 EC2 上）：

```bash
# 内网
docker run --rm --network auto-exchange-lite_appnet \
  curlimages/curl:8.5.0 -sS -o /dev/null -w '%{http_code}\n' http://halo:8090/login

# 主站不受影响
curl -s -o /dev/null -w '%{http_code}\n' https://dwcode.cloud/api/
```

---

## 10. 本机怎么跑起来验证

验证发博客**不必**在本机再装一套完整生产栈。推荐：本机跑工具台，用 SSH 隧道连已经初始化好的线上 Halo。

### 10.1 本机准备

- JDK 17、Maven、Node 20、本机 MySQL 8+
- 库：`CREATE DATABASE IF NOT EXISTS okx_bot ... utf8mb4;`
- `okx-bot/src/main/resources/application-local.yml` 存在（gitignore）。可：
  ```powershell
  python deploy/scripts/gen_profile_yml.py
  ```
  生成结果里应有 `halo.enabled: true`、`halo.base-url: http://127.0.0.1:8090`、`halo.token` 来自 `app.env` 的 `HALO_PAT`。

当前仓库的 `application-local.yml` 已写成：`token: ${HALO_PAT:}`。启动前在**同一终端**注入 PAT（不要把令牌贴进聊天）：

```powershell
# 从 app.env 读 HALO_PAT 到当前进程（不回显）
Get-Content deploy\env\app.env | ForEach-Object {
  if ($_ -match '^\s*HALO_PAT=(.+)$') { $env:HALO_PAT = $Matches[1].Trim() }
}
```

### 10.2 隧道：本机 18090 → 线上 Halo

线上 Halo **没有**映射公网 8090，只能隧道。  
默认转到本机 **18090**（很多本机 Clash 会劫持 `8090`，出现假 502）。

```powershell
# 窗口 A，保持开着
powershell -ExecutionPolicy Bypass -File deploy/scripts/halo-tunnel.ps1
```

另开终端探活：

```powershell
curl.exe --noproxy "*" -sS -o NUL -w "%{http_code}`n" http://127.0.0.1:18090/login
# 期望 200
```

### 10.3 起后端 + 前端

```powershell
# 窗口 B：后端（先设好 HALO_PAT + 隧道端口）
Get-Content deploy\env\app.env | ForEach-Object {
  if ($_ -match '^\s*HALO_PAT=(.+)$') { $env:HALO_PAT = $Matches[1].Trim().Trim('"') }
}
$env:HALO_BASE_URL = "http://127.0.0.1:18090"
$env:NO_PROXY = "127.0.0.1,localhost"
# 不要用 run-local.ps1 若它清掉了上面的环境变量；直接：
cd okx-bot
$env:SPRING_PROFILES_ACTIVE = "local"
mvn spring-boot:run
```

```powershell
# 窗口 C：前端
cd okx-trading-web
npm install
npm run dev
```

- 前端：http://localhost:3000 （Vite 把 `/api` 转到 `8080`）
- 后端：http://localhost:8080
- 登录：本地超管见 `application-local.yml` 的 `auth.admin`（常见 `admin@okx-bot.local` / `Admin@123456`，以你文件为准）

Flyway 会在本地库执行到 **V6**（没有这三列会自动加）。

### 10.4 点一遍发布

1. 打开 http://localhost:3000 登录 → 知识库
2. 新建或打开一篇笔记，写标题和正文，**先保存**
3. 「更多」→ **发布到博客**
4. 成功会 toast，并尝试打开 `https://blog.dwcode.cloud/...`  
   DNS 未好时页面打不开，但接口已成功。看笔记详情是否带回 `haloPermalink`。

接口自测（把 TOKEN 换成登录后的 JWT）：

```powershell
curl.exe -sS -X POST http://localhost:8080/api/v1/kb/notes/{笔记ID}/publish-blog `
  -H "Authorization: Bearer TOKEN"
```

- 503 +「博客未配置」：`HALO_PAT` 没进 Java 进程，或隧道没开
- 502 鉴权：隧道通了但 PAT 不对
- 本机 `8090` 返回 502、`Proxy-Connection`：Clash 劫持了端口，用 **18090** 隧道（`halo-tunnel.ps1` 默认）
- 连不上 18090：先检查窗口 A 隧道；`curl.exe --noproxy "*" http://127.0.0.1:18090/login` 应 200
- 线上发文 409：已发布态，适配器会忽略并回读 permalink

### 10.5 可选：本机自己起 Halo

不连线上、单独玩主题时：

```powershell
docker run -d --name halo-local -p 8090:8090 `
  -v ${HOME}/.halo2:/root/.halo2 `
  -e HALO_SECURITY_BASIC_AUTH_DISABLED=false `
  -e HALO_EXTERNAL_URL=http://127.0.0.1:8090 `
  halohub/halo:2.25
```

浏览器打开 http://127.0.0.1:8090/system/setup 初始化，在个人中心建 PAT，写入本机 `HALO_PAT`。  
这是**另一套**站点，和线上博客不是同一库。

---

## 9. 检查清单

- [x] 旁挂架构 + 发文适配器代码
- [x] Flyway V6
- [x] RDS 库 `halo`
- [x] Halo 容器与 Caddy 站点配置
- [x] `/system/setup` 管理员
- [x] PAT 写入 `HALO_PAT`，okx-bot 已注入
- [ ] DNS `blog.dwcode.cloud` A → `13.201.82.24`
- [ ] 浏览器打开 console + 知识库发一篇验证

相关：[local-run.md](./local-run.md) · [remote-deploy.md](./remote-deploy.md) · [domain-dwcode-cloud.md](./domain-dwcode-cloud.md) · [scripts.md](./scripts.md)
