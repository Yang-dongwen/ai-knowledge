# 视频核心内容提取 AI Agent 开发文档

**项目名称**：VideoCoreExtractor（视频核心内容提取器）  
**版本**：v0.1（MVP 规划）  
**日期**：2026-07-11  
**负责人**：Ge Dong  
**目标平台**：本地隐私优先 + Java 后端 AI Agent  
**适用场景**：抖音/B站/YouTube 等网站视频 → 自动提取核心要点、时间戳、思维导图、 repurposed 脚本（自媒体内容生产）

---

## 1. 项目背景与目标

### 1.1 背景
用户为 Java 后端开发工程师，正在探索 **LangChain4j / Spring AI** 构建 AI Agent。同时有自媒体运营需求（抖音视频 repurposing 到 X/Twitter），希望高效提取视频核心内容，避免逐帧观看。

### 1.2 核心目标
- 输入视频链接（抖音/B站/YouTube/任意网站）或本地文件
- 自动完成：下载 → 音频提取 → 本地转录 → LLM 智能总结
- 输出结构化结果：
  - 核心要点（带时间戳）
  - 章节大纲
  - 思维导图（Markdown / JSON）
  - 可 repurposed 的脚本 / 文案建议
- **隐私优先**：所有处理本地完成（可选混合云端 LLM）
- **可扩展为 Agent**：支持工具调用、多步推理、批量处理

### 1.3 非目标（MVP 阶段）
- 不做实时流处理
- 不做视频生成/剪辑自动化（后期扩展）
- 不处理付费墙/加密视频

---

## 2. 系统架构

### 2.1 整体架构图（文本描述）

```
用户 / 前端 / 自媒体工具
          ↓ (REST API)
Java Spring Boot 后端 (LangChain4j Agent)
   ├── Agent Orchestrator（工具调用决策）
   ├── VideoDownloadTool（yt-dlp + FFmpeg）
   ├── TranscriptionTool（调用本地 Whisper 服务）
   ├── SummarizationTool（调用本地 LLM）
   ├── RepurposeTool（生成 repurposed 内容）
   └── StructuredOutput（JSON 输出：要点 + 时间戳 + 脚本）
          ↓
本地微服务层
   ├── Python FastAPI（faster-whisper 转录服务） ← OpenAI 兼容接口
   └── Ollama / NVIDIA NIM（本地 LLM 服务，推荐 Qwen 系列）
          ↓
硬件层
   └── NVIDIA GPU（推荐） / CPU
```

**数据流**：
1. 用户提交视频 URL
2. Java Agent 决定调用 DownloadTool → 保存本地视频 + 音频
3. 调用 TranscriptionTool → 获取带时间戳的字幕
4. 调用 SummarizationTool（LLM） → 生成核心要点 + 思维导图 + repurposed 建议
5. 返回结构化 JSON + Markdown

---

## 3. 技术栈

### 3.1 Java 后端（主系统）
- **框架**：Spring Boot 3.x + LangChain4j（或 Spring AI）
- **Agent 能力**：Tool Calling、Memory、Structured Output、Chains
- **HTTP 客户端**：WebClient（调用本地微服务）
- **视频处理**：ProcessBuilder 调用 yt-dlp + FFmpeg
- **持久化**：可选 PostgreSQL / SQLite（历史记录、任务队列）
- **异步**：@Async 或 Spring Task / RabbitMQ（后期）

### 3.2 本地 AI 微服务
- **转录服务**：Python + FastAPI + faster-whisper（推荐）
  - 模型：large-v3 或 medium（中文效果好）
  - 接口：OpenAI Whisper 兼容（/v1/audio/transcriptions）
- **LLM 服务**（二选一）：
  - **推荐**：Ollama + Qwen3 / Qwen3.6 系列（中文强、工具调用好）
  - **高性能备选**：NVIDIA NIM（Qwen3-32B / Qwen3-Next，TensorRT 优化）
- **模型选择优先级**（中文视频）：
  1. Qwen3-32B / Qwen3-Next（推荐）
  2. Qwen3-7B / 14B（显存有限时）
  3. DeepSeek-R1（推理强）

### 3.3 基础设施
- **容器化**：Docker + Docker Compose（推荐）
- **硬件**：
  - 推荐：NVIDIA GPU（RTX 4060 Ti 16GB+ / 4090 / A系列）
  - 最低：16GB RAM + CPU（可用小模型）
- **操作系统**：Linux（推荐 Ubuntu） / Windows / macOS（Apple Silicon 可用 Ollama）

---

## 4. 核心模块设计

### 4.1 Java 模块划分（建议包结构）
```
com.videocoreextractor
├── agent/                  # LangChain4j Agent 定义
│   ├── VideoProcessingAgent.java
│   └── tools/              # 自定义 Tool
│       ├── DownloadVideoTool.java
│       ├── TranscribeAudioTool.java
│       ├── SummarizeContentTool.java
│       └── GenerateRepurposeTool.java
├── service/                # 业务服务
│   ├── VideoDownloadService.java
│   ├── TranscriptionService.java
│   └── LLMService.java
├── controller/             # REST API
│   └── VideoProcessController.java
├── dto/                    # 请求/响应 DTO（结构化输出）
│   └── VideoSummaryResponse.java
├── config/                 # LangChain4j 配置、Ollama/NIM 配置
└── util/                   # FFmpeg 工具类、ProcessHelper
```

### 4.2 自定义 Tool 示例（LangChain4j）
每个 Tool 实现 `Tool` 接口或用 `@Tool` 注解：

- `DownloadVideoTool`：输入 URL → 输出本地文件路径 + 元数据
- `TranscribeAudioTool`：输入音频路径 → 输出带时间戳字幕 JSON
- `SummarizeContentTool`：输入字幕 + 提示词 → 输出结构化摘要（要点、章节、思维导图、repurposed 脚本）
- `GenerateRepurposeTool`：输入摘要 → 输出适合 X 的文案版本（带 hook、价值点）

Agent 可自动规划调用顺序（例如先下载 → 转录 → 总结）。

---

## 5. API 接口设计（MVP）

### 5.1 核心端点

**POST /api/v1/video/process**
- 描述：提交视频链接，异步处理，返回任务 ID
- 请求体：
```json
{
  "url": "https://www.douyin.com/video/xxx",
  "options": {
    "extractMindMap": true,
    "generateRepurposeScript": true,
    "language": "zh"
  }
}
```
- 响应：`{ "taskId": "uuid", "status": "PROCESSING" }`

**GET /api/v1/video/status/{taskId}**
- 返回处理状态 + 最终结构化结果

**最终输出结构（VideoSummaryResponse）**：
```json
{
  "videoId": "...",
  "title": "...",
  "duration": 1234,
  "summary": {
    "keyPoints": [
      {"timestamp": "00:01:23", "point": "核心观点1"}
    ],
    "chapters": [...],
    "mindMapMarkdown": "...",
    "repurposeScript": "适合发 X 的文案..."
  },
  "transcription": { "segments": [...] }
}
```

---

## 6. 核心数据流程（MVP 版本）

1. 用户调用 `/process`
2. Java Agent 接收请求
3. 调用 `DownloadVideoTool`（yt-dlp 下载最高画质 + 提取音频）
4. 调用 `TranscribeAudioTool`（HTTP 调用本地 FastAPI Whisper 服务）
5. 调用 `SummarizeContentTool`（HTTP 调用 Ollama/NIM，prompt 包含“提取核心要点、时间戳、思维导图、 repurposed 建议”）
6. Agent 组装结构化输出
7. 返回结果 + 可选持久化到数据库

**Prompt 工程建议**（Summarize Tool）：
- 系统提示：你是专业的视频内容分析师，擅长提取核心要点并生成 repurposed 内容。
- 用户提示：包含字幕文本 + 要求输出 JSON（keyPoints, chapters, mindMap, repurposeScript）

---

## 7. 环境搭建与部署（推荐 Docker Compose）

### 7.1 一键启动建议（docker-compose.yml 结构）
```yaml
services:
  java-backend:
    build: ./java-app
    ports:
      - "8080:8080"
    depends_on:
      - whisper-service
      - ollama

  whisper-service:
    build: ./whisper-service
    ports:
      - "8000:8000"

  ollama:
    image: ollama/ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama-data:/root/.ollama
```

**启动命令**：
```bash
# 1. 拉取 Qwen 模型（推荐）
ollama pull qwen3:7b          # 先测试小模型
ollama pull qwen3:32b         # 显存充足时用大模型

# 2. 启动所有服务
docker compose up -d
```

### 7.2 NVIDIA NIM 备选部署
如果选择 NVIDIA NIM：
- 使用官方 NIM 容器部署 Qwen 模型
- baseUrl 指向 NIM endpoint（默认 8000 端口）
- LangChain4j 配置相同（OpenAI 兼容）

---

## 8. 开发路线图（建议分阶段）

### Phase 1: MVP（2-4 周）
- [ ] Spring Boot + LangChain4j 基础项目搭建
- [ ] 实现 DownloadVideoTool + FFmpeg 音频提取
- [ ] 搭建 Python FastAPI Whisper 微服务
- [ ] 实现 SummarizeContentTool（连接 Ollama）
- [ ] 基础 REST API + 结构化输出
- [ ] Docker Compose 一键部署

### Phase 2: Agent 增强（2-3 周）
- [ ] 完整 Agent + 多 Tool 自动规划
- [ ] Structured Output（JSON 强制输出）
- [ ] 思维导图生成 + Markdown 渲染
- [ ] 简单 Web UI（可选，Thymeleaf 或 React）

### Phase 3: 生产级（后期）
- [ ] 任务队列 + 异步处理（RabbitMQ / Spring Cloud Stream）
- [ ] NVIDIA NIM 切换支持（高性能模式）
- [ ] 历史记录 + 用户管理
- [ ] X/Twitter 自动发布集成（后期）
- [ ] 批量处理 + 监控 Dashboard

---

## 9. 硬件与性能建议

- **推荐配置**（流畅体验）：
  - GPU：RTX 4060 Ti 16GB / 4070 Ti / 4090
  - RAM：32GB+
  - 存储：SSD 500GB+

- **显存参考**（Qwen 模型）：
  - Qwen3-7B Q4：约 5-6GB
  - Qwen3-32B Q4：约 18-20GB
  - Qwen3-Next MoE：更高效，可跑更大参数

- **速度参考**（本地）：
  - 转录：中等长度视频（10-30min）约 2-5 分钟
  - LLM 总结：几秒到几十秒（取决于模型大小）

---

## 10. 注意事项与风险

1. **版权与合规**：仅用于个人学习、评论、原创衍生内容。禁止大规模商业侵权转载。
2. **模型选择**：优先 Qwen 系列（中文 + 工具调用强）。NVIDIA NIM 提供优化版。
3. **隐私**：本地方案下，视频和字幕永不离开用户机器。
4. **错误处理**：yt-dlp 可能因平台更新失效，需定期更新；Whisper 对强口音/背景音可能有误差。
5. **扩展性**：Agent 设计时预留 Tool 接口，后续可轻松增加“提取关键帧”“生成短视频脚本”等工具。

---

## 11. 下一步行动建议

1. **立即开始**：创建 Spring Boot 项目 + LangChain4j 依赖
2. **第一周目标**：跑通“URL → 下载 → 转录 → Ollama 简单总结”流程
3. **需要我提供的内容**：
   - 完整 pom.xml + LangChain4j 配置示例
   - Python FastAPI Whisper 服务完整代码
   - Agent Tool 实现代码模板
   - Docker Compose 完整文件
   - Prompt 模板库

---

**文档状态**：v0.1（可作为项目启动基线）  
**维护者**：Ge Dong  
**联系方式**：（可补充）

---

*此文档基于本地隐私优先 + Java AI Agent 方案设计，目标是帮助你快速落地一个实用、可扩展的视频智能处理系统。后续可根据实际开发反馈持续迭代。*

**祝开发顺利！** 如果需要把这份文档转成 Word（.docx）或进一步细化某个章节，请随时告诉我。