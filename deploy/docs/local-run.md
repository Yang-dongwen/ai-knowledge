# 本机怎么跑当前项目

面向：工具台前后端你会跑，但不清楚 **博客（Halo）** 本地要不要起、怎么起。

更细的 Halo 设计与服务器已做事项见 [halo-blog.md](./halo-blog.md)。  
服务器日常发版见 [remote-deploy.md](./remote-deploy.md)。

---

## 0. 先建立正确心智（不用会 Docker Compose）

把 Docker 想成：**一个容器 = 一台小虚拟机里只跑一个程序**。

| 名字 | 是什么 | 本机怎么跑 |
|------|--------|------------|
| **工具台前端** | Vue（`okx-trading-web`） | `npm run dev` |
| **工具台后端** | Spring Boot（`okx-bot`） | 主启动类 / `mvn spring-boot:run` |
| **博客 Halo** | 官方做好的程序，**不在本仓库源码里** | 一般 **本机不装**；需要时再 Docker 起一个，或 SSH 隧道连云端 |

你的理解是对的：

> 博客单独一个容器，和工具台独立；工具台用 HTTP 调博客接口。

补充三点：

1. **没有**把 Halo 源码拉进本仓库，服务器上是 `docker pull halohub/halo:2.25` 镜像。
2. 工具台发文靠配置里的 **`HALO_PAT`（个人令牌）** + `HALO_BASE_URL`，不是把博客嵌进 Spring 进程。
3. **日常改工具台**：只跑前后端即可；发博客可连**云端已在跑的 Halo**。  
   **改博客皮肤/主题 HTML**：才需要本机起 Halo，或直接在服务器改主题目录。

```text
【日常本机】
  浏览器 → localhost:3000（前端）
              ↓ /api 代理
           localhost:8080（okx-bot）
              ↓ 可选：HTTP 发文
           云端 Halo（隧道转到 127.0.0.1:18090）
              或本机 Docker Halo :8090

【服务器】
  浏览器 → dwcode.cloud → Caddy → web + okx-bot
  浏览器 → blog.dwcode.cloud → Caddy → halo 容器
  okx-bot 容器内直接 http://halo:8090 发文
```

---

## 1. 本机只跑工具台（最常用）

### 1.1 需要准备

- JDK 17、Maven  
- Node 20+  
- 本机 MySQL 8+，库名 `okx_bot`（账号密码与 `application-local.yml` 一致，常见 `root` / `123456`）

### 1.2 后端

```powershell
cd D:\gitprojects\auto-exchange\okx-bot
# IDE 运行 OkxBotApplication，profile = local
# 或：
#   先保证有 application-local.yml（gitignore）
#   powershell -File ..\deploy\scripts\run-local.ps1
```

- 端口：**8080**  
- 配置：`okx-bot/src/main/resources/application-local.yml`  
- 没有该文件时：`python deploy/scripts/gen_profile_yml.py`（从 `deploy/env/app.env` 生成）

### 1.3 前端

```powershell
cd D:\gitprojects\auto-exchange\okx-trading-web
npm install   # 首次
npm run dev
```

- 地址：http://localhost:3000  
- Vite 会把 `/api` 转到 `http://localhost:8080`

### 1.4 登录

以你本机 `application-local.yml` 里 `auth.admin` 为准。常见开发账号：

- 邮箱：`admin@okx-bot.local`  
- 密码：`Admin@123456`（若你改过以文件为准）

到这里：**工具台已完整可测**（知识库、AI 工具等）。**不必**起博客。

---

## 2. 本机要「发布到博客」时（推荐：直接连云端）

### 2.0 数据库本来就不是同一个

| 数据 | 在哪 | 本机发博客时 |
|------|------|----------------|
| 工具台用户、知识库笔记 | 本机 MySQL `okx_bot` | 读写本地库 |
| 博客文章、主题 | 云端 Halo + RDS 库 `halo` | 只通过 HTTP 写入云端 |

**不需要**本机连云端 MySQL。本地 yml 里配的是「博客 API 地址 + 令牌」，不是博客数据库地址。

### 2.1 最简单：yml 直连云端（DNS 已通时）

云端 Halo 经 Caddy 暴露在 **`https://blog.dwcode.cloud`**（不是公网 `IP:8090`）。  
本机 `application-local.yml` **已经可以这样配**（当前仓库默认）：

```yaml
halo:
  enabled: true
  base-url: ${HALO_BASE_URL:https://blog.dwcode.cloud}   # 调 API 用
  token: ${HALO_PAT:}                                    # 必须带 PAT
  public-base-url: https://blog.dwcode.cloud               # 拼文章链接用
  publish-on-create: true
```

你只需要：

1. 正常起工具台前后端（§1）  
2. 启动后端**之前**注入 PAT（令牌在 `deploy/env/app.env`，与云端 okx-bot 同一个）：

```powershell
Get-Content deploy\env\app.env | ForEach-Object {
  if ($_ -match '^\s*HALO_PAT=(.+)$') { $env:HALO_PAT = $Matches[1].Trim().Trim('"') }
}
# 然后 IDE 或 mvn 起 OkxBotApplication（profile=local）
```

也可以把 `token: pat_xxx` 直接写进 `application-local.yml`（该文件 gitignore，勿提交）。

3. 知识库 → 保存笔记 → 发布到博客。  
   文章进**云端**博客；笔记仍在**本机**库。其它不用管。

**不需要**隧道，**不需要**本机 Docker 跑 Halo。

### 2.2 备选：SSH 隧道（仅当 blog 域名访问不了时）

公网 `IP:8090` **没有**对外开放，所以不能写 `http://13.x.x.x:8090`。  
域名不通时，才用：

```powershell
powershell -File deploy/scripts/halo-tunnel.ps1   # 本机 18090 → 云端容器
$env:HALO_BASE_URL = "http://127.0.0.1:18090"
$env:HALO_PAT = "..."   # 同上
```

### 2.3 本机自己起 Halo（仅改博客主题 UI 时）

这是**另一套**博客数据，和线上不是同一库。日常发文**不要**用这个。

```powershell
docker run -d --name halo-local -p 8090:8090 `
  -v $HOME/.halo2:/root/.halo2 `
  -e HALO_EXTERNAL_URL=http://127.0.0.1:8090 `
  -e HALO_SECURITY_BASIC_AUTH_DISABLED=false `
  -e JVM_OPTS="-Xms128m -Xmx256m" `
  halohub/halo:2.25
```

初始化后拿本机 PAT，把 `base-url` 改成 `http://127.0.0.1:8090`。主题目录在本机 `~/.halo2/themes`。

---

## 3. 「改前端 UI」分别改哪里

| 你要改的界面 | 在哪改 | 本机怎么调试 |
|--------------|--------|--------------|
| 工具台（知识库、按钮、布局） | 仓库 `okx-trading-web` | `npm run dev` 即可 |
| 博客前台（访客看到的站） | Halo **主题**，不是本仓库 Vue | 本机 Docker Halo（做法 B），或改服务器 `/data/auto-exchange/halo/themes` |
| 博客后台 Console | 一般不改；扩展用插件 | 浏览器打开 Halo 后台 |

**结论：**  
- 只改工具台 → 不用本地 Halo。  
- 要改博客皮肤 → 才需要本地 Halo 容器或改服务器主题目录。  
- 发文联调 → 隧道连云端就够。

---

## 4. 本机相关脚本一览（我帮你加过/用过的）

| 脚本 | 作用 | 你是否必须用 |
|------|------|----------------|
| `deploy/scripts/run-local.ps1` | profile=local 起 okx-bot | 可选，IDE 启动也行 |
| `deploy/scripts/gen_profile_yml.py` | 从 `app.env` 生成 `application-local.yml` | 缺 local 配置时用 |
| `deploy/scripts/halo-tunnel.ps1` | 本机 18090 → 云端 Halo | 仅域名访问不了时 |
| （无强制脚本） | 本机 `docker run ... halo` | **只改博客主题时用** |

**没有**「一键本机起博客」的固定 compose 依赖；博客官方镜像一行 `docker run` 即可。

服务器上的部署手段见 [remote-deploy.md](./remote-deploy.md)（`server-deploy.sh`、`compose.blog.yml` 等）。

---

## 5. 推荐日常流程（对照你的习惯）

```text
改工具台功能
  → IDE 起 OkxBotApplication + npm run dev
  → 浏览器 localhost:3000
  → 不需要 Halo

测「发布到博客」
  → 起前后端即可
  → 后端带上 HALO_PAT（base-url 默认已是 https://blog.dwcode.cloud）
  → 知识库点发布（文章进云端，笔记仍在本机库）

改博客外观
  → 本机 docker run Halo，改 themes
  → 或 SSH 到服务器改 /data/auto-exchange/halo/themes
```

---

## 6. 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| 发布 503 博客未配置 | 没设 `HALO_PAT` 或 `enabled=false` | 从 `deploy/env/app.env` 注入 PAT |
| 本机 8090 假 502、`Proxy-Connection` | Clash 劫持 | 用隧道默认 **18090** |
| 隧道关掉后发文失败 | 连不上云端 Halo | 重新跑 `halo-tunnel.ps1` |
| 外链 blog.dwcode.cloud 打不开 | DNS 未配 | 不影响发文进库；配 DNS 后即可访问 |
| 本机 MySQL 连不上 | 服务没开 / 库不存在 | 建库 `okx_bot`，核对 local yml |

---

## 7. 检查清单

- [ ] MySQL `okx_bot` + `application-local.yml`  
- [ ] 后端 8080、前端 3000 能登录  
- [ ] （可选）隧道 18090 返回 200  
- [ ] （可选）知识库发布成功、有 `haloPermalink`  
- [ ] （可选）本机 Docker Halo 改主题  

上一篇：[halo-blog.md](./halo-blog.md) · 服务器：[remote-deploy.md](./remote-deploy.md)
