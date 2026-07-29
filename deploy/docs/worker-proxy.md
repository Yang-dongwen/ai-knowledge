# Cloudflare Worker 反代（固定 workers.dev）

无自有域名时，用 **Worker + Quick Tunnel** 提供固定 HTTPS 入口。

---

## 0. 与自有域名的关系

国内主入口已改为：

```text
http://dwcode.cloud:8088
```

见 [domain-dwcode-cloud.md](./domain-dwcode-cloud.md)。  
Worker 地址仍可用（海外/备用），**不会**因买了域名而失效。

---

## 1. Worker 固定地址（海外/备用）

```text
https://auto-exchange-proxy.dwcode.workers.dev
```

| 项 | 值 |
|----|-----|
| Worker 名 | `auto-exchange-proxy` |
| workers.dev 子域 | `dwcode`（账号级） |
| 代码目录 | `deploy/worker-proxy/` |
| 配置 | `deploy/worker-proxy/wrangler.toml` |

**这是固定域名**：不改 Worker 名、不删 Worker，URL 不会变。  
（对比：Quick Tunnel 的 `*.trycloudflare.com` 会随容器重建而变。）

---

## 2. 架构

```text
浏览器
  │
  │  HTTPS（固定）
  ▼
https://auto-exchange-proxy.dwcode.workers.dev
  │  Cloudflare Worker（deploy/worker-proxy）
  │  ORIGIN_BASE → Quick Tunnel
  ▼
https://xxxx.trycloudflare.com
  │  cloudflared（EC2 compose profile tunnel）
  ▼
http://web:80  →  okx-bot:8080
  │
  EC2（docker compose lite）
```

**为什么多一层 Tunnel？**  
本账号下 Worker **直连 EC2 裸 IP:8088** 会异常（边缘返回 error 1003）。  
因此源站使用 EC2 上已运行的 **Cloudflare Quick Tunnel**（HTTPS），再转到本机 nginx。

---

## 3. 前置条件

1. **EC2 上 auto-exchange 已部署**（web + okx-bot 正常）
2. **Quick Tunnel 在跑**（Worker 的源站）
3. 本机已安装 **Node.js / npm**
4. Cloudflare 账号可登录（`wrangler login`）

---

## 4. 一次性部署步骤

### 4.1 保证 EC2 服务与 Tunnel

SSH 到 EC2：

```bash
cd ~/auto-exchange

# 业务容器（若未在跑）
export APP_DIR=$HOME/auto-exchange
export COMPOSE_FILE=deploy/stack/compose.lite.yml
export SKIP_GIT=1
bash deploy/scripts/server-deploy.sh

# 启动 Quick Tunnel，并记下 URL
bash deploy/scripts/quick-tunnel.sh start
bash deploy/scripts/quick-tunnel.sh url
# 例: https://knew-procedure-proxy-disciplines.trycloudflare.com
```

### 4.2 配置 Worker 源站

编辑本机仓库：

`deploy/worker-proxy/wrangler.toml`

```toml
name = "auto-exchange-proxy"
main = "src/index.js"
compatibility_date = "2024-11-01"
workers_dev = true
preview_urls = false

[vars]
# 必须与 quick-tunnel.sh url 一致（无尾斜杠）
ORIGIN_BASE = "https://knew-procedure-proxy-disciplines.trycloudflare.com"
ORIGIN_HOST = "13.201.82.24"
ORIGIN_PORT = "8088"
```

`ORIGIN_HOST` / `ORIGIN_PORT` 为备用；当前逻辑优先用 `ORIGIN_BASE`。

### 4.3 登录并部署 Worker

在**本机**仓库根目录：

```powershell
cd deploy\worker-proxy

# 首次：浏览器授权 Cloudflare
npx wrangler login

# 部署
npx wrangler deploy
```

或：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/deploy-worker-proxy.ps1
```

成功后输出类似：

```text
https://auto-exchange-proxy.dwcode.workers.dev
```

### 4.4 验证

```bash
# 首页（期望 200）
curl -s -o /dev/null -w "%{http_code}\n" https://auto-exchange-proxy.dwcode.workers.dev/

# API（未登录期望 401）
curl -s -o /dev/null -w "%{http_code}\n" https://auto-exchange-proxy.dwcode.workers.dev/api/
```

浏览器打开：https://auto-exchange-proxy.dwcode.workers.dev

### 4.5 业务回调地址（可选但推荐）

服务器 `deploy/env/app.env`：

```env
PAY_PUBLIC_BASE_URL=https://auto-exchange-proxy.dwcode.workers.dev
```

本机改完后同步：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1
```

或在 EC2 上改完后：

```bash
export APP_DIR=$HOME/auto-exchange COMPOSE_FILE=deploy/stack/compose.lite.yml SKIP_GIT=1
bash deploy/scripts/server-deploy.sh
```

---

## 5. 日常运维

### 5.1 只改业务代码

```text
git push → GitHub Actions → server-deploy
```

**不必**每次重部署 Worker。Worker 只做反代，不包含 Java/Vue 构建。

### 5.2 Quick Tunnel 挂了 / URL 变了

现象：固定域名打开 502 / 无法访问。

```bash
# EC2
bash deploy/scripts/quick-tunnel.sh start   # 或 restart
bash deploy/scripts/quick-tunnel.sh url
```

若 URL **与** `wrangler.toml` 里 `ORIGIN_BASE` **不一致**：

1. 更新 `ORIGIN_BASE`
2. 本机重新 `npx wrangler deploy`

### 5.3 修改反代逻辑

改 `deploy/worker-proxy/src/index.js` 后：

```powershell
cd deploy\worker-proxy
npx wrangler deploy
```

### 5.4 查看 Worker 实时日志

```powershell
cd deploy\worker-proxy
npx wrangler tail
```

---

## 6. 文件清单

| 路径 | 说明 |
|------|------|
| `deploy/worker-proxy/src/index.js` | 反代实现（路径/方法/SSE body 透传、X-Forwarded-*） |
| `deploy/worker-proxy/wrangler.toml` | Worker 名、ORIGIN_BASE |
| `deploy/worker-proxy/package.json` | 可选本地 scripts |
| `deploy/scripts/deploy-worker-proxy.ps1` | Windows 一键部署 |
| `deploy/scripts/deploy-worker-proxy.sh` | Linux 一键部署 |
| `deploy/scripts/quick-tunnel.sh` | EC2 上维护 Quick Tunnel |

凭据与构建产物勿提交：`.wrangler/`、`.dist/`、`node_modules/`（已在 `.gitignore`）。

---

## 7. 名称是否固定？

| 名称 | 是否固定 | 说明 |
|------|----------|------|
| `auto-exchange-proxy.dwcode.workers.dev` | **是** | 不改 `name`、不删 Worker 则不变 |
| `*.trycloudflare.com` | **否** | tunnel 容器重建后可能变 |
| 自有域名 Named Tunnel | 需自有 Zone | 无域名时不可用 |

**不要**随意：

- `wrangler delete auto-exchange-proxy`
- 修改 `wrangler.toml` 的 `name = "..."`（改名 = 换 URL）

---

## 8. 排障

| 现象 | 处理 |
|------|------|
| 固定域名 502 / origin_unreachable | EC2 tunnel 是否在跑；`ORIGIN_BASE` 是否最新并已 deploy |
| API 401 | 正常（未登录） |
| 首页 200、API 不通 | 看 `okx-bot` 日志；本机 `curl http://127.0.0.1:8088/api/` |
| 本机访问 workers.dev 异常、解析到奇怪 IP | DNS 污染：换 1.1.1.1/8.8.8.8，或用手机流量；用 EC2 `curl` 验证 |
| `shrill-dew-d53a.dwcode.workers.dev` 1003 | 该名在本账号曾异常，已改用 `auto-exchange-proxy` |
| Worker 直连 `http://公网IP:8088` 1003 | 已知问题；保持 `ORIGIN_BASE` 走 trycloudflare |

---

## 9. 与其它方案对比

| 方案 | 固定 URL | 需要自有域名 | 备注 |
|------|----------|--------------|------|
| **Worker + Quick Tunnel（当前）** | 是 | 否 | 需 tunnel 常开 |
| 仅 Quick Tunnel | 否 | 否 | URL 会变 |
| Named Tunnel | 是 | **是** | `named-tunnel.sh setup <fqdn>` |
| 公网 IP:8088 | 是（IP） | 否 | 无免费 HTTPS 证书 |

---

## 10. 检查清单（上线后）

- [ ] `https://auto-exchange-proxy.dwcode.workers.dev` 首页 200  
- [ ] `/api/` 未登录返回 401  
- [ ] EC2：`docker ps` 中有 `tunnel` 容器 Up  
- [ ] `quick-tunnel.sh url` 与 `wrangler.toml` 的 `ORIGIN_BASE` 一致  
- [ ] `PAY_PUBLIC_BASE_URL` 已改为 Worker 固定地址  

相关入口： [deploy/README.md](../README.md) · [scripts.md](./scripts.md) · [cicd.md](./cicd.md)
