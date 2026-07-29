# okx-bot 后端操作指南

面向 **本目录后端** 的本地部署、联调与排障说明。前端（`okx-trading-web`）不在本文范围；业务接口均以 REST/SSE 提供。

仓库级目录索引见上层 **[../README.md](../README.md)**。

---

## 1. 项目是什么

`okx-bot` 是 Spring Boot 主后端，在 OKX 量化交易能力之上扩展了多套 AI 工具流水线：

| 能力 | 说明 | 代码包 |
|------|------|--------|
| **OKX 交易助手** | 模拟盘/实盘配置、均线策略、持仓/订单、回测、系统启停 | `okx` / `strategy` / `trading` / `backtest` |
| **认证与权限** | 邮箱注册登录、JWT、角色（USER / MEMBER / SUPER_ADMIN） | `auth` |
| **会员支付** | 套餐、Mock 支付开通 MEMBER、有效期叠加（支付宝/微信待进件） | `member` / `pay` |
| **AI 聊天** | 多供应商 OpenAI 兼容对话（流式） | `chat` |
| **视频核心提取** | 链接 → 下载 → 音频 → Whisper 转录 → LLM 总结（可选画面理解） | `video` |
| **AI 视频生成** | 提示词 → 分镜/镜头 → TTS 或配图 → Remotion 成片 | `aigen` |
| **AI 文生图** | 提示词（可选润色）→ NVIDIA FLUX 出图 | `imggen` |

辅助进程（由 Java 可托管或手动启动）：

| 目录 | 作用 |
|------|------|
| `whisper-service/`（本目录内） | 本地 faster-whisper 转录 HTTP 服务（默认 `:8000`） |
| `../aigen-remotion/` | Remotion 模板与 HTTP 渲染服务（默认 `:3100`） |

兄弟工程 `okx-trading-web`、`polymarket-ai-trader` 见上层仓库索引，**本文不展开**。

---

## 2. 目录结构

```text
okx-bot/
├── src/main/java/com/dwcode/okxbot/
├── src/main/resources/
│   ├── application.yml           # 主配置（DB、AI、video、aigen、imggen、auth）
│   └── db/schema.sql             # 全量建表脚本
├── whisper-service/              # Python Whisper 微服务
├── docker-compose.video.yml      # Whisper（及可选 Ollama）容器
├── data/                         # 运行时产物（视频/音频/成片，已 gitignore）
├── doc/                          # 模块级详细设计与手册
└── README.md                     # 本文
```

同级渲染工程：`../aigen-remotion/`（配置项 `aigen.remotion.project-dir` 默认指向该目录）。

---

## 3. 技术栈与运行时

| 类别 | 选型 |
|------|------|
| 语言 / 框架 | Java 17、Spring Boot 3.2.5、Spring Security、JWT |
| 持久化 | MySQL 8.x、MyBatis-Plus 3.5 |
| HTTP 客户端 | OkHttp（OKX API 等） |
| LLM 出站 | LangChain4j OpenAI 兼容（可回滚 OkHttp，见 `ai.chat-engine`） |
| 构建 | Maven |
| ASR | Python 3.10+、faster-whisper、FastAPI / uvicorn |
| 视频渲染 | Node.js 18+、Remotion 4、Express |
| 系统 CLI | yt-dlp、FFmpeg；TTS 可选 edge-tts |

默认 HTTP 端口：

| 服务 | 端口 |
|------|------|
| okx-bot | `8080` |
| whisper-service | `8000` |
| aigen-remotion | `3100` |

---

## 4. 外部工具依赖总表

按功能模块列出。**仅认证**时不必安装 Whisper / Remotion / edge-tts。

| 外部依赖 | 交易 | 聊天/模型 | 视频提取 | AI 视频生成 | 文生图 | 说明 |
|----------|:----:|:---------:|:--------:|:-----------:|:------:|------|
| JDK 17 + Maven | ✓ | ✓ | ✓ | ✓ | ✓ | 必需 |
| MySQL | ✓ | ✓ | ✓ | ✓ | ✓ | 必需 |
| NVIDIA / OpenAI 等 API Key | | ✓ | ✓ | ✓ | ✓ | `ai.providers.*.api-key` |
| yt-dlp | | | ✓ | | | 下载各平台视频 |
| FFmpeg | | | ✓ | ✓（ffprobe 等） | | 抽音频、探测时长 |
| Whisper 服务 | | | ✓ | | | 默认 Java 可托管本地进程 |
| edge-tts 或 Windows SAPI | | | | ✓（配音） | | `aigen.tts` |
| Node + aigen-remotion | | | | ✓（真渲染） | | 默认 Java 可托管 |
| Docker（可选） | | | ✓ | | | 仅用 compose 跑 Whisper 时 |

### 4.1 工具安装与自检（Windows 示例）

```powershell
# Java
java -version          # 需 17+
mvn -version

# 视频下载与处理（建议 winget 或官方包，并在 application.yml 写绝对路径）
yt-dlp --version
ffmpeg -version

# Node（Remotion）
node -v
npm -v

# TTS（推荐）
pip install edge-tts
edge-tts --version

# Python Whisper 本地依赖（若不用 Docker）
cd okx-bot\whisper-service
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
```

**路径注意**：IDE 启动的 Java 进程有时读不到 winget 更新后的 PATH。视频相关请在 `application.yml` 中配置 **绝对路径**：

```yaml
video:
  yt-dlp-path: C:/path/to/yt-dlp.exe
  ffmpeg-path: C:/path/to/ffmpeg.exe
```

### 4.2 代理

访问 OKX、部分海外视频站或云端 AI 时可能需要代理。当前配置示例：

```yaml
okx:
  proxy-host: 127.0.0.1
  proxy-port: 7897
```

按本机代理端口修改。

---

## 5. 数据库初始化

### 5.1 创建库

```sql
CREATE DATABASE IF NOT EXISTS okx_bot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

### 5.2 建表

**全新环境（推荐）**：直接执行全量脚本：

```text
src/main/resources/db/schema.sql
```

覆盖表包括：交易侧（`okx_config`、`strategy_config`、`market_candle`、订单/持仓/成交、回测）、`chat_*`、`video_task`、`sys_user` / `email_code`、`ai_model_config`、`aigen_task`、`imggen_task` 等。

**从旧库升级**：按需执行 `doc/sql/` 下增量脚本（如 `video_task_v2_alter.sql`、`aigen_task_*.sql`、`auth_tables.sql` 等）。新装勿重复执行冲突的 alter。

### 5.3 模型配置种子（建议）

`schema.sql` 会建 `ai_model_config` 表；**模型列表初始数据**见：

```text
doc/sql/ai_model_config.sql
```

以及后续 capability / 协议相关 alter。**供应商 API Key 不在库表中**，仍写在 `application.yml` 的 `ai.providers`。

### 5.4 数据源

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/okx_bot?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: 你的密码
```

---

## 6. 核心配置说明

主文件：`src/main/resources/application.yml`  
Profile：默认 `paper`（模拟盘）。切换：

```text
--spring.profiles.active=paper   # 模拟盘
--spring.profiles.active=prod    # 实盘配置（live-enabled 默认仍为 false，需显式开启）
```

### 6.1 认证 / 超管种子

```yaml
auth:
  jwt:
    secret: 请换成足够长的随机密钥   # 生产必须改
    expire-seconds: 7200
  mail:
    console-mode: true              # true=验证码打日志，不真发邮件
  admin:
    seed-enabled: true              # 库中无 SUPER_ADMIN 时首次启动自动建号
    email: admin@okx-bot.local
    password: Admin@123456
```

本地默认：登录邮箱 `admin@okx-bot.local`，密码 `Admin@123456`。上线后请改密或关闭 `seed-enabled`。

### 6.2 AI 供应商

```yaml
ai:
  default-provider: nvidia
  chat-engine: langchain4j          # langchain4j | okhttp
  providers:
    nvidia:
      base-url: https://integrate.api.nvidia.com/v1
      api-key: "你的 nvapi-..."      # 勿提交真实密钥到公开仓库
    openai:
      base-url: https://api.openai.com/v1
      api-key: ""
    deepseek:
      base-url: https://api.deepseek.com/v1
      api-key: ""
```

模型展示列表以表 `ai_model_config` 为准（capability：`chat` / `image` / `video_omni` 等）。

### 6.3 视频提取 `video.*`

| 配置 | 含义 |
|------|------|
| `work-dir` | 任务文件根目录，默认 `./data/video` |
| `yt-dlp-path` / `ffmpeg-path` | 可执行文件路径 |
| `whisper.base-url` | 默认 `http://127.0.0.1:8000` |
| `whisper.managed.enabled` | `true` 时 Spring 启动/停止本地 `whisper-service` |
| `llm.provider` | 总结用供应商，空则用 `ai.default-provider` |
| `understanding.mode` | `audio_only`（默认）/ `hybrid` / `omni_only` |
| `cleanup-media` | `false` 保留媒体便于下载验收 |

### 6.4 AI 视频生成 `aigen.*`

| 配置 | 含义 |
|------|------|
| `mock-pipeline` | `true` 三步全 mock，不调外部 |
| `steps.plan/asset/render` | 各自 `real` / `mock` |
| `default-pipeline-mode` | `visual`（画面优先）或 `template`（口播模板） |
| `tts.provider` | `auto` / `edge` / `windows` / `mock` |
| `remotion.manage-process` | `true` 时 Java 托管 Node 渲染进程 |
| `remotion.project-dir` | 默认 `../aigen-remotion`（相对 okx-bot 运行目录） |
| `work-dir` | 默认 `./data/aigen`（生产建议绝对路径） |

### 6.5 文生图 `imggen.*`

| 配置 | 含义 |
|------|------|
| `enabled` | 总开关 |
| `steps.enhance` | `off` / `real` / `mock` |
| `steps.generate` | `real`=NVIDIA FLUX，`mock`=本地假图 |
| `flux.invoke-url` | GenAI 端点兜底；模型优先读 `ai_model_config`（capability=image） |

### 6.6 交易安全

- `paper`：`okx.simulated=true`，`trading.live-enabled=false`
- `prod`：`live-enabled` 默认仍为 `false`，**切勿误开实盘**
- 交易类 API 仅 `SUPER_ADMIN` 可访问（见下文权限）

---

## 7. 启动顺序（推荐）

```text
① MySQL 就绪并完成建表
② 填写 application.yml（数据源、ai.api-key、yt-dlp/ffmpeg 路径）
③ （可选）手动起 Whisper：Docker 或 Python —— 若 managed=true 可跳过
④ （可选）aigen-remotion：npm install 一次；manage-process=true 可跳过手动启动
⑤ 启动 okx-bot
⑥ 登录拿 JWT，再调业务 API
```

### 7.1 启动 okx-bot

**配置分工（按你的要求）：**

| 文件 | profile | 内容 | Git |
|------|---------|------|-----|
| `application-local.yml` | `local` | **真实**密钥/本机库/Windows 路径 | ❌ **不提交** |
| `application-ec2.yml` | `ec2` | **变量** `${...}`，密钥在服务器 `app.env` | ✅ **要提交** |

两者都用 **R2**，`env-prefix`: `local` / `ec2` 区分路径。

```powershell
# 生成本地 yml（可从 deploy/env/app.env 填 R2/AI）
python deploy/scripts/gen_profile_yml.py

# 本地启动
powershell -ExecutionPolicy Bypass -File deploy/scripts/run-local.ps1

# 只同步服务器密钥（deploy/env/app.env）
powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1
```

部署目录地图见 [deploy/README.md](../deploy/README.md)；脚本说明见 [deploy/docs/scripts.md](../deploy/docs/scripts.md)。

IDE：Active profiles = `local`。

### 7.2 Whisper（二选一）

**A. 由 Java 托管（默认）**

```yaml
video.whisper.managed.enabled: true
video.whisper.managed.working-dir: ./whisper-service
```

首次需在 `whisper-service` 建好 `.venv` 并装好依赖。`python-path` 为空时自动用 `.venv/Scripts/python.exe`（Windows）。

**B. Docker**

```powershell
cd okx-bot
docker compose -f docker-compose.video.yml up -d --build
```

**C. 手动 uvicorn**

```powershell
cd okx-bot\whisper-service
# 激活 venv 后
$env:WHISPER_MODEL = "small"
$env:WHISPER_DEVICE = "cpu"
$env:WHISPER_COMPUTE = "int8"
uvicorn main:app --host 0.0.0.0 --port 8000
```

健康检查：

```powershell
curl http://127.0.0.1:8000/health
```

### 7.3 aigen-remotion（真渲染）

首次：

```powershell
cd aigen-remotion
npm install
```

之后：

- **默认**：`aigen.remotion.manage-process=true`，启动 okx-bot 会自动 `node server/index.mjs`
- **手动**：`manage-process=false` 后执行 `npm run render-server`

健康检查：`http://127.0.0.1:3100/health`（首次 webpack bundle 可能较慢）。

Studio 预览（可选）：`npm run dev`。

### 7.4 仅交易联调最小集

只需：MySQL + okx-bot。无需 Whisper / Remotion / AI Key（聊天与 AI 工具不可用）。

### 7.5 无云端 Key 的 mock 联调

```yaml
aigen:
  mock-pipeline: true          # 或 steps 分步 mock
imggen:
  mock-pipeline: true
  # 或 steps.generate: mock
video:
  understanding:
    mock: true                 # 仅画面理解 mock；转录仍需 Whisper
```

视频提取的**下载/转录**无法完全 mock 云端；可关 Whisper 后验证任务失败路径。

---

## 8. 认证与权限

### 8.1 登录拿 Token

```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "admin@okx-bot.local",
  "password": "Admin@123456"
}
```

响应中的 JWT 后续放在：

```http
Authorization: Bearer <token>
```

其它公开接口（无需登录）：

- `POST /api/auth/register/send-code`
- `POST /api/auth/register`
- `POST /api/auth/password/send-code`
- `POST /api/auth/password/reset`

开发环境 `auth.mail.console-mode=true` 时，验证码打印在 **后端控制台日志**。

### 8.2 角色

| 角色 | 能力 |
|------|------|
| `USER` / `MEMBER` | 登录后：AI 聊天、视频提取、视频生成、文生图等用户任务；`MEMBER` 经支付开通，见 `member_expire_at` |
| `SUPER_ADMIN` | 上述 + 交易后台 + 用户管理 + `ai_model_config` CRUD；**禁止购买会员** |

交易与管理路径（需 `SUPER_ADMIN`）：

- `/api/admin/**`
- `/api/dashboard/**`、`/api/okx/**`、`/api/strategies/**`
- `/api/positions/**`、`/api/trades/**`、`/api/orders/**`
- `/api/strategy-run-logs/**`、`/api/system/**`、`/api/backtests/**`
- `/api/v1/video/model-configs/**`

其余业务 API 默认 **已登录即可**。

### 8.3 统一响应

```json
{
  "code": 0,
  "success": true,
  "message": "success",
  "data": {},
  "timestamp": "..."
}
```

- `code = 0` 成功；`401` 未登录；`403` 无权限；`400` 参数错误；`404` 不存在。

---

## 9. 后端模块与 API 一览

Base URL：`http://127.0.0.1:8080`  
以下均需 JWT（公开认证接口除外）。

### 9.0 会员支付（Mock）

设计文档：`doc/会员充值与支付宝微信支付对接架构设计方案.md`  
升级 SQL：`doc/sql/member_pay.sql`（需先执行再建表/加列）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/member/plans` | 上架套餐 |
| GET | `/api/member/status` | 当前会员状态（含惰性降级） |
| POST | `/api/pay/orders` | 创建订单 body:`planId,channel,clientType`；channel=`mock` |
| GET | `/api/pay/orders/{orderNo}` | 查询本人订单 |
| POST | `/api/pay/mock/confirm` | Mock 确认支付 body:`{orderNo}`（需 `pay.mock-enabled=true`） |
| POST | `/api/pay/notify/alipay` | 支付宝异步通知（匿名，协议 body `success`/`failure`） |
| GET | `/api/pay/return/alipay` | 支付宝同步回跳（不履约） |

`GET /api/auth/me` 扩展字段：`memberExpireAt`、`memberActive`。

**支付宝直连（默认关闭）**：`pay.alipay.enabled=false`。进件后配置 `ALIPAY_APP_ID` / `ALIPAY_PRIVATE_KEY` / `ALIPAY_PUBLIC_KEY`，设 `enabled=true`，`pay.public-base-url` 为公网 HTTPS。无资质时用 `channel=mock`。

### 9.1 认证 ` /api/auth`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/login` | 登录 |
| POST | `/register`、`/register/send-code` | 注册 |
| POST | `/password/*` | 重置密码 |
| GET | `/me` | 当前用户 |
| POST | `/logout` | 登出（前端丢弃 Token 即可） |

### 9.2 交易与系统（SUPER_ADMIN）

| 前缀 | 说明 |
|------|------|
| `/api/okx` | API Key 配置、连通性、余额 |
| `/api/strategies` | 策略 CRUD、启停 |
| `/api/positions`、`/api/orders`、`/api/trades` | 持仓 / 订单 / 成交 |
| `/api/strategy-run-logs` | 策略运行日志 |
| `/api/dashboard` | 看板概览 |
| `/api/system` | 全局交易状态 stop/resume |
| `/api/backtests` | 回测任务与资金曲线 |

定时任务（应用内）：`MarketSyncJob`、`StrategyRunJob`（依赖系统 RUNNING 与策略 enabled）。

### 9.3 AI 聊天 `/api/chat`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/models` | 可用模型 |
| GET/DELETE | `/conversations...` | 会话与消息 |
| POST | `/send` | **SSE 流式**对话 |

### 9.4 视频提取 `/api/v1/video`

```text
提交 URL → PENDING → 下载(yt-dlp) → 抽音频(FFmpeg)
       → 转录(Whisper) → [可选画面理解] → 总结(LLM) → SUCCESS
```

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/process` | 提交任务（body: `url` + options） |
| GET | `/tasks`、`/tasks/{id}`、`/status/{id}` | 列表与状态 |
| GET | `/tasks/{id}/transcription`、`/summary` | 转录 / 摘要 JSON |
| GET | `/tasks/{id}/video` | 视频文件流 |
| POST | `/tasks/{id}/pause`、`/retry` | 暂停 / 重试 |
| DELETE | `/tasks/{id}` | 删除 |
| GET | `/events` | **SSE** 任务状态推送 |
| GET/POST | `/models`、`/models/test` | 模型列表与连通性测试 |
| * | `/model-configs/**` | 模型配置 CRUD（超管） |

验收自检顺序建议：yt-dlp/ffmpeg → Whisper `/health` → 登录后列表接口 → 短视频全链路。

| 想读什么 | 文档 |
|----------|------|
| **运行流程 / 工具串联 / Mermaid 一页纸** | [`doc/VideoCoreExtractor_视频提取运行流程教程.md`](./doc/VideoCoreExtractor_视频提取运行流程教程.md) |
| 分层验收与 curl | [`doc/VideoCoreExtractor_后端使用与验证手册.md`](./doc/VideoCoreExtractor_后端使用与验证手册.md) |
| 实现细节与表结构 | [`doc/VideoCoreExtractor_核心逻辑与后端实现文档.md`](./doc/VideoCoreExtractor_核心逻辑与后端实现文档.md) |

### 9.5 AI 视频生成 `/api/v1/aigen`

```text
提示词 → Plan(LLM 分镜) → Asset(TTS 或镜头配图) → Render(Remotion) → MP4
```

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tasks` | 创建生成任务 |
| GET | `/tasks`、`/tasks/{id}` | 列表 / 详情 |
| GET | `/tasks/{id}/storyboard`、`/shots` | 分镜与镜头 |
| GET | `/tasks/{id}/media/output` | 成片 |
| POST | `/tasks/{id}/cancel`、`/pause`、`/retry` | 控制 |
| DELETE | `/tasks/{id}` | 删除 |
| GET | `/templates`、`/voices` | 模板与音色 |
| GET | `/events` | **SSE** |

流水线组合示例：

| 目标 | 配置 |
|------|------|
| 全 mock 跑通状态机 | `mock-pipeline: true` |
| 只测 LLM 分镜 | plan=real, asset=mock, render=mock |
| 完整出片 | plan=real, asset=real, render=real |

### 9.6 文生图 `/api/v1/imggen`

```text
提示词 → [可选 Enhance LLM] → Generate(FLUX) → 落盘 PNG/JPEG
```

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/models` | 生图模型 |
| POST | `/tasks` | 创建 |
| GET | `/tasks`、`/tasks/{id}` | 列表 / 详情 |
| GET | `/tasks/{id}/media/{fileName}` | 读图 |
| POST | `/tasks/{id}/cancel`、`/pause`、`/retry` | 控制 |
| DELETE | `/tasks/{id}` | 删除 |
| GET | `/events` | **SSE** |

---

## 10. 运行时目录与磁盘

| 路径 | 内容 |
|------|------|
| `data/video/{taskId}/` | 下载视频、音频、transcription/summary JSON |
| `data/aigen/{taskId}/` | 分镜、音频/图片资产、output.mp4 |
| `data/imggen/{taskId}/` | 生成图片与元数据 |

`data/` 已在仓库 `.gitignore` 中忽略。磁盘紧张时可打开对应 `cleanup-*` 配置。

---

## 11. 分层自检清单

在完整业务验收前，按层排查：

| 层级 | 检查 | 期望 |
|------|------|------|
| L0 工具 | `yt-dlp --version`、`ffmpeg -version`、`node -v`、`edge-tts --version` | 输出版本 |
| L1 Whisper | `GET http://127.0.0.1:8000/health` | `status=ok` |
| L1 Remotion | `GET http://127.0.0.1:3100/health` | 健康 |
| L2 后端 | `POST /api/auth/login` | 返回 token |
| L2 鉴权 API | `GET /api/v1/video/tasks?page=0&size=1` + Bearer | `code=0` |
| L3 参数校验 | 空 body 提交 video process | `400` |
| L4 业务 | 短视频提取 / mock aigen / mock imggen | 终态 SUCCESS 或明确 FAILED |

PowerShell 登录示例：

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/auth/login `
  -ContentType "application/json" `
  -Body '{"email":"admin@okx-bot.local","password":"Admin@123456"}'
$token = $login.data.token   # 字段名以实际响应为准，可能为 accessToken
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/video/tasks?page=0&size=1" -Headers $headers
```

---

## 12. 常见问题

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 启动失败 DataSource | MySQL 未启 / 库不存在 / 密码错 | 检查 URL 与建库 |
| SQL 字段缺失 | 未跑 schema 或增量脚本 | 执行 `schema.sql` 或对应 alter |
| 401 全接口 | 未带 JWT 或过期 | 重新登录；检查 `auth.jwt.secret` 是否被改导致旧 Token 失效 |
| 403 交易接口 | 非超管账号 | 使用 seed 超管或改库角色 |
| 视频下载失败 | yt-dlp 过旧、路径错误、需代理 | 升级 yt-dlp、写绝对路径、开代理 |
| 转录一直失败 | Whisper 未起 / 端口不对 / 模型加载慢 | 查 `:8000/health`；拉长 `startup-timeout-seconds` |
| 总结 FAILED | 无 API Key / 模型不可用 / 503 限流 | 填 `api-key`；看 `video.llm` 重试配置 |
| aigen 渲染失败 | remotion 未装依赖 / 端口占用 / work-dir 路径不一致 | `npm install`；查 `:3100/health`；work-dir 用绝对路径 |
| TTS 失败 | 无 edge-tts 且 Windows 无中文语音 | `pip install edge-tts` 或临时 `tts.provider: mock` |
| IDE 找不到 yt-dlp | PATH 未注入 IDE 进程 | yml 写绝对路径 |
| 实盘误下单 | profile/prod + live-enabled | 保持 `live-enabled: false` 直至明确上线流程 |
| SSE 异常日志 | 异步派发鉴权 | 已在 Security 放行 ASYNC/ERROR；勿对 SSE 二次写响应 |

---

## 13. 生产部署注意

1. **密钥**：更换 `auth.jwt.secret`；所有 `api-key` 用环境变量或密钥管理，勿提交仓库。  
2. **邮件**：关闭 `console-mode`，配置 `spring.mail`。  
3. **超管**：改默认密码，`auth.admin.seed-enabled: false`。  
4. **HTTPS**：公网必须 TLS；CORS 收紧 `allowedOriginPatterns`。  
5. **交易**：实盘前确认 `simulated`、`live-enabled`、系统 STOPPED 默认策略。  
6. **资源**：Whisper 与 Remotion 吃 CPU/内存；建议限 `max-concurrent-tasks`，独立机器或容器。  
7. **路径**：`work-dir`、CLI 工具、`aigen.remotion.project-dir` 使用绝对路径。  
8. **Remotion**：生产可设 `AIGEN_RENDER_TOKEN` 与 `aigen.remotion.render-token` 一致；`ALLOWED_WORK_ROOT` 限制渲染可读根目录。

---

## 14. 详细文档索引

均在 `doc/`：

| 文档 | 内容 |
|------|------|
| **`VideoCoreExtractor_视频提取运行流程教程.md`** | **流程教程：工具职责、串联、Mermaid 一页纸** |
| `VideoCoreExtractor_后端使用与验证手册.md` | 视频提取安装、分层验收、API |
| `VideoCoreExtractor_核心逻辑与后端实现文档.md` | 实现细节 |
| `VideoCoreExtractor_核心接口调用路径.md` | 按接口串调用链 |
| `VideoCoreExtractor_多模态视频理解_架构设计方案.md` | hybrid/omni 画面理解 |
| `AI视频生成_极简说明.md` | 生成链路心智模型 |
| `AI视频生成_Phase1_使用说明.md` | 生成实操与托管 Remotion |
| `AI视频生成_架构设计方案.md` / `Phase1_架构设计.md` | 架构规格 |
| `AI视频生成_语音接入方案.md` | TTS |
| `AI文生图_后端实现与开发手册.md` | 文生图开发与排障 |
| `Auth_登录架构与安全设计.md` | 认证安全 |
| `会员充值与支付宝微信支付对接架构设计方案.md` | 会员支付架构 |
| `LangChain4j_三工具切换架构设计.md` | Chat 出站引擎切换 |
| `sql/*.sql` | 增量 / 种子 SQL |

渲染侧：`aigen-remotion/README.md`。

---

## 15. 快速清单（复制用）

```text
[ ] JDK 17 + Maven + MySQL 8
[ ] 执行 schema.sql（+ 可选 ai_model_config 种子）
[ ] 修改 datasource / jwt.secret / ai.providers.api-key
[ ] （视频提取）yt-dlp + ffmpeg 绝对路径
[ ] （视频提取）whisper venv 或 docker；确认 :8000
[ ] （视频生成）aigen-remotion npm install；确认托管或 :3100
[ ] （配音）edge-tts 可选
[ ] mvn spring-boot:run → 登录超管 → 调目标模块 API
[ ] 产物目录 data/* 可写
```

---

**维护说明**：配置项以 `src/main/resources/application.yml` 与源码为准；接口路径以各 `*Controller` 的 `@RequestMapping` 为准。模块行为变更时请同步更新本文与 `doc/` 下对应手册。
