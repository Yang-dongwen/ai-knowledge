# deploy/ 目录说明

部署相关**只看这里**。子目录按职责分开，避免密钥、镜像、脚本、文档混在一起。

```text
deploy/
├── README.md          ← 你在这里（目录地图 + 日常三件事）
├── env/               ← 环境变量（模板可提交；真实密钥不提交）
├── stack/             ← Docker 镜像 / compose / nginx
├── scripts/           ← 一键脚本（本机 + 服务器）
└── docs/              ← 详细文档（从零部署、CI、脚本表）
```

---

## 一眼看懂：哪个文件干什么

| 路径 | 作用 | 进 Git？ |
|------|------|----------|
| **`env/app.env.example`** | 服务器/EC2 变量**模板** | ✅ |
| **`env/app.env`** | 真实密钥（RDS/JWT/R2/AI…） | ❌ |
| **`env/app.env.local.example`** | 本机 env 可选模板 | ✅ |
| **`stack/compose.lite.yml`** | **当前生产**：web + okx-bot | ✅ |
| **`stack/compose.full.yml`** | 全量：+ whisper + remotion | ✅ |
| **`stack/Dockerfile.*`** | 镜像构建 | ✅ |
| **`stack/nginx.conf`** | 前端反代 | ✅ |
| **`scripts/*`** | 同步密钥 / 发版 / 本机启动 | ✅ |
| **`docs/cicd.md`** | 日常发代码 + 同步密钥 | ✅ |
| **`docs/scripts.md`** | 每个脚本详细说明 | ✅ |
| **`docs/setup.md`** | 从零建 EC2/RDS（可选深读） | ✅ |
| **`docs/worker-proxy.md`** | **Worker 反代固定域名（完整步骤）** | ✅ |
| **`worker-proxy/`** | Cloudflare Worker 源码与 wrangler 配置 | ✅ |

Spring 业务配置**不在**本目录：

| 配置 | 位置 |
|------|------|
| 远程 profile | `okx-bot/src/main/resources/application-ec2.yml`（变量，提交） |
| 本机 profile | `okx-bot/src/main/resources/application-local.yml`（真实值，不提交） |

---

## 公网 HTTPS 入口

**完整步骤与排障 → [docs/worker-proxy.md](./docs/worker-proxy.md)**（Worker 反代，推荐参阅）

| 方案 | 固定 URL | 文档 |
|------|----------|------|
| **A. Worker 反代**（当前） | `https://auto-exchange-proxy.dwcode.workers.dev` | [docs/worker-proxy.md](./docs/worker-proxy.md) |
| B. 仅 Quick Tunnel | 否（URL 会变） | `scripts/quick-tunnel.sh` |
| C. Named Tunnel | 需自有域名 | `scripts/named-tunnel.sh` |

快速命令：

```bash
# EC2：保证业务 + tunnel
bash deploy/scripts/quick-tunnel.sh start
bash deploy/scripts/quick-tunnel.sh url   # 填进 wrangler.toml 的 ORIGIN_BASE

# 本机：部署 Worker
cd deploy/worker-proxy
npx wrangler login    # 首次
npx wrangler deploy
```

---

## 日常三件事

### 1. 发代码（主路径）

```powershell
git push origin main
# → GitHub Actions → 服务器 git pull + docker 重建
```

### 2. 改密钥 / 环境变量

```powershell
# 编辑 deploy/env/app.env 后：
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1
```

### 3. 本机跑后端

```powershell
python deploy/scripts/gen_profile_yml.py   # 可选：从 app.env 生成 local yml
powershell -ExecutionPolicy Bypass -File deploy/scripts/run-local.ps1
```

---

## 脚本速查

| 脚本 | 场景 |
|------|------|
| `scripts/sync-env-local.ps1` | 本机 → EC2 同步 `env/app.env` |
| `scripts/deploy-local.ps1` | CI 挂了时本机打包发布 |
| `scripts/run-local.ps1` | 本机 Spring Boot |
| `scripts/gen_profile_yml.py` | 生成 `application-local.yml` |
| `scripts/server-deploy.sh` | 服务器/CI：pull + compose |
| `scripts/up.sh` | 服务器只重建容器 |
| `scripts/bootstrap-git.sh` | 一次性：服务器改 git 部署 |
| `scripts/init-rds.sh` | 一次性：RDS 建库导表 |

详情 → **[docs/scripts.md](./docs/scripts.md)** · CI 流程 → **[docs/cicd.md](./docs/cicd.md)**

---

## 服务器上标准路径

```text
~/auto-exchange/
  deploy/env/app.env              # 唯一真实密钥文件
  deploy/stack/compose.lite.yml   # 默认 compose
```

首次拉到新目录结构后，`server-deploy.sh` 会把旧的 `deploy/app.env` **自动迁移**到 `deploy/env/app.env`。

---

## 生产启动命令（服务器）

```bash
cd ~/auto-exchange
bash deploy/scripts/server-deploy.sh
# 或（必须 export 绝对路径，compose 内 env_file 依赖 APP_ENV_FILE）
export APP_ENV_FILE=$PWD/deploy/env/app.env
docker compose -f deploy/stack/compose.lite.yml --env-file deploy/env/app.env up -d --build
```

访问：`http://<公网IP>:8088/`

## 脚本内路径约定（统一）

| 常量 | 值 |
|------|-----|
| 仓库根 | `APP_DIR` / 本机 repo root |
| 密钥（文件） | `deploy/env/app.env` |
| 密钥（compose env_file） | **绝对路径** `$APP_ENV_FILE`（脚本自动 export） |
| 生产 compose | `deploy/stack/compose.lite.yml` |
| 全量 compose | `deploy/stack/compose.full.yml` |
| build context | `../..`（相对 stack/ → 仓库根） |
