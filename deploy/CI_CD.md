# 一键部署指南（服务已上线后）

当前生产：EC2 `13.201.82.24:8088` + AWS RDS。  
密钥只在服务器 `~/auto-exchange/deploy/app.env`。

---

## 你要的目标（推荐分工）

| 改什么 | 怎么做 | 状态 |
|--------|--------|------|
| **代码**（前后端） | `git push origin main` → GitHub Actions 自动部署 | **已完成** |
| **隐私配置**（`app.env`） | 本机改 `deploy/app.env` → 跑同步脚本 | **用下面脚本** |

```text
代码变更  ──git push──►  GitHub Actions ──SSH──►  git pull + docker 重建前后端
                                                     ▲
隐私/密钥 ──sync-env-local.ps1──scp app.env──────────┘（仅同步配置并重启）
```

**原则：** 密钥永不进 Git；代码走 CI；配置单独一键同步。

---

## 方案对比

| 方案 | 怎么触发 | 服务器是否要 git | 推荐场景 |
|------|----------|------------------|----------|
| **代码：GitHub Actions** | `git push` / 网页 Run | 需要（已配好） | **日常发布代码（主路径）** |
| **配置：sync-env** | 本机 `sync-env-local.ps1` | 不需要 | **改 RDS/JWT/API Key** |
| **兜底：deploy-local.ps1** | 本机打包 scp 代码 | 不需要 | CI 挂了或紧急发布 |
| **SSH 一条命令** | 服务器上 `deploy` | 需要 | 人已在机器上 |

全部 **不把业务密钥写进 Git**。

---

## 日常 1：发代码（GitHub 自动，无需你再操作）

```powershell
git add .
git commit -m "your message"
git push origin main
```

之后 **不用** SSH、不用本机脚本。Actions 会：

1. SSH 登录 EC2  
2. `git pull` 最新代码  
3. `docker compose up -d --build`（**前端 + 后端** 镜像重建并重启）  
4. 密钥仍读服务器上的 `deploy/app.env`（**不会**用仓库里的密钥）

手动触发：GitHub → **Actions → Deploy EC2 → Run workflow**

---

## 日常 2：同步隐私配置（本机一键脚本）

只改密钥 / RDS / API Key 时：

1. 编辑本机 `deploy/app.env`（勿 commit）  
2. 执行：

```powershell
cd D:\gitprojects\auto-exchange
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1
```

会：

1. `scp` 本机 `app.env` → 服务器 `~/auto-exchange/deploy/app.env`  
2. 同步为 `.env`  
3. `docker compose up -d` **重启容器**使新环境变量生效（默认不重新 build 镜像）

只上传不重启：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1 -NoRestart
```

---

## 兜底：本机整包同步代码

CI 挂了或要紧急覆盖服务器代码时：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/deploy-local.ps1
```

注意：此脚本 **不会** 用本机 app.env 覆盖服务器密钥。

---

## 密钥约定

| 文件 | 位置 | Git |
|------|------|-----|
| `deploy/app.env` | 本机 + 服务器私有 | ❌ 禁止提交 |
| `deploy/app.env.example` | 仓库模板 | ✅ |
| `DEPLOY_SSH_KEY` 等 | GitHub Secrets | 仅部署 SSH，无业务密钥 |

---

## 推荐节奏（已落地）

1. **改代码** → `git push` → 等 Actions 绿 → 刷新网站  
2. **改密钥** → 改本机 `app.env` → `sync-env-local.ps1`  
3. 一般 **不要** 把 `app.env` 提交进 Git  


---

## 排障

| 现象 | 处理 |
|------|------|
| 本机 scp/ssh 失败 | 检查 pem、安全组 22、IP |
| Actions SSH timeout | 22 未对 GitHub 放行 → 用方案 A 或放宽 22 |
| `不是 git 仓库` | 跑 `bootstrap-git.sh` 或本机用 `deploy-local.ps1` |
| 构建 OOM | 确认 2G swap；或升配 |
| 密钥被覆盖 | `deploy-local.ps1` 会备份恢复 app.env；勿把本机空密码 env 强行 scp 错内容 |
