# Docker 清单

这一层是「服务器上要起哪些容器、镜像怎么打」。一般不用手敲 `docker compose`，由 [../aws/server-deploy.sh](../aws/server-deploy.sh) 调用。

本机开发通常**不用**这里的文件。

---

## 这一层每个文件

| 文件 | 作用 |
|------|------|
| **README.md** | 本文 |
| **compose.lite.yml** | **当前生产**。2G EC2：`okx-bot` + `web` + `caddy` |
| **compose.blog.yml** | 叠在 lite 上面，加 `halo` 容器，并给 okx-bot 注入 `HALO_*`。`server-deploy.sh` 发现它就会自动 `--profile blog` |
| **compose.full.yml** | 可选。再加 whisper、remotion。要 **≥ 8GB** 内存，现在这台 2G **不要用** |
| **Dockerfile.okx-bot** | 后端镜像（Java 17，打包 okx-bot） |
| **Dockerfile.web** | 前端：先 `npm build`，再用 nginx 托管静态文件并反代 `/api` |
| **Dockerfile.remotion** | 只给 `compose.full.yml` 用 |
| **nginx.conf** | 进 `web` 镜像：静态页 + `/api` → `okx-bot:8080` |
| **Caddyfile** | 宿主机 80/443。`dwcode.cloud` → web，`blog.dwcode.cloud` → halo |

compose 的 `build.context` 是仓库根（`../..`），所以必须在仓库根执行脚本，不要单独进 `stack/` 目录 compose。

---

## 容器关系（lite + blog）

```text
caddy :80/:443
  ├─ dwcode.cloud        → web:80  → 静态 Vue
  │                         └─ /api → okx-bot:8080
  └─ blog.dwcode.cloud   → halo:8090

okx-bot 发文：容器内 HTTP → http://halo:8090 （服务名，不走公网）
```

`web` 另外映射宿主机 **8088**，域名或证书挂了时还能 `http://公网IP:8088` 访问。

Halo 工作目录挂在宿主机 `/data/auto-exchange/halo`（主题在 `themes/`）。
