# 远程部署到服务器（工具台 + 博客）

面向：知道「容器 = 一个独立进程环境」，但不熟 Docker Compose 细节；想搞清**服务器上怎么跑、当初怎么装上的、以后怎么更新**。

本机开发见 [local-run.md](./local-run.md)。  
Halo 决策与排障细节见 [halo-blog.md](./halo-blog.md)。

---

## 0. 先看懂：服务器上有什么

**一台 EC2**（例如 `13.201.82.24`，2G 内存）上，用 Docker 跑多个容器。  
Compose 只是「一份清单：要起哪些容器、端口、环境变量」。

| 容器（逻辑名） | 干什么 | 对外 |
|----------------|--------|------|
| **okx-bot** | 工具台后端 Spring Boot | 不直接暴露，给 web 反代 |
| **web** | 工具台前端静态页 + 反代 `/api` | 宿主机 **8088**；主域名经 Caddy |
| **caddy** | HTTPS、按域名转发 | **80 / 443** |
| **halo** | 博客（官方镜像，**不是本仓库源码**） | 只在 Docker 内网 **8090**；经 Caddy 到 `blog.dwcode.cloud` |

关系：

```text
访客浏览器
  │
  ├─ https://dwcode.cloud ──► Caddy ──► web ──► okx-bot
  │
  └─ https://blog.dwcode.cloud ──► Caddy ──► halo

工具台发文章：
  okx-bot 容器 ──HTTP + PAT──► http://halo:8090
  （容器之间用服务名通信，不走公网）
```

你的理解：

> 博客单独容器，和工具台独立，工具台 HTTP 调博客。

**正确。**  
「同机 + `--profile blog`」只表示：**Halo 和工具台装在同一台机器上，用 Compose 的 blog 开关把它一起启动**，不是嵌进 Spring 里。

清单文件：

| 文件 | 内容 |
|------|------|
| `deploy/stack/compose.lite.yml` | 工具台：okx-bot、web、caddy |
| `deploy/stack/compose.blog.yml` | 博客：halo，并给 okx-bot 注入 `HALO_*` |
| `deploy/stack/Caddyfile` | 域名怎么转到哪个容器 |
| `deploy/env/app.env` | 密钥与配置（**不提交 Git**） |

`--profile blog`：只有带这个参数时，才启动名字挂在 profile `blog` 下的 **halo** 服务。  
当前 `server-deploy.sh` / `up.sh` **会自动**加上 blog 文件和 profile（只要仓库里有 `compose.blog.yml`）。

---

## 1. 当初是怎么部署到服务器的（手段说明）

没有神秘步骤，就是：**SSH 上机器 + 写配置 + Docker 起容器 + 初始化 Halo + 把令牌写给 okx-bot**。

### 1.1 常用脚本（仓库里）

| 脚本 | 谁跑 | 干什么 |
|------|------|--------|
| `deploy/scripts/server-deploy.sh` | **服务器上** | `git pull`（可关）→ `docker compose ... up -d --build` |
| `deploy/scripts/up.sh` | 服务器上 | 只 compose up，不 pull |
| `deploy/scripts/init-rds.sh` | 服务器上 | RDS 建库 `okx_bot`、`halo`（表由应用 Flyway / Halo 自己建） |
| `deploy/scripts/sync-env-local.ps1` | **你的 Windows** | 把本机 `app.env` scp 到服务器并可选重启 |
| `deploy/scripts/deploy-local.ps1` | 本机 | 打包代码 scp 上去再 deploy（不覆盖密钥） |
| `deploy/scripts/bootstrap-halo.sh` | 服务器上 | 初始化站点 / 补 PAT（一次性） |

日常发版主路径往往是：**本机 `git push` → GitHub Actions → 服务器执行 `server-deploy.sh`**。

### 1.2 2026-08 接入博客时实际做过什么（便于对照）

当时云端已经有工具台，博客是后加的：

1. 仓库增加 `compose.blog.yml`、Caddy `blog.dwcode.cloud`、okx-bot 发文代码、`app.env` 里 Halo 配置项。  
2. 本机把代码/配置同步到 EC2（scp / 解压 / 改 `app.env`）。  
3. 服务器执行 `init-rds.sh` → RDS 多一个空库 **`halo`**。  
4. 服务器执行带 blog 的 `docker compose up` → 拉镜像 **`halohub/halo:2.25`**，起容器。  
5. 浏览器/脚本访问 Halo **`/system/setup`** 创建管理员（**不是**拉 GitHub 源码编译）。  
6. 用管理员建 **个人令牌 PAT**，写入 `app.env` 的 `HALO_PAT`，重建 **okx-bot** 容器让它读到令牌。  
7. Caddy 增加博客域名；**DNS 需你自己在 DNSPod 加 `blog` A 记录**。

**没有**在服务器上 `git clone halo-me`。博客程序来自 Docker 镜像。

### 1.3 关键配置在哪

服务器路径（默认）：

```text
/home/ubuntu/auto-exchange/          # 代码
  deploy/env/app.env                 # 密钥（含 HALO_PAT、数据库）
  deploy/stack/compose.*.yml
/data/auto-exchange/
  data/                              # okx-bot 数据
  logs/
  halo/                              # Halo 工作目录（主题 themes/ 等）
```

---

## 2. 日常：代码更新到服务器

### 2.1 推荐：Git push（CI）

```powershell
# 本机
git add ...
git commit -m "..."
git push origin main
```

Actions 在服务器上大致执行：`server-deploy.sh` → compose 重建工具台 + Halo（若 profile 打开）。

### 2.2 本机直接推代码包

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/deploy-local.ps1
```

### 2.3 只改密钥

```powershell
# 编辑本机 deploy/env/app.env 后
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1
```

### 2.4 SSH 上服务器手动

```bash
ssh -i 你的.pem ubuntu@13.201.82.24
cd /home/ubuntu/auto-exchange
export SKIP_GIT=0   # 要 pull 代码
# 或 SKIP_GIT=1 只重建容器
bash deploy/scripts/server-deploy.sh
```

实质命令等价于：

```bash
docker compose \
  -f deploy/stack/compose.lite.yml \
  -f deploy/stack/compose.blog.yml \
  --profile blog \
  --env-file deploy/env/app.env \
  -f /tmp/某override.yml \   # 脚本生成：给 okx-bot 绝对路径 env_file
  up -d --build
```

看状态：

```bash
docker ps
docker logs auto-exchange-lite-halo-1 --tail 50
docker logs auto-exchange-lite-okx-bot-1 --tail 50
```

---

## 3. 博客相关环境变量（`deploy/env/app.env`）

| 变量 | 含义 |
|------|------|
| `HALO_ENABLED` | 是否允许工具台发博客 |
| `HALO_PAT` | 个人令牌，okx-bot 调 Halo API 用 |
| `HALO_PUBLIC_BASE_URL` | 对外地址，如 `https://blog.dwcode.cloud` |
| `HALO_R2DBC_URL` | Halo 用的 MySQL（R2DBC），库名 `halo` |
| `HALO_DB_USERNAME` / `HALO_DB_PASSWORD` | 库账号 |
| `HALO_JVM_OPTS` | 如 `"-Xms128m -Xmx256m"`（**要加引号**） |
| `HALO_ADMIN_*` | 初始化/登录用（密码只放 env） |

Compose 里还会给 okx-bot 设：

- `HALO_BASE_URL=http://halo:8090`（**容器内部名字**，只在服务器 Docker 网有效）

本机开发不要用 `http://halo:8090`，见 [local-run.md](./local-run.md)。

---

## 4. DNS 与访问

| 域名 | 指向 | 用途 |
|------|------|------|
| `dwcode.cloud` | EC2 IP | 工具台 |
| `blog.dwcode.cloud` | 同一 IP | 博客（Caddy → halo） |

DNSPod 增加：`blog` A → `13.201.82.24`（若还没加）。

- 博客前台：https://blog.dwcode.cloud  
- 博客后台：https://blog.dwcode.cloud/console  
- 管理员：见 `app.env` 里 `HALO_ADMIN_USERNAME` / `HALO_ADMIN_PASSWORD`  

改博客外观：服务器目录  

```text
/data/auto-exchange/halo/themes
```

不必重新编译本仓库 Java。

---

## 5. 首次从零装（新机器提纲）

若以后换机器，顺序是：

1. 装 Docker、克隆仓库到 `~/auto-exchange`  
2. 写好 `deploy/env/app.env`（数据库、JWT、R2、`HALO_*`…）  
3. `bash deploy/scripts/init-rds.sh`  
4. `bash deploy/scripts/server-deploy.sh`  
5. 打开 `https://blog.xxx/system/setup` 或脚本 `bootstrap-halo.sh` 初始化  
6. 写入 `HALO_PAT`，再 deploy 一次 okx-bot  
7. 配 DNS + 安全组 80/443  

细节与排障：[halo-blog.md](./halo-blog.md)、[domain-dwcode-cloud.md](./domain-dwcode-cloud.md)、[setup.md](./setup.md)。

---

## 6. 常见问题

| 现象 | 含义 | 处理 |
|------|------|------|
| okx-bot 日志 `jdbcUrl, ${SPRING_DATASOURCE_URL}` | 没注入 `app.env` | 用 `server-deploy.sh`，不要裸 `compose up` 单个服务丢 override |
| `blog` 域名 502 | halo 没起或 Caddy 未加载 | `docker ps` 看 halo；重建 caddy |
| 发布 503 | PAT 空或 `HALO_ENABLED=false` | 检查 env 并重建 okx-bot |
| 内存很紧 / OOM | 2G 同机 | 不要再开 whisper/remotion；可 `docker stop ...halo-1` 临时关博客 |
| 想停博客不停工具台 | | `docker stop auto-exchange-lite-halo-1` |

---

## 7. 和你理解的对照

| 你的理解 | 实际情况 |
|----------|----------|
| 博客单独容器 | ✅ `halo` 容器，镜像 `halohub/halo:2.25` |
| 和工具台独立 | ✅ 不同容器、不同库；仅 HTTP + PAT |
| 工具台调博客接口 | ✅ `POST /api/v1/kb/notes/{id}/publish-blog` 内部再调 Halo UC API |
| 是否拉了源码 | ❌ 没有 clone Halo 源码，只是跑官方镜像 |
| 怎么部署上去的 | SSH + `app.env` + `server-deploy`/`compose` + 初始化 + PAT |
| 本机是否必须跑博客 | ❌ 日常否；发文联调用隧道；改主题再本机 Docker |

本机完整步骤：[local-run.md](./local-run.md)
