# GitHub 一键部署：Secrets 与 Deploy Key

## 1. 仓库 Deploy Key（服务器 git pull 用）

在 **EC2** 执行：

```bash
bash ~/auto-exchange/deploy/scripts/bootstrap-git.sh
```

脚本会打印 **公钥**。复制到：

GitHub 仓库 → **Settings → Deploy keys → Add deploy key**

- Title: `ec2-auto-exchange`
- Key: 粘贴公钥
- 只读即可（不要勾 Write，除非要服务器 push）

再执行一次 `bootstrap-git.sh` 直到 clone 成功。

---

## 2. GitHub Actions Secrets（Actions SSH 用）

仓库 → **Settings → Secrets and variables → Actions → New repository secret**

| Name | 值 |
|------|-----|
| `DEPLOY_HOST` | `13.201.82.24` |
| `DEPLOY_USER` | `ubuntu` |
| `DEPLOY_SSH_KEY` | `dw-yindu.pem` **全文**（`-----BEGIN ... KEY-----` 到 `END`） |
| `DEPLOY_APP_DIR` | `/home/ubuntu/auto-exchange`（可选） |

**不要**把 `app.env` / RDS / NVIDIA 密钥放进 GitHub Secrets。

---

## 3. 安全组

| 端口 | 说明 |
|------|------|
| 22 | 本机 IP；若用 Actions 云端 runner，需允许 GitHub 访问（或临时 `0.0.0.0/0`，有风险） |
| 8088 | 网站访问 |

若 Actions SSH 超时：用 **本机一键** `deploy-local.ps1`，或把 runner 改成 self-hosted。

---

## 4. 触发

- **自动**：`git push origin main`
- **手动**：Actions → **Deploy EC2** → Run workflow
