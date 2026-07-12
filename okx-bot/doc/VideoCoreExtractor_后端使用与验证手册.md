# VideoCoreExtractor 后端使用与验证手册

**范围**：仅验证 Java 后端 API 与持久化是否可用（不含前端页面）  
**模块**：`com.dwcode.okxbot.video`  
**默认端口**：`8080`  
**版本**：v2 持久化版  

---

## 1. 前置要求

### 1.1 运行时环境

| 组件 | 版本/要求 | 说明 |
|------|-----------|------|
| JDK | **17+** | 项目 `java.version=17`；Maven 也需指向 JDK 17 |
| Maven | 3.6+ | 编译与启动 |
| MySQL | 5.7+ / 8.x | 库名默认 `okx_bot` |
| 磁盘空间 | 建议 ≥ 5GB 可用 | 视频 + Whisper 模型缓存 |

### 1.2 外部可执行工具（必须在 PATH 或配置绝对路径）

| 工具 | 用途 | 自检命令 |
|------|------|----------|
| **yt-dlp** | 下载抖音/B站/YouTube 等视频 | `yt-dlp --version` |
| **FFmpeg** | 从视频提取音频 | `ffmpeg -version` |

配置位置：`src/main/resources/application.yml`

```yaml
video:
  yt-dlp-path: yt-dlp      # 或绝对路径，如 C:/tools/yt-dlp.exe
  ffmpeg-path: ffmpeg
  work-dir: ./data/video   # 任务文件根目录
  cleanup-media: false     # false=保留视频/音频，便于下载接口验证
```

### 1.3 Whisper 转录服务（必须）

Java 后端**不内置** Whisper，需单独启动本地服务，默认：

- 地址：`http://127.0.0.1:8000`
- 健康检查：`GET /health`
- 转录接口：`POST /v1/audio/transcriptions`

**方式 A：Docker（推荐）**

```bash
# 在项目根目录 okx-bot/
docker compose -f docker-compose.video.yml up -d --build
```

首次构建会下载模型，CPU 模式较慢，属正常现象。

**方式 B：本机 Python**

```bash
cd whisper-service
pip install -r requirements.txt
# 可选环境变量：WHISPER_MODEL=medium  WHISPER_DEVICE=cpu  WHISPER_COMPUTE=int8
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 1.4 LLM 服务（必须）

总结步骤调用 OpenAI 兼容 Chat Completions。默认复用 `application.yml` 中的 `ai.providers`：

```yaml
ai:
  default-provider: nvidia
  providers:
    nvidia:
      base-url: https://integrate.api.nvidia.com/v1
      api-key: "你的密钥"     # 必填，空则总结阶段失败
      models:
        - id: deepseek-ai/deepseek-v4-flash

video:
  llm:
    provider: nvidia         # 对应 ai.providers 的 key
    model:                   # 空则用该供应商第一个模型
    temperature: 0.3
    max-tokens: 4096
```

也可换成 OpenAI / DeepSeek / 本地 Ollama（需 OpenAI 兼容 `/v1/chat/completions`）。

### 1.5 数据库

1. 创建库（若尚无）：

```sql
CREATE DATABASE IF NOT EXISTS okx_bot DEFAULT CHARACTER SET utf8mb4;
```

2. 建表（二选一）：

- **全新安装**：执行 `src/main/resources/db/schema.sql`（含 `video_task` 全字段）
- **已有旧表升级到 v2**：执行 `doc/video_task_v2_alter.sql`

3. 确认数据源：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/okx_bot?...
    username: root
    password: 你的密码
```

### 1.6 网络与平台注意

| 平台 | 说明 |
|------|------|
| YouTube | 可能需要代理；可在系统代理下运行 yt-dlp |
| 抖音 / B站 | 需 yt-dlp 版本较新；部分链接有时效/风控 |
| 建议 | 验收时优先用**短视频**（1–3 分钟），缩短下载与转录时间 |

---

## 2. 启动顺序

按顺序启动，任一步失败都会导致全链路不可用。

```text
① MySQL 就绪
② Whisper 服务 (8000)
③ 配置 AI api-key
④ 启动 Spring Boot 后端 (8080)
```

### 2.1 启动后端

```bash
# Windows 建议使用 JDK 17
set JAVA_HOME=C:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%

cd okx-bot
mvn spring-boot:run
# 或 IDE 运行 OkxBotApplication
```

启动成功日志中应无 DataSource / 端口冲突错误。

---

## 3. 分层自检清单（由底到顶）

验证时建议**先做依赖自检，再跑完整任务**，避免任务失败后不知卡在哪一层。

### L0 — 工具可执行

```bash
yt-dlp --version
ffmpeg -version
```

期望：正常输出版本号。

### L1 — Whisper 健康

```bash
curl http://127.0.0.1:8000/health
```

期望：

```json
{"status":"ok","model":"medium"}
```

### L2 — 后端进程与端口

```bash
curl http://127.0.0.1:8080/api/v1/video/tasks?page=0&size=1
```

期望：HTTP 200，JSON 形如：

```json
{
  "code": 0,
  "success": true,
  "message": "success",
  "data": {
    "items": [],
    "total": 0,
    "page": 0,
    "size": 1
  }
}
```

若连接失败：后端未启动或端口不对。  
若 500 且含 SQL：表结构未建或字段缺失（执行 v2 迁移）。

### L3 — 参数校验（不依赖下载）

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/video/process ^
  -H "Content-Type: application/json" ^
  -d "{}"
```

期望：`code=400`，提示 `url 不能为空`（说明 Controller + 校验链路正常）。

### L4 — 全链路业务（核心验收）

见第 5 节端到端流程。成功标志：

| 检查项 | 期望 |
|--------|------|
| 任务 `status` | `SUCCESS` |
| `GET .../transcription` | 有 `segments[]` 与 `text` |
| `GET .../summary` | 有 `keyPoints` / `chapters` |
| `GET .../video` | 返回视频二进制流，HTTP 200 |
| 磁盘 | `data/video/{taskId}/` 下存在 video、audio、json |
| 数据库 | `video_task` 行字段已填充 |

### L5 — 失败路径（可选）

| 操作 | 期望行为 |
|------|----------|
| 查询不存在 taskId | `code=404`，提示任务不存在 |
| 任务进行中查 transcription | `code=404`，提示尚未生成 |
| 关闭 Whisper 再提交任务 | 任务最终 `FAILED`，`errorMessage` 含 Whisper 连接失败 |
| 清空 AI api-key 再提交 | 任务在总结阶段 `FAILED`，提示供应商/密钥问题 |

---

## 4. API 使用说明

Base URL：`http://127.0.0.1:8080`

统一响应（除视频文件流外）：

```json
{
  "code": 0,
  "message": "success",
  "success": true,
  "data": { },
  "timestamp": "..."
}
```

- `code = 0`：成功  
- `code = 400`：参数错误  
- `code = 404`：资源不存在  
- `code = 500`：业务/系统错误（见 `message`）

### 4.1 提交处理任务

```http
POST /api/v1/video/process
Content-Type: application/json
```

**请求体**

```json
{
  "url": "https://www.bilibili.com/video/BVxxxxxxxx",
  "options": {
    "language": "zh",
    "extractMindMap": true,
    "generateRepurposeScript": true
  }
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `url` | 是 | 视频链接 |
| `options.language` | 否 | 默认 `zh` |
| `options.extractMindMap` | 否 | 默认 `true` |
| `options.generateRepurposeScript` | 否 | 默认 `true` |

**响应示例（立即返回，异步处理）**

```json
{
  "code": 0,
  "success": true,
  "data": {
    "taskId": "1928374650012345678",
    "status": "PENDING",
    "url": "https://...",
    "platform": "bilibili",
    "currentStep": "排队中",
    "createdAt": "2026-07-12 12:00:00"
  }
}
```

记下 `taskId`，后续查询均用此 ID。

### 4.2 查询任务详情

```http
GET /api/v1/video/tasks/{taskId}
```

兼容旧路径：`GET /api/v1/video/status/{taskId}`

**状态机**

```text
PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING → SUCCESS
                                              ↘ FAILED
```

| status | 含义 |
|--------|------|
| `PENDING` | 已入队 |
| `DOWNLOADING` | yt-dlp + FFmpeg |
| `TRANSCRIBING` | Whisper 转录中 |
| `SUMMARIZING` | LLM 总结中 |
| `SUCCESS` | 完成，`result` 有完整结构 |
| `FAILED` | 失败，看 `errorMessage` |

**SUCCESS 时 `data.result` 主要结构**

```json
{
  "videoId": "...",
  "title": "视频标题",
  "duration": 125.6,
  "sourceUrl": "...",
  "summary": {
    "keyPoints": [{"timestamp": "00:01:23", "point": "..."}],
    "chapters": [{"timestamp": "00:00:00", "title": "...", "summary": "..."}],
    "mindMapMarkdown": "...",
    "repurposeScript": "..."
  },
  "transcription": {
    "language": "zh",
    "durationSeconds": 125.6,
    "text": "全文...",
    "segments": [
      {"id": 0, "start": 0.0, "end": 3.45, "text": "大家好..."}
    ]
  }
}
```

### 4.3 分页任务列表

```http
GET /api/v1/video/tasks?page=0&size=20
```

| 参数 | 默认 | 说明 |
|------|------|------|
| `page` | 0 | 从 0 开始 |
| `size` | 20 | 最大 100 |

列表项一般不含完整 `result`，减轻体积。

兼容：`GET /api/v1/video/tasks/recent?limit=20`

### 4.4 获取转录文字

```http
GET /api/v1/video/tasks/{taskId}/transcription
```

任务至少完成到转录写入 DB 后才可访问；全失败且无转录则 404。

### 4.5 获取核心内容

```http
GET /api/v1/video/tasks/{taskId}/summary
```

返回 `keyPoints` / `chapters` / `mindMapMarkdown` / `repurposeScript`。

### 4.6 下载原始视频

```http
GET /api/v1/video/tasks/{taskId}/video
```

- 成功：HTTP 200，`Content-Type: video/mp4`（或其它媒体类型），body 为文件流  
- 失败：业务 JSON 或 404（文件未保留 / 路径为空）  
- 需 `video.cleanup-media: false` 且任务下载步骤成功  

浏览器可直接打开该 URL 尝试播放。

---

## 5. 端到端验证脚本（推荐流程）

将 `YOUR_VIDEO_URL`、`TASK_ID` 替换为实际值。以下为 **Windows PowerShell** 示例。

### 5.1 提交任务

```powershell
$body = @{
  url = "YOUR_VIDEO_URL"
  options = @{
    language = "zh"
    extractMindMap = $true
    generateRepurposeScript = $true
  }
} | ConvertTo-Json

$resp = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8080/api/v1/video/process" `
  -ContentType "application/json" `
  -Body $body

$resp | ConvertTo-Json -Depth 6
$taskId = $resp.data.taskId
Write-Host "taskId=$taskId"
```

### 5.2 轮询状态（直到 SUCCESS / FAILED）

```powershell
# 短视频一般 1–10 分钟；长视频视 Whisper 而定
do {
  Start-Sleep -Seconds 5
  $st = Invoke-RestMethod "http://127.0.0.1:8080/api/v1/video/tasks/$taskId"
  Write-Host ("{0}  status={1}  step={2}" -f (Get-Date -Format "HH:mm:ss"), $st.data.status, $st.data.currentStep)
} while ($st.data.status -notin @("SUCCESS", "FAILED"))

$st | ConvertTo-Json -Depth 10
```

### 5.3 校验各子接口

```powershell
# 转录
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/video/tasks/$taskId/transcription" |
  ConvertTo-Json -Depth 6

# 摘要
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/video/tasks/$taskId/summary" |
  ConvertTo-Json -Depth 6

# 列表
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/video/tasks?page=0&size=5" |
  ConvertTo-Json -Depth 6

# 视频文件（保存到本地）
Invoke-WebRequest "http://127.0.0.1:8080/api/v1/video/tasks/$taskId/video" `
  -OutFile ".\verify-output.mp4"
```

### 5.4 curl 等价（Git Bash / Linux / macOS）

```bash
# 提交
curl -s -X POST http://127.0.0.1:8080/api/v1/video/process \
  -H "Content-Type: application/json" \
  -d '{"url":"YOUR_VIDEO_URL","options":{"language":"zh"}}'

# 查详情
curl -s http://127.0.0.1:8080/api/v1/video/tasks/TASK_ID

# 转录 / 摘要
curl -s http://127.0.0.1:8080/api/v1/video/tasks/TASK_ID/transcription
curl -s http://127.0.0.1:8080/api/v1/video/tasks/TASK_ID/summary

# 下载视频
curl -L -o out.mp4 http://127.0.0.1:8080/api/v1/video/tasks/TASK_ID/video
```

---

## 6. 持久化验收（数据库 + 文件系统）

### 6.1 文件系统

路径规则（相对进程工作目录）：

```text
./data/video/{taskId}/
  ├── video.mp4              # 或其它后缀
  ├── audio.mp3
  ├── transcription.json
  └── summary.json
```

检查：

```powershell
# taskId 替换为实际值
Get-ChildItem ".\data\video\TASK_ID" -Recurse
```

| 文件 | 何时存在 |
|------|----------|
| `video.*` / `audio.*` | 下载成功后；`cleanup-media=false` 时保留 |
| `transcription.json` | 转录成功后 |
| `summary.json` | 总结成功后 |

### 6.2 数据库

```sql
SELECT id, platform, status, current_step, title,
       video_path, audio_path,
       transcription_path, summary_path,
       LENGTH(transcription_json) AS tr_len,
       LENGTH(summary_json) AS sm_len,
       LENGTH(result_json) AS rs_len,
       error_message, created_at, finished_at
FROM video_task
ORDER BY created_at DESC
LIMIT 5;
```

**SUCCESS 任务期望**：

- `status = 'SUCCESS'`
- `platform` 非空（如 `bilibili` / `youtube` / `douyin` / `other`）
- `transcription_json`、`summary_json`、`result_json` 长度 > 0
- `video_path` / `audio_path` 非空（未开启清理时）
- `error_message` 为空

---

## 7. 功能完整度验收表（后端）

按此表勾选即可判断「后端是否可用且完整」。

| # | 验收项 | 验证方式 | 通过标准 |
|---|--------|----------|----------|
| 1 | 服务可访问 | `GET /tasks` | `code=0` |
| 2 | 参数校验 | POST 空 body | 400 / url 不能为空 |
| 3 | 任务创建 | POST process | 返回 `taskId`，DB 有 `PENDING` 行 |
| 4 | 平台识别 | 详情 `platform` | 与 URL 域名匹配 |
| 5 | 异步下载 | 状态变为 `DOWNLOADING`→后续 | 非一直卡死；有进度步骤变化 |
| 6 | 视频落盘 | 磁盘 + `video_path` | 文件存在 |
| 7 | 音频提取 | 磁盘 `audio.*` | 文件存在 |
| 8 | Whisper 转录 | status `TRANSCRIBING`→后续 | `transcription_json` 有 segments |
| 9 | LLM 总结 | status `SUMMARIZING`→`SUCCESS` | `summary` 有 keyPoints |
| 10 | 详情含 result | GET tasks/{id} | `result.summary` + `result.transcription` |
| 11 | 独立转录 API | GET .../transcription | 与 DB 一致 |
| 12 | 独立摘要 API | GET .../summary | 与 DB 一致 |
| 13 | 视频下载 API | GET .../video | 200 + 可播放/非 0 字节 |
| 14 | 分页列表 | GET tasks?page=0 | `total`/`items` 正确 |
| 15 | 失败可观测 | 人为断 Whisper | `FAILED` + `errorMessage` 可读 |

**判定建议**：

- **后端可用**：1–3 通过  
- **主链路完整**：1–10 通过  
- **v2 持久化完整**：1–15 全部通过  

---

## 8. 常见问题排查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 编译 `无效的目标发行版` | Maven 使用了 JDK 8 | 设置 `JAVA_HOME` 为 JDK 17 |
| `GET /tasks` 500，SQL 报错 Unknown column | 未执行 v2 字段迁移 | 跑 `doc/video_task_v2_alter.sql` |
| 任务 `DOWNLOADING` 失败 | 未装 yt-dlp/ffmpeg，或链接失效/需代理 | 自检工具；更新 yt-dlp；配置代理 |
| 任务 `TRANSCRIBING` 失败 | Whisper 未启动或端口不对 | `curl :8000/health`；检查 `video.whisper.base-url` |
| 任务 `SUMMARIZING` 失败 | API Key 无效/额度/模型名错误 | 检查 `ai.providers.*.api-key` 与 `video.llm` |
| 转录极慢 | CPU + medium 模型 | 换更小模型 `tiny`/`base`，或启用 GPU |
| 视频接口 404 | `cleanup-media=true` 或下载失败 | 设 `cleanup-media: false` 后重跑任务 |
| 抖音链接下载失败 | 链接过期 / yt-dlp 过旧 | 用最新链接；`pip install -U yt-dlp` |

日志关键字（`logging.level.com.dwcode.okxbot: DEBUG`）：

```text
创建视频任务
任务状态更新
调用 Whisper 转录
调用 LLM
视频任务完成 / 视频任务失败
```

---

## 9. 配置速查

```yaml
# application.yml 与视频模块相关的关键项
server.port: 8080

spring.datasource:          # MySQL
  url / username / password

ai.providers.*.api-key:     # LLM 密钥

video:
  work-dir: ./data/video
  yt-dlp-path: yt-dlp
  ffmpeg-path: ffmpeg
  cleanup-media: false
  whisper.base-url: http://127.0.0.1:8000
  whisper.model: medium
  whisper.timeout-seconds: 600
  llm.provider: nvidia
  download.max-duration-seconds: 7200   # 超时长视频拒绝
  download.timeout-seconds: 600
```

---

## 10. 最小验收路径（5 分钟快速版）

适合只想确认「后端能不能跑通」：

1. `yt-dlp --version` && `ffmpeg -version`  
2. `curl http://127.0.0.1:8000/health`  
3. 启动后端 → `curl .../api/v1/video/tasks?page=0&size=1`  
4. POST 一个**短视频** URL  
5. 轮询 `GET .../tasks/{taskId}` 至 `SUCCESS`  
6. 再调 `transcription`、`summary`、`video` 三个接口  

六步全过 → 后端主功能可用。

---

**文档状态**：后端使用与验证手册 v1  
相关脚本：`doc/video_task_migration.sql`、`doc/video_task_v2_alter.sql`、`docker-compose.video.yml`
