# 一键部署到 AWS

本层只服务「把当前代码或密钥弄上已经在跑的 EC2」。本机开发看 [../local/README.md](../local/README.md)。

现在这台机器：

| 项 | 值 |
|----|-----|
| 主机 | `13.201.82.24`（`ubuntu`） |
| 代码目录 | `/home/ubuntu/auto-exchange` |
| 密钥 | `/home/ubuntu/auto-exchange/deploy/env/app.env`（**不进 Git**） |
| 数据 | `/data/auto-exchange/`（日志、okx-bot 数据、Halo 主题） |
| 访问 | https://dwcode.cloud · https://blog.dwcode.cloud · 备用 `:8088` |

服务器上跑的是 Docker：`okx-bot` + `web` + `caddy` + `halo`。Halo 是官方镜像，仓库里没有 Halo 源码。

```text
浏览器
  ├─ https://dwcode.cloud      → Caddy → web → okx-bot
  └─ https://blog.dwcode.cloud → Caddy → halo:8090

本机发版
  deploy.ps1  ──scp 代码包──►  EC2 ── server-deploy.sh ──► docker compose up --build
  sync-env.ps1 ──scp app.env──► EC2 ── server-deploy.sh（不 pull）
  git push   ──Actions SSH──►  EC2 ── server-deploy.sh（先 git pull）
```

---

## 这一层每个文件

| 文件 | 在哪执行 | 作用 |
|------|----------|------|
| **README.md** | — | 本文 |
| **deploy.ps1** | **你的 Windows** | 一键：打包源码 → scp → 远端重建容器。**不覆盖**服务器已有 `app.env` |
| **sync-env.ps1** | **你的 Windows** | 只把本机 `deploy/env/app.env` 拷到服务器并重启。改密钥用这个 |
| **server-deploy.sh** | **EC2**（Actions / 上面两个脚本都会调它） | `git pull`（可关）→ 带上 blog overlay → `docker compose up -d --build` |
| **up.sh** | **EC2** | 只重建容器，不 pull。已经 SSH 上去、代码不用更新时 |
| **init-rds.sh** | **EC2，新机器一次** | 在 RDS 上 `CREATE DATABASE okx_bot` 和 `halo`。表由 Flyway / Halo 自己建 |
| **bootstrap-git.sh** | **EC2，新机器一次** | 把原来的目录换成 `git clone`，保留 `app.env`，生成 Deploy Key |
| **bootstrap-halo.sh** | **EC2，博客未初始化时一次** | 调 Halo `/system/setup`，建 PAT，写回 `app.env` 的 `HALO_PAT` |

默认 SSH 密钥：`%USERPROFILE%\Downloads\aws_common\dw-yindu.pem`。换机器改脚本参数即可。

---

## 日常发版（已有这台 EC2）

### 方式 A：本机一键（推荐，不依赖 Actions）

```powershell
powershell -File deploy/aws/deploy.ps1
```

浏览器打开 http://13.201.82.24:8088/ 或 https://dwcode.cloud 。

### 方式 B：GitHub Actions

`git push` 之后打开仓库 **Actions → Deploy EC2 → Run workflow**（push 不会自动发，避免误发）。

服务器上等价于：

```bash
export APP_DIR=$HOME/auto-exchange REF=main SKIP_GIT=0
bash deploy/aws/server-deploy.sh
```

### 只改环境变量

编辑本机 `deploy/env/app.env`（先看 [../env/README.md](../env/README.md)）：

```powershell
powershell -File deploy/aws/sync-env.ps1
# 只上传不重启：
powershell -File deploy/aws/sync-env.ps1 -NoRestart
```

---

## 新机器从零（换 EC2 时按这个顺序）

1. 机器装 Docker，安全组放行 **22**（你的 IP）、**80/443**、备用 **8088**。RDS 与 EC2 同 VPC，3306 只放行 EC2。
2. 把代码放到 `~/auto-exchange`（`git clone` 或先跑一次 `deploy.ps1`）。
3. `cp deploy/env/app.env.example deploy/env/app.env`，填 RDS / JWT / R2 / Halo 等，**不要提交**。
4. `set -a; source deploy/env/app.env; set +a` 然后 `bash deploy/aws/init-rds.sh`。
5. `bash deploy/aws/server-deploy.sh`（第一次会拉镜像、建容器）。
6. 若博客未初始化：`bash deploy/aws/bootstrap-halo.sh`，再 `SKIP_GIT=1 bash deploy/aws/server-deploy.sh` 让 okx-bot 读到 PAT。
7. DNSPod：`dwcode.cloud` / `www` / `blog` 的 A 记录指到这台公网 IP。
8. 要用 Actions：在 EC2 跑 `bash deploy/aws/bootstrap-git.sh`，公钥加到仓库 Deploy keys；GitHub Secrets 填 `DEPLOY_HOST` / `DEPLOY_USER` / `DEPLOY_SSH_KEY`。

---

## 博客在 AWS 上怎么挂着

`server-deploy.sh` 发现 `deploy/stack/compose.blog.yml` 就会自动加 `--profile blog`，不用单独发版。

| 变量（写在 `app.env`） | 含义 |
|------------------------|------|
| `HALO_ENABLED` | 工具台是否允许发博客 |
| `HALO_PAT` | okx-bot 调 Halo API 的令牌 |
| `HALO_PUBLIC_BASE_URL` | 对外地址，现在是 `https://blog.dwcode.cloud` |
| `HALO_R2DBC_URL` 等 | Halo 自己的 MySQL（库名 `halo`） |
| `HALO_ADMIN_*` | 后台登录，只放 env |

容器内部 okx-bot 访问 Halo 用 `http://halo:8090`（Docker 服务名）。本机不要写这个地址。

改博客外观：SSH 后改 `/data/auto-exchange/halo/themes`，不用重新编译本仓库。

---

## 常见问题

| 现象 | 处理 |
|------|------|
| okx-bot 日志 `jdbcUrl, ${SPRING_DATASOURCE_URL}` | 漏了 env override。不要只 `docker compose up okx-bot`，再跑一遍 `server-deploy.sh`（`SKIP_GIT=1`） |
| Actions SSH 超时 | 安全组 22；或改用 `deploy.ps1` |
| scp 失败 | PEM 路径、IP 是否变了 |
| 博客 502 | `docker ps` 看 halo 在不在；重建 caddy |
| 发布 503 | `HALO_PAT` 空或 `HALO_ENABLED=false`，sync-env 后再重启 |
| 2G 内存吃紧 | 不要用 `compose.full.yml`（whisper + remotion）；可临时 `docker stop auto-exchange-lite-halo-1` |
