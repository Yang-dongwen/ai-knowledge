# 部署到 AWS EC2 + RDS（第一版，尽量全量）

目标架构：

```text
浏览器 ──HTTP :80──► EC2
                      ├── web (Nginx + Vue 静态)
                      ├── okx-bot :8080
                      ├── whisper :8000
                      └── remotion :3100
                              │
                              │ 3306（仅安全组放行）
                              ▼
                         AWS RDS MySQL
                              │
                         Cloudflare R2（对象，可选）
```

- **MySQL 不在 Docker 里**，用你已有的 / 将创建的 **RDS**。
- **暂无域名**：用 EC2 **公网 IP** 访问 `http://x.x.x.x/`。
- 第一版 **带上** Whisper + Remotion；机器内存建议 **≥ 8GB**。

---

## 0. 你需要准备的东西

| 项 | 说明 |
|----|------|
| AWS 账号 | 能开 EC2、RDS |
| 一把密钥对 | SSH 登录 EC2（`.pem`） |
| AI Key | 至少 NVIDIA / OpenAI 等之一（聊天/生图/成片） |
| R2（可选） | 已有则填；没有可先 `STORAGE_PROVIDER=local`（改 compose 环境） |
| 域名 | **现在不需要** |

---

## 1. RDS 从零到能连（重点）

你本地还没访问过 RDS，按下面做即可。**第一版建议：RDS 与 EC2 同一区域、同一 VPC**，最省事。

### 1.1 创建实例（控制台）

1. 打开 **RDS → Create database**
2. 引擎：**MySQL 8.0**
3. 模板：开发可选 **Free tier** / **Dev/Test**
4. 设置：
   - DB instance identifier：随意，如 `okx-bot-db`
   - Master username：如 `admin`
   - Master password：自己设强密码（保存好）
5. 实例规格：`db.t3.micro` / `db.t4g.micro` 开发够用
6. **Connectivity**：
   - VPC：与即将创建的 **EC2 同一 VPC**
   - Public access：
     - **推荐 `No`**（只给 VPC 内 EC2 连）——本地笔记本默认连不上，**在 EC2 上初始化库**
     - 若坚持本机连：`Yes` + 安全组只放行**你的家庭/公司公网 IP** 的 3306（不要 `0.0.0.0/0`）
7. 创建后在 Connectivity 复制 **Endpoint**（形如 `xxx.ap-northeast-1.rds.amazonaws.com`）和 **Port（3306）**

### 1.2 安全组（必对，否则永远连不上）

做两条规则思路：

**RDS 安全组入站**

| 类型 | 端口 | 来源 |
|------|------|------|
| MySQL/Aurora | 3306 | **EC2 的安全组 ID**（推荐）或 EC2 私网 IP |

**EC2 安全组入站**

| 类型 | 端口 | 来源 |
|------|------|------|
| SSH | 22 | 仅你的 IP |
| HTTP | 80 | `0.0.0.0/0`（第一版无域名） |
| HTTPS | 443 | 以后有域名再开 |

不要把 RDS 3306 对全网开放。

### 1.3 在 EC2 上初始化库（推荐）

SSH 进 EC2 后（仓库已 clone 的前提下）：

```bash
# 安装客户端（脚本里也会自动装）
sudo apt-get update && sudo apt-get install -y mysql-client

export RDS_HOST='你的.endpoint.rds.amazonaws.com'
export DB_USER='admin'
export DB_PASSWORD='你的主密码'
export RDS_DATABASE='okx_bot'

bash deploy/scripts/init-rds.sh
```

脚本会：测 TCP → `CREATE DATABASE` → 导入 `okx-bot/src/main/resources/db/schema.sql` → `SHOW TABLES`。

**连不上时逐项查：**

1. EC2 与 RDS 是否同一 VPC / 路由可达  
2. RDS 安全组是否放行 **EC2 安全组** 的 3306  
3. Endpoint / 密码是否复制错  
4. 本机测：`mysql -h $RDS_HOST -u $DB_USER -p -e 'SELECT 1'`

### 1.4 （可选）本机访问 RDS

仅当 Public access = Yes 且安全组放行了你的公网 IP：

```bash
mysql -h 你的.endpoint.rds.amazonaws.com -P 3306 -u admin -p
```

否则本机连不上是**正常**的，用 EC2 初始化即可。

### 1.5 应用专用账号（建议）

主账号只用于建库，业务用低权限用户：

```sql
CREATE USER 'okx_bot'@'%' IDENTIFIED BY '另一强密码';
GRANT ALL ON okx_bot.* TO 'okx_bot'@'%';
FLUSH PRIVILEGES;
```

`deploy/.env` 里 `DB_USER` / `DB_PASSWORD` 用这个业务账号。

---

## 2. 开 EC2

| 项 | 建议 |
|----|------|
| 系统 | Ubuntu 24.04 LTS |
| 规格 | **全量**（bot+web+whisper+remotion）：`t3.large`（8G）起；吃紧就 `t3.xlarge` |
| 磁盘 | ≥ 40GB gp3 |
| 网络 | 与 RDS **同一 VPC** |
| 弹性 IP | 建议绑一个，避免重启换 IP |
| 安全组 | 22（你的 IP）、80 |

初始化（可用仓库脚本）：

```bash
# 上传或 git clone 本仓库后
sudo bash docs/vps-setup.sh   # swap、Docker、防火墙等
# 重新登录一次，使 docker 组生效
```

没有脚本时至少：

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu
# 2G swap 对 小内存 很有帮助
```

---

## 3. 配置并启动应用

```bash
cd /path/to/auto-exchange
cp deploy/.env.example deploy/.env
nano deploy/.env   # 填 RDS、JWT、PAY_PUBLIC_BASE_URL、AI Key、R2
```

必填最少项：

- `SPRING_DATASOURCE_*`（或 `RDS_HOST` + `DB_*`，按 example 拼好 URL）
- `AUTH_JWT_SECRET`（≥32 字符随机）
- `PAY_PUBLIC_BASE_URL=http://你的弹性公网IP`
- `AI_NVIDIA_API_KEY`（或其它 provider）

启动：

```bash
bash deploy/scripts/up.sh
# 或
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

首次构建 **较慢**（Maven、Remotion Chrome、Whisper 镜像）。

查看状态：

```bash
docker compose -f deploy/docker-compose.yml ps
docker compose -f deploy/docker-compose.yml logs -f okx-bot
```

浏览器：

```text
http://<弹性公网IP>/
```

默认种子管理员见 `.env` 的 `AUTH_ADMIN_*`（务必改密；验收后设 `AUTH_ADMIN_SEED_ENABLED=false`）。

---

## 4. 服务说明

| 容器 | 作用 | 对外 |
|------|------|------|
| `web` | Vue 静态 + `/api` 反代 | **:80** |
| `okx-bot` | Spring Boot | 仅内网 |
| `whisper` | 语音转写 | 仅内网 |
| `remotion` | 成片渲染 | 仅内网 |
| RDS | MySQL | AWS 托管 |

**路径约定（重要）**：`okx-bot` 与 `remotion` 共享 volume `app-data`，均挂载为 `/app/data`。Java 会把**绝对路径**传给 Remotion，路径必须一致。

生产配置入口：`deploy/config/application-docker.yml`（`SPRING_PROFILES_ACTIVE=docker`）。

---

## 5. 无域名时的注意点

- 前端 `baseURL` 是相对路径 `/api`，**用 IP 打开即可**，不用改前端代码。
- 支付回调 `pay.public-base-url` 填 `http://公网IP`；无 HTTPS 时部分支付渠道以后才接。
- 以后买域名：解析 A 记录到弹性 IP → 再加 Caddy/certbot 做 443。

---

## 6. 日常更新

```bash
cd /path/to/auto-exchange
git pull
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

只改前端时也会重建 `web` 镜像。

---

## 7. 排障速查

| 现象 | 处理 |
|------|------|
| okx-bot 起不来 / 连库失败 | `logs okx-bot`；在 EC2 上 `mysql -h $RDS_HOST -u ...`；查安全组 |
| 页面开了 API 502 | `docker compose ps`，看 okx-bot 是否 healthy |
| Whisper 很慢 / OOM | `WHISPER_MODEL=small`；升配或暂时停 whisper 容器 |
| Remotion 渲染失败 | `logs remotion`；确认与 bot 共用 `/app/data` |
| 抖音下载失败 | 准备 cookies 挂进容器并设 `VIDEO_COOKIES_FILE` |
| 内存不够 | 确认 `vps-setup` 的 swap；或去掉 remotion/whisper 先跑最小集 |

临时**不跑**重服务（省内存）可：

```bash
docker compose -f deploy/docker-compose.yml stop whisper remotion
# 同时需在运行中的 bot 配置里关闭对它们的强依赖（whisper fail-if-unavailable 已是 false）
```

最小集（只 bot+web）可后续再拆 `docker-compose.lite.yml`；当前 compose 按「第一版尽量全量」设计。

---

## 8. 安全清单（上线前）

- [ ] RDS 3306 不对公网 `0.0.0.0/0`
- [ ] `AUTH_JWT_SECRET` / 管理员密码已换
- [ ] `deploy/.env` 未进 git（已在 `.gitignore` 建议忽略）
- [ ] 仓库里 `application.yml` 若曾提交过 R2/密钥，**在云控制台轮换**
- [ ] 安全组 SSH 仅自己 IP
- [ ] 验收后 `AUTH_ADMIN_SEED_ENABLED=false`

---

## 9. 推荐落地顺序（一天内可完成）

1. 创建 **RDS**（同 VPC、Private）→ 记下 Endpoint  
2. 创建 **EC2**（同 VPC、8G+、弹性 IP）→ `vps-setup` + Docker  
3. clone 仓库 → `init-rds.sh` 导表  
4. 填 `deploy/.env` → `up.sh`  
5. 浏览器 `http://弹性IP/` 登录验证  
6. 再测聊天 / 文生图；视频与成片视内存情况验收  

有域名后再补 HTTPS，不必阻塞第一版。
