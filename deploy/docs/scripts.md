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
| **`halo-tunnel.ps1`** | 本机 `8090` → 线上 Halo，发文联调（见 [halo-blog.md](./halo-blog.md) §10） |
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
| **`init-rds.sh`** | 一次性：RDS 建库（含 `halo`）+ 表由 Flyway |
| **`bootstrap-halo.sh`** | 一次性：Halo `/system/setup` + 写 `HALO_PAT`（见 [halo-blog.md](./halo-blog.md)） |
| **`quick-tunnel.sh`** | Cloudflare 快速隧道：免费 `*.trycloudflare.com`（Worker 源站依赖它） |
| **`deploy-worker-proxy.ps1`** | 部署固定 `*.workers.dev` Worker 反代（详见 [worker-proxy.md](./worker-proxy.md)） |

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

| 项 | 路径 |
|----|------|
| 密钥文件 | 仓库根下 `deploy/env/app.env` |
| compose 注入 | `export APP_ENV_FILE=<绝对路径>`（`server-deploy` / `up` 已自动设置） |
| 生产 compose | `deploy/stack/compose.lite.yml` + `compose.blog.yml --profile blog` |
| Docker | `docker compose -f deploy/stack/compose.lite.yml -f deploy/stack/compose.blog.yml --profile blog --env-file deploy/env/app.env` |
| Halo 说明 | [halo-blog.md](./halo-blog.md) |

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
