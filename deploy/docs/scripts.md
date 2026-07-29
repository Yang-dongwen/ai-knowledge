# 脚本说明

目录地图 → [../README.md](../README.md)

## 推荐分工

```text
改代码     →  git push
改密钥     →  sync-env-local.ps1
CI 挂了    →  deploy-local.ps1
本地后端   →  run-local.ps1
服务器上   →  server-deploy.sh / up.sh
```

---

## 本机（Windows）

| 脚本 | 作用 |
|------|------|
| **`sync-env-local.ps1`** | scp `deploy/env/app.env` → EC2，默认重启 compose |
| **`deploy-local.ps1`** | 打包代码 scp 到 EC2（不覆盖密钥），重建镜像 |
| **`run-local.ps1`** | `SPRING_PROFILES_ACTIVE=local` + `mvn spring-boot:run` |
| **`gen_profile_yml.py`** | 从 `env/app.env` 生成 `application-local.yml` |

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1
powershell -ExecutionPolicy Bypass -File deploy/scripts/deploy-local.ps1
python deploy/scripts/gen_profile_yml.py
powershell -ExecutionPolicy Bypass -File deploy/scripts/run-local.ps1
```

默认 EC2：`13.201.82.24`，PEM：`%USERPROFILE%\Downloads\aws_common\dw-yindu.pem`。

---

## 服务器（EC2）

| 脚本 | 作用 |
|------|------|
| **`server-deploy.sh`** | 可选 git pull + compose build/up + 探活（CI 入口） |
| **`up.sh`** | 仅 compose up（不 pull） |
| **`bootstrap-git.sh`** | 一次性：Deploy Key + clone，保留密钥 |
| **`init-rds.sh`** | 一次性：RDS 建库 + schema |

```bash
bash deploy/scripts/server-deploy.sh
bash deploy/scripts/up.sh
bash deploy/scripts/bootstrap-git.sh
set -a; source deploy/env/app.env; set +a
bash deploy/scripts/init-rds.sh
```

`server-deploy.sh` 环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `APP_DIR` | `$HOME/auto-exchange` | 应用根（= 仓库根） |
| `COMPOSE_FILE` | `deploy/stack/compose.lite.yml` | compose（相对仓库根） |
| `REF` | `main` | git ref |
| `SKIP_GIT` | `0` | `1` = 不 pull |

所有脚本统一路径：

| 项 | 路径（相对仓库根） |
|----|-------------------|
| 密钥 | `deploy/env/app.env` |
| 生产 compose | `deploy/stack/compose.lite.yml` |
| Docker | `docker compose --project-directory <仓库根> -f deploy/stack/... --env-file deploy/env/app.env` |

---

## 快速决策

| 我想… | 用 |
|-------|-----|
| 改完代码上线 | `git push` |
| 改服务器环境变量 | `sync-env-local.ps1` |
| CI 红了先上线 | `deploy-local.ps1` |
| 本机起后端 | `run-local.ps1` |
| 新库初始化 | `init-rds.sh` |
| 服务器改 git 部署 | `bootstrap-git.sh` |
