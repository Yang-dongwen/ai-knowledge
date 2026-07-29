# 域名 dwcode.cloud 配置说明

## 当前状态（已可用）

| 项 | 值 |
|----|-----|
| 域名 | `dwcode.cloud` / `www.dwcode.cloud` |
| DNS | DNSPod → A → **13.201.82.24** |
| **主入口** | **https://dwcode.cloud** |
| HTTP | 自动跳转 HTTPS（Caddy） |
| 证书 | Let's Encrypt（Caddy 自动续期） |
| 备用 | http://dwcode.cloud:8088 （直连 web，无证书） |
| `PAY_PUBLIC_BASE_URL` | `https://dwcode.cloud` |

---

## 访问方式

| 地址 | 说明 |
|------|------|
| **https://dwcode.cloud** | **推荐主入口**（80/443 + HTTPS） |
| https://www.dwcode.cloud | 同上 |
| http://dwcode.cloud:8088 | 备用直连（不经 Caddy） |
| http://13.201.82.24:8088 | IP 备用 |
| https://auto-exchange-proxy.dwcode.workers.dev | Worker 反代（海外/备用，国内常需代理） |

---

## 架构

```text
浏览器
  │  :80 / :443
  ▼
Caddy（Docker）—— 自动 HTTPS
  │  reverse_proxy
  ▼
web (nginx) :80
  │
  ▼
okx-bot :8080
```

相关文件：

| 路径 | 作用 |
|------|------|
| `deploy/stack/Caddyfile` | 域名与反代规则 |
| `deploy/stack/compose.lite.yml` | `caddy` 服务映射 80/443 |
| `deploy/scripts/free-ports-for-web.sh` | 将原占用 80/443 的 xray 改端口 |

---

## 80/443 与 xray

此前 80/443 被 **xray（Shadowsocks）** 占用。已调整为：

| 服务 | 端口 |
|------|------|
| 网站 Caddy | **80 / 443** |
| xray SS（原 80） | **18080** |
| xray SS（原 443） | **18443** |
| 网站备用 | **8088** |

配置备份：`/usr/local/etc/xray/config.json.bak.*`  
若你客户端仍连旧 80/443，请改成 **18080 / 18443**。

---

## DNS 记录（DNSPod）

| 主机记录 | 类型 | 记录值 |
|----------|------|--------|
| `@` | A | `13.201.82.24` |
| `www` | A | `13.201.82.24` |

**不要**把域名开成 Cloudflare 橙云代理（国内可能再次打不开）。保持 DNSPod 直指 IP 即可。

---

## AWS 安全组

需放行：

| 端口 | 用途 |
|------|------|
| 22 | SSH |
| **80** | HTTP → HTTPS 跳转 / ACME |
| **443** | HTTPS 网站 |
| 8088 | 备用直连（可选） |
| 18080 / 18443 | 仅当你仍要用 xray SS 时 |

---

## 验证

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://dwcode.cloud/
# 200

curl -s -o /dev/null -w "%{http_code}\n" https://dwcode.cloud/api/
# 401

curl -sI http://dwcode.cloud/ | head -5
# 期望 301/308 到 https
```

Caddy 日志：

```bash
docker logs auto-exchange-lite-caddy-1 --tail 50
```

---

## 运维

- **日常发版**：`git push` / `server-deploy` 会带上 caddy，证书在 volume `caddy_data` 中保留  
- **改域名**：改 `deploy/stack/Caddyfile` 后重新 compose up  
- **证书问题**：检查 80/443 是否被占用、DNS 是否指向本机  

---

## 检查清单

- [x] 80/443 释放并给 Caddy  
- [x] Let's Encrypt 证书（dwcode.cloud / www）  
- [x] https://dwcode.cloud 200  
- [x] /api/ 401  
- [x] PAY_PUBLIC_BASE_URL=https://dwcode.cloud  
- [x] 8088 备用仍可用  

相关：[deploy/README.md](../README.md) · [worker-proxy.md](./worker-proxy.md)
