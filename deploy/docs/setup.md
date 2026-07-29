# 从零部署 EC2 + RDS（概要）

更细的日常操作见 [cicd.md](./cicd.md)；目录地图见 [../README.md](../README.md)。

## 架构

```text
浏览器 ──:8088──► EC2
                   ├── web (Nginx + Vue)
                   ├── okx-bot :8080
                   └── （可选 full）whisper / remotion
                              │
                         AWS RDS MySQL
                         Cloudflare R2
```

- MySQL **只用 RDS**，compose 内不起 MySQL。
- 2G 小机用 **`stack/compose.lite.yml`**；全量 whisper+remotion 用 **`compose.full.yml`**（建议 ≥ 8GB）。

## 准备

| 项 | 说明 |
|----|------|
| EC2 | 同区域 VPC；安全组 22（你的 IP）、8088（或 80） |
| RDS | MySQL 8；安全组只放行 EC2 安全组的 3306 |
| PEM | SSH 登录密钥 |
| 密钥文件 | `cp deploy/env/app.env.example deploy/env/app.env` 后填写 |

## 推荐顺序

1. 创建 RDS（同 VPC、Private）→ 记 Endpoint  
2. 创建 EC2 + Docker + swap  
3. 把代码放到 `~/auto-exchange`（git clone 或 `deploy-local.ps1`）  
4. 填 `deploy/env/app.env`  
5. 在 EC2：`set -a; source deploy/env/app.env; set +a` → `bash deploy/scripts/init-rds.sh`  
6. `bash deploy/scripts/server-deploy.sh`  
7. 浏览器 `http://公网IP:8088/`  
8. 配 Deploy Key + GitHub Actions Secrets → 以后 `git push` 即可  

## 安全清单

- [ ] RDS 3306 不对 `0.0.0.0/0`
- [ ] `AUTH_JWT_SECRET` / 管理员密码已换
- [ ] `deploy/env/app.env` 未进 git
- [ ] SSH 仅自己 IP（或受控范围）
- [ ] 验收后 `AUTH_ADMIN_SEED_ENABLED=false`
- [ ] `STORAGE_ENV_PREFIX=ec2`（勿用 `dev`）
