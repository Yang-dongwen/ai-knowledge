# 一键部署指南（服务已上线后）

当前生产：EC2 `13.201.82.24:8088` + AWS RDS。  
密钥只在服务器 `~/auto-exchange/deploy/app.env`。

---

## 方案对比

| 方案 | 怎么触发 | 服务器是否要 git | 推荐场景 |
|------|----------|------------------|----------|
| **A. 本机一键** | 运行 PowerShell | 不需要 | 日常开发，立刻发布 |
| **B. GitHub Actions** | push / 网页 Run | 需要 | 远程/协作/标准 CI |
| **C. 仅 SSH 一条命令** | SSH 后 `deploy` | 需要（pull） | 已在服务器上时 |

三条都 **不把业务密钥写进 Git**。

---

## 方案 A：本机一键（现在就能用）

在仓库根目录（Windows）：

```powershell
pwsh deploy/scripts/deploy-local.ps1
```

可选参数：

```powershell
pwsh deploy/scripts/deploy-local.ps1 `
  -HostName 13.201.82.24 `
  -PemPath "C:\Users\你\Downloads\aws_common\dw-yindu.pem"
```

流程：

1. 打包 `okx-bot` / 前端 / `deploy`（排除 `node_modules`、本机 `app.env`）  
2. scp 到 EC2  
3. **保留服务器上的 `app.env`**  
4. `SKIP_GIT=1` 执行 `server-deploy.sh` → `docker compose up -d --build`  

只同步代码不重建：

```powershell
pwsh deploy/scripts/deploy-local.ps1 -SkipBuild
```

---

## 方案 B：GitHub 一键

### B1. 一次性准备

1. **本机**把部署相关代码 push 到 `main`（含 `.github/workflows`、`deploy/`）  
2. **EC2** 转成 git 仓库（保留密钥）：

```bash
# 先保证服务器上有最新 scripts（可用方案 A 同步一次）
cd ~/auto-exchange
bash deploy/scripts/bootstrap-git.sh
# 按提示把公钥加到 GitHub Deploy keys 后，再执行一次直到成功
```

3. 配置 GitHub Secrets：见 [setup-github-secrets.md](./scripts/setup-github-secrets.md)

### B2. 日常

```bash
git add .
git commit -m "your message"
git push origin main
```

或：GitHub → **Actions → Deploy EC2 → Run workflow**

Actions 只做：SSH → `git pull` → `docker compose up -d --build`。

---

## 方案 C：服务器上一条命令

```bash
ssh -i dw-yindu.pem ubuntu@13.201.82.24
cd ~/auto-exchange
bash deploy/scripts/server-deploy.sh
# 或（PATH 已加 ~/bin 时）
deploy
```

`SKIP_GIT=1` 时只重建不 pull。

---

## 密钥约定

| 文件 | 位置 | Git |
|------|------|-----|
| `deploy/app.env` | 服务器 / 本机私有 | ❌ 禁止提交 |
| `deploy/app.env.example` | 仓库模板 | ✅ |
| `DEPLOY_SSH_KEY` | GitHub Secrets | 仅部署 SSH |

本机改密钥 → scp 到服务器：

```powershell
scp -i $pem deploy\app.env ubuntu@13.201.82.24:~/auto-exchange/deploy/app.env
```

---

## 推荐节奏

1. **现在**：用 **方案 A** 发布（不依赖 GitHub Deploy Key）  
2. **本周**：push 代码 + `bootstrap-git.sh` + Secrets → 启用 **方案 B**  
3. 改 env 只动服务器 `app.env`，再跑 A 或 B 重建容器  

---

## 排障

| 现象 | 处理 |
|------|------|
| 本机 scp/ssh 失败 | 检查 pem、安全组 22、IP |
| Actions SSH timeout | 22 未对 GitHub 放行 → 用方案 A 或放宽 22 |
| `不是 git 仓库` | 跑 `bootstrap-git.sh` 或本机用 `deploy-local.ps1` |
| 构建 OOM | 确认 2G swap；或升配 |
| 密钥被覆盖 | `deploy-local.ps1` 会备份恢复 app.env；勿把本机空密码 env 强行 scp 错内容 |
