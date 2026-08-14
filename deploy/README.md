# 部署

这个目录只做两件事：

1. **本机跑起来**（Windows 开发）
2. **一键发到 AWS EC2**（现在这台：`13.201.82.24`，域名 `dwcode.cloud`）

Worker 反代、Cloudflare Tunnel 等旧方案已删。博客 Halo 跟工具台一起发，不需要单独一套流程。

---

## 先看懂四层

```text
deploy/
├── README.md     ← 你在这里（总览）
├── local/        ← 第 1 层：本机脚本 + 本机说明
├── aws/          ← 第 2 层：发到 AWS 的脚本 + AWS 说明
├── env/          ← 第 3 层：密钥模板（两层共用，真实密钥不进 Git）
└── stack/        ← 第 4 层：Docker 清单（主要给 AWS 用）
```

| 层 | 谁用 | 进去看什么 |
|----|------|------------|
| **[local/](./local/README.md)** | 你在自己电脑写代码 | 怎么起前后端、怎么连云端博客发文 |
| **[aws/](./aws/README.md)** | 要把代码/密钥弄上服务器 | 一键打包、同步密钥、服务器重建容器 |
| **[env/](./env/README.md)** | 改数据库/JWT/R2/Halo 令牌 | 哪个文件能提交、哪个绝对不能 |
| **[stack/](./stack/README.md)** | 改镜像、端口、域名反代 | 每个 compose / Dockerfile 管哪几个容器 |

Spring 业务配置不在这里：

| 文件 | 干什么 | Git |
|------|--------|-----|
| `okx-bot/src/main/resources/application-local.yml` | 本机真实配置 | 不提交 |
| `okx-bot/src/main/resources/application-ec2.yml` | 服务器只引用 `${环境变量}` | 提交 |

---

## 日常只记三条命令

```powershell
# 本机起后端（先有 application-local.yml）
powershell -File deploy/local/run.ps1

# 本机一键发到 AWS（不覆盖服务器上的密钥）
powershell -File deploy/aws/deploy.ps1

# 只改了 deploy/env/app.env，同步到 AWS 并重启
powershell -File deploy/aws/sync-env.ps1
```

也可以 `git push` 后到 GitHub Actions 里手动跑 **Deploy EC2**（效果等于服务器执行 `deploy/aws/server-deploy.sh`）。

---

## 线上入口

| 地址 | 是什么 |
|------|--------|
| https://dwcode.cloud | 工具台（Caddy → web → okx-bot） |
| https://blog.dwcode.cloud | 博客 Halo |
| http://dwcode.cloud:8088 | 备用，不经 Caddy |

详细步骤分别写在 **[local/README.md](./local/README.md)** 和 **[aws/README.md](./aws/README.md)**。
