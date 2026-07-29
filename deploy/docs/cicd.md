# 一键部署（服务已上线后）

当前生产：EC2 `13.201.82.24:8088` + AWS RDS。  
密钥：`~/auto-exchange/deploy/env/app.env`。

目录地图 → [../README.md](../README.md) · 脚本表 → [scripts.md](./scripts.md)

---

## 推荐分工

| 改什么 | 怎么做 |
|--------|--------|
| **代码** | `git push origin main` → GitHub Actions |
| **密钥 / 环境变量** | 改本机 `deploy/env/app.env` → `sync-env-local.ps1` |

```text
代码  ──git push──►  Actions ──SSH──►  server-deploy.sh（pull + compose）
密钥  ──sync-env-local.ps1──scp──►  deploy/env/app.env → 重启容器
```

**原则：** 业务密钥永不进 Git；代码走 CI；配置单独同步。

---

## 发代码

```powershell
git add .
git commit -m "your message"
git push origin main
```

Actions 会：SSH → `git pull` → `docker compose up -d --build`（读服务器 `env/app.env`）。

手动：GitHub → **Actions → Deploy EC2 → Run workflow**

---

## 同步密钥

```powershell
# 编辑 deploy/env/app.env 后
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1

# 只上传不重启
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1 -NoRestart
```

---

## 兜底：本机整包

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/deploy-local.ps1
```

不会用本机密钥覆盖服务器 `env/app.env`。

---

## GitHub 一次性配置

### Deploy Key（服务器 git pull）

在 EC2：

```bash
bash ~/auto-exchange/deploy/scripts/bootstrap-git.sh
```

公钥 → 仓库 **Settings → Deploy keys**（只读）。

### Actions Secrets

| Name | 值 |
|------|-----|
| `DEPLOY_HOST` | EC2 公网 IP |
| `DEPLOY_USER` | `ubuntu` |
| `DEPLOY_SSH_KEY` | 登录 PEM 全文 |
| `DEPLOY_APP_DIR` | `/home/ubuntu/auto-exchange`（可选） |

不要把 `app.env` 放进 GitHub Secrets。

---

## 密钥约定

| 文件 | Git |
|------|-----|
| `deploy/env/app.env` | ❌ |
| `deploy/env/app.env.example` | ✅ |
| `DEPLOY_SSH_KEY` 等 | 仅部署用 |

R2 前缀：服务器 `STORAGE_ENV_PREFIX=ec2`；本机 `local`。

---

## 排障

| 现象 | 处理 |
|------|------|
| scp/ssh 失败 | pem、安全组 22、IP |
| Actions SSH timeout | 放行 22 或改用 `deploy-local.ps1` |
| 不是 git 仓库 | `bootstrap-git.sh` |
| 构建 OOM | swap / 升配 |
| R2 仍写 `dev/` | `env/app.env` 里 `STORAGE_ENV_PREFIX=ec2` 后 sync |
