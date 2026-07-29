# 域名 dwcode.cloud 配置说明

## 当前状态（已可用）

| 项 | 值 |
|----|-----|
| 域名 | `dwcode.cloud` |
| DNS 服务商 | **DNSPod**（NS: `director.dnspod.net` / `snowfall.dnspod.net`） |
| A 记录 | `@` / `www` → **13.201.82.24** |
| 业务入口 | **http://dwcode.cloud:8088** |
| API | http://dwcode.cloud:8088/api/ （未登录 401） |
| `PAY_PUBLIC_BASE_URL` | `http://dwcode.cloud:8088` |

国内一般可**直连**（不经 Cloudflare workers.dev）。

---

## 访问方式对比

| 地址 | 国内直连 | HTTPS | 说明 |
|------|----------|-------|------|
| **http://dwcode.cloud:8088** | ✅ 较好 | ❌ | **推荐主入口** |
| http://13.201.82.24:8088 | ✅ | ❌ | IP 直连 |
| https://auto-exchange-proxy.dwcode.workers.dev | ❌ 常需代理 | ✅ | Worker 反代，海外/备用 |
| http://dwcode.cloud （无端口） | ⚠️ | ❌ | 当前 80 被占用，见下 |

---

## 为何是 `:8088` 而不是 80/443？

EC2 上 **80 / 443 已被 `xray` 进程占用**，业务 Docker 只映射了 **8088→容器 80**。

因此浏览器请带端口：

```text
http://dwcode.cloud:8088
```

### 若希望 `http://dwcode.cloud`（无端口）

需要任选其一：

1. **停掉或改掉 xray 占用的 80**，再把 compose 里 web 改为 `"80:80"`，然后：

   ```bash
   # compose.lite.yml 中 web.ports 改为
   - "80:80"
   ```

2. 或用 **Caddy/nginx 宿主机** 在空闲端口反代（仍要避开 xray）。

### 若希望 `https://dwcode.cloud`（443 + 证书）

1. 释放 443（或改 xray 端口）  
2. 安装 Caddy/certbot 申请 Let's Encrypt  
3. 反代到 `127.0.0.1:8088`  

**不要**为了国内访问再把域名橙云接到 Cloudflare 代理（国内又可能打不开）。

---

## DNS 记录（DNSPod 控制台）

已生效记录（若误删可按此恢复）：

| 主机记录 | 类型 | 记录值 | TTL |
|----------|------|--------|-----|
| `@` | A | `13.201.82.24` | 600 |
| `www` | A | `13.201.82.24` | 600 |

可选子域：

| 主机记录 | 类型 | 记录值 | 访问 |
|----------|------|--------|------|
| `app` | A | `13.201.82.24` | http://app.dwcode.cloud:8088 |

---

## 与 Worker 反代的关系

- **买域名后，Worker 地址仍然可用**，互不影响。  
- **国内用户主入口**：`http://dwcode.cloud:8088`  
- **海外/备用**：`https://auto-exchange-proxy.dwcode.workers.dev`  
- 业务回调建议用主入口，当前已配：

  ```env
  PAY_PUBLIC_BASE_URL=http://dwcode.cloud:8088
  ```

Worker 文档见 [worker-proxy.md](./worker-proxy.md)。

---

## 验证命令

```bash
# DNS
dig +short dwcode.cloud A
# 期望: 13.201.82.24

# 首页
curl -s -o /dev/null -w "%{http_code}\n" http://dwcode.cloud:8088/
# 期望: 200

# API
curl -s -o /dev/null -w "%{http_code}\n" http://dwcode.cloud:8088/api/
# 期望: 401（未登录）
```

---

## 安全组

AWS EC2 安全组需放行：

| 端口 | 用途 |
|------|------|
| **8088** | 网站（当前） |
| 22 | SSH |
| 80/443 | 仅当以后业务占用且释放 xray 后 |

---

## 检查清单

- [x] A 记录指向 EC2  
- [x] http://dwcode.cloud:8088 首页 200  
- [x] /api/ 401  
- [x] PAY_PUBLIC_BASE_URL 已更新  
- [ ] （可选）释放 80/443，配置无端口 HTTP/HTTPS  
- [ ] （可选）www / app 子域按需添加  

相关：[deploy/README.md](../README.md) · [worker-proxy.md](./worker-proxy.md)
