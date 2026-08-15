# AI 工具台

个人用的 AI 创作工作台：对话、视频提取、文章提取、成片、文生图，再配一套知识库（PC + 微信小程序）。后端统一鉴权，笔记可以发布到独立博客。

仓库在 GitHub 上只放**源码**。本页讲两件事：产品做什么，以及怎么在自己电脑跑起来。

---

## 它解决什么问题

把常见的「看视频 / 读文章 → 提炼 → 再写成图文或短片 → 存进自己的库」收成一条链路，而不是在十几个网站之间复制粘贴。

```text
浏览器  http://localhost:3000
            │  /api 转到 8080
            ▼
        okx-bot（Spring Boot）
            ├─ MySQL 8     用户、笔记、任务
            ├─ LLM API     对话 / 总结 / 分镜 / 润色
            ├─ yt-dlp + FFmpeg + Whisper    视频提取
            └─ Remotion（可选）              真渲染成片
```

登录之后从工作台进各个工具；知识库在顶栏单独入口。小程序只做知识库，不做 AI 工具。

---

## 功能说明

### 账号与权限

邮箱注册 / 登录 / 找回密码，JWT 会话。PC 可接 Google、GitHub OAuth；小程序可接微信一键登录。

角色大致是：

| 角色 | 能做什么 |
|------|----------|
| USER | 登录、用基础能力 |
| MEMBER | 会员权益（套餐、有效期叠加） |
| SUPER_ADMIN | 用户管理、以及仓库里仍保留的交易助手接口 |

本机默认会种一个管理员（见下方「本机部署」）。会员中心支持 Mock 充值，方便本地把开通 / 续费走通；支付宝 / 微信进件是后话。

### AI 对话

工作台「开始对话」。多供应商、OpenAI 兼容协议，可切换模型，支持流式输出。适合连续改稿、头脑风暴。密钥写在本机 `application-local.yml` 的 `ai.providers`。

### 视频提取

粘贴视频链接：下载 → 抽音频 → Whisper 转录 → LLM 总结，可选画面理解（`video_omni` 一类多模态模型）。结果可落到知识库再改。

本机要跑通这条链路，除了 JDK / MySQL，还需要 **yt-dlp、FFmpeg**，以及 **Whisper 服务**（仓库里 `okx-bot/whisper-service`，默认由 Java 拉起，端口 `8000`）。只测登录和知识库时不必装这些。

### 文章提取

粘贴新闻链接或正文，抽出核心要点，并可二次创作。和视频提取一样，成稿可以进知识库。

### AI 视频生成

一句话出分镜和口播，配 TTS 或配图，再用 Remotion 合成短片。渲染进程在 `aigen-remotion`（默认 `:3100`，也可由 okx-bot 托管）。2G 小机器上一般只在本机玩这条，不要和生产工具台挤在一起。

### AI 文生图

提示词（可先润色）走 NVIDIA FLUX 出图，支持多比例。密钥同样在 `ai.providers.nvidia`。

### 知识库

文件夹树、笔记（Markdown / 富文本）、标签、附件、回收站、分享链接。PC 用完整编辑器；微信小程序覆盖列表、快记、文件夹、标签、分享，和 PC 共用同一套账号与 `/api/v1/kb/*`。

笔记可以「发布到博客」：工具台只调博客的 HTTP API（个人令牌），**不**把博客源码或数据库并进本仓库。本机笔记仍在本机 MySQL；发出去的文章在云端博客。

### 交易助手（后台能力）

仓库名还带 auto-exchange：后端里仍有 OKX 模拟盘 / 实盘、均线策略、持仓订单、回测。日常产品入口是 AI 工具台，这些接口主要给管理员用。

---

## 仓库里有什么

| 目录 | 是什么 |
|------|--------|
| **[okx-bot](./okx-bot/)** | 后端。Spring Boot 3 / Java 17。接口、任务、Flyway 迁移 |
| **[okx-trading-web](./okx-trading-web/)** | PC 前端。Vue 3 + Vite，开发端口 **3000** |
| **[aigen-remotion](./aigen-remotion/)** | 成片用的 Remotion 模板和 HTTP 渲染服务 |
| **[kb-miniprogram](./kb-miniprogram/)** | 知识库微信小程序 |

更细的后端手册、各模块设计在 [okx-bot/README.md](./okx-bot/README.md) 和 [okx-bot/doc/](./okx-bot/doc/)。

---

## 本机部署

只跑「能登录的工具台」需要：**JDK 17、Maven、Node 20+、MySQL 8**。Windows / macOS / Linux 步骤相同，下面用 PowerShell 举例。

### 1. 建库

```sql
CREATE DATABASE IF NOT EXISTS okx_bot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

表结构在后端**第一次启动**时由 Flyway 自动建，不用手执行一堆 SQL。

### 2. 本机配置（必做）

真实密钥不能进 Git。复制模板后改成你的库账号和 AI Key：

```powershell
copy okx-bot\src\main\resources\application-local.yml.example okx-bot\src\main\resources\application-local.yml
```

至少核对：

- `spring.datasource`：本机 MySQL（常见 `root` / `123456`）
- `ai.providers.*.api-key`：要用对话 / 提取 / 出图时再填
- `auth.admin`：种子管理员，默认邮箱 `admin@okx-bot.local`、密码 `Admin@123456`

IDE 启动时 Active profiles = **`local`**。`application.yml` 里默认也是 `local`。

### 3. 起后端

```powershell
cd okx-bot
mvn spring-boot:run
```

或在 IDE 运行 `OkxBotApplication`。端口 **8080**。看到启动完成、Flyway 跑完即可。

### 4. 起前端

```powershell
cd okx-trading-web
npm install
npm run dev
```

浏览器打开 **http://localhost:3000**。Vite 会把 `/api` 转到 `http://localhost:8080`。

用上面的管理员账号登录。到这里：工作台、知识库、会员页都可以点；没填 AI Key 的工具会在调用时失败，属正常。

### 5. 按需再开的能力

| 你要测什么 | 额外准备 |
|------------|----------|
| 视频提取 | 安装 yt-dlp、FFmpeg；在 yml 里写**绝对路径**。首次进入 `okx-bot/whisper-service` 建 venv 并 `pip install -r requirements.txt` |
| AI 成片 | `cd aigen-remotion` → `npm install`；需要 TTS 时 `pip install edge-tts` |
| 微信小程序知识库 | 用微信开发者工具打开 `kb-miniprogram`，后端保持 `auth.wechat.mini.mock=true` 即可本机模拟登录 |
| 发布到博客 | 本机 yml 里配置 `halo.base-url` 和 `halo.token`（个人令牌，需文章 + 附件 + 分类/标签权限）。笔记仍在本机库；图片/附件会上传到博客 |

### 默认端口

| 服务 | 端口 |
|------|------|
| 前端 Vite | 3000 |
| okx-bot | 8080 |
| Whisper（视频提取） | 8000 |
| Remotion（成片） | 3100 |

### 常见问题

| 现象 | 处理 |
|------|------|
| 启动报缺少 `application-local.yml` | 先复制 `.example`，不要提交复制出来的文件 |
| MySQL 连不上 | 服务没开，或账号 / 库名和 yml 不一致；需要 MySQL **8+** |
| 前端能开但接口全失败 | 后端没在 8080，或 profile 不是 `local` |
| 视频下载 / 抽音频找不到命令 | IDE 读不到 PATH，把 yt-dlp、ffmpeg 写成绝对路径 |
| 发布博客 503 | `halo.enabled` 或 `token` 没配 |

---

## 本机目录怎么对应到功能

```text
okx-bot/src/main/java/com/dwcode/okxbot/
  auth/      登录注册、JWT、OAuth、小程序
  kb/        知识库
  blog/      发布到博客的适配器
  chat/      AI 对话
  video/     视频提取
  article/   文章提取
  aigen/     AI 成片
  imggen/    文生图
  member/    会员与支付
  okx/       交易助手

okx-trading-web/src/views/
  home/              工作台
  ai-chat/           对话
  video-extract/     视频提取
  article-extract/   文章提取
  video-generate/    成片
  image-generate/    文生图
  kb/                知识库
  member/            会员
```
