# 视频核心内容提取 AI Agent 开发文档（v2 - 持久化保存版）

**项目名称**：VideoCoreExtractor  
**版本**：v2.0（带持久化保存）  
**更新日期**：2026-07-12  
**更新重点**：增加视频、转录文字、核心内容的持久化保存，支持后续前端查看与管理

---

## 1. 项目目标（更新后）

### 核心目标
用户输入视频链接（抖音/B站/YouTube 等）后，系统完成以下流程并**持久化保存**：

1. 下载并保存**原始视频文件**
2. 提取音频（可选保存）
3. 使用 Whisper 转录音频，**单独保存带时间戳的完整文字**
4. 使用 LLM 生成**结构化核心内容**（要点、章节、思维导图、repurposed 脚本）
5. 将以上内容保存到数据库 + 文件系统，方便后续查看和前端展示

**最终效果**：
- 用户可以随时查看历史处理记录
- 可以看到原始视频 + 完整可搜索的转录文字 + AI 提炼的核心内容
- 支持按时间戳对齐查看

---

## 2. 系统架构（更新版）

### 整体架构

```
用户提交视频 URL
        ↓
Java Spring Boot 后端（LangChain4j Agent）
   ├── Agent Orchestrator
   ├── VideoDownloadService（下载 + 保存视频）
   ├── TranscriptionService（调用 Whisper + 保存转录文字）
   ├── SummarizationService（调用 LLM + 保存核心内容）
   ├── StorageService（文件存储 + 数据库持久化）
   └── TaskRepository（任务管理）
        ↓
本地存储层
   ├── 文件系统（/data/videos/、/data/audio/、/data/transcriptions/）
   └── 数据库（H2 / SQLite / PostgreSQL）
        ↓
可选前端页面（查看任务列表、转录文字、核心内容）
```

**关键变化**：
- 新增 **StorageService** 和 **TaskRepository**
- 转录文字和核心内容**必须持久化到数据库**
- 视频文件持久化到本地磁盘

---

## 3. 数据模型设计（核心）

### 3.1 VideoTask 实体（推荐）

```java
@Entity
public class VideoTask {
    @Id
    private String id;                    // UUID

    private String originalUrl;
    private String title;
    private String platform;              // douyin / bilibili / youtube

    // 文件路径
    private String videoPath;             // 视频文件路径
    private String audioPath;             // 音频文件路径（可选）

    // 转录内容（单独保存，便于前端展示和搜索）
    @Lob
    private String transcriptionJson;     // 存储带时间戳的完整转录

    // AI 生成的核心内容
    @Lob
    private String summaryJson;           // 结构化核心内容

    private TaskStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    // 其他元数据
    private Long duration;                // 视频时长（秒）
}
```

### 3.2 推荐的 JSON 结构示例

**transcriptionJson**（转录文字）：
```json
{
  "language": "zh",
  "duration": 125.6,
  "segments": [
    {"start": 0.0, "end": 3.45, "text": "大家好..."},
    {"start": 3.45, "end": 8.12, "text": "今天我们..."}
  ]
}
```

**summaryJson**（核心内容）：
```json
{
  "keyPoints": [
    {"timestamp": "00:01:23", "point": "核心观点1"},
    {"timestamp": "00:05:40", "point": "核心观点2"}
  ],
  "chapters": [...],
  "mindMapMarkdown": "...",
  "repurposeScript": "适合发到 X 的文案..."
}
```

---

## 4. 存储策略

| 内容               | 保存方式           | 路径 / 表                  | 原因 |
|--------------------|--------------------|----------------------------|------|
| 原始视频           | 文件系统           | `/data/videos/{taskId}/video.mp4` | 便于下载和播放 |
| 音频               | 文件系统（可选）   | `/data/audio/{taskId}/audio.mp3` | 后期扩展使用 |
| 转录文字（带时间戳） | 数据库 + JSON 文件 | 数据库 `transcriptionJson` 字段 | 前端展示、搜索 |
| 核心内容           | 数据库             | 数据库 `summaryJson` 字段   | 结构化查询和展示 |
| 任务元数据         | 数据库             | `video_task` 表            | 任务管理 |

**MVP 阶段推荐数据库**：
- 先用 **H2**（内存/文件）或 **SQLite**，快速开发
- 后期迁移到 **PostgreSQL**

---

## 5. API 接口设计（更新版）

### 核心接口

**1. 提交处理任务**
- `POST /api/v1/video/process`
- 请求体包含 URL 和选项
- 返回 `taskId`

**2. 查询任务状态与结果**
- `GET /api/v1/tasks/{taskId}`
- 返回完整任务信息（包含视频路径、转录文字、核心内容）

**3. 获取转录文字（单独接口，便于前端展示）**
- `GET /api/v1/tasks/{taskId}/transcription`
- 返回带时间戳的文字列表

**4. 获取核心内容**
- `GET /api/v1/tasks/{taskId}/summary`

**5. 历史任务列表**
- `GET /api/v1/tasks?page=0&size=20`

**6. 下载原始视频**
- `GET /api/v1/tasks/{taskId}/video`（返回文件流）

---

## 6. 核心模块更新

新增/重点模块：

- `StorageService`：负责文件保存和路径管理
- `TaskRepository` + `TaskService`：数据库操作
- `TranscriptionService`：调用 Whisper + 保存转录结果
- `SummarizationService`：调用 LLM + 保存结构化摘要
- `VideoDownloadService`：下载视频并保存到指定目录

---

## 7. 开发路线图（v2 版）

### Phase 1: MVP（带基础保存）
- [ ] 项目搭建 + LangChain4j 集成
- [ ] 下载视频并保存到本地磁盘
- [ ] 搭建 Whisper 微服务
- [ ] 实现转录 + 保存到数据库
- [ ] 实现 LLM 总结 + 保存结构化内容
- [ ] 提供查询任务详情接口
- [ ] Docker Compose 支持（包含数据库）

### Phase 2: 增强
- [ ] 支持音频单独保存
- [ ] 添加简单 Web 页面（任务列表 + 转录查看）
- [ ] 支持按关键词搜索转录内容
- [ ] 切换到 NVIDIA NIM（可选）

### Phase 3: 生产级
- [ ] 任务队列 + 异步处理
- [ ] 用户系统 + 权限管理
- [ ] 视频在线播放 + 时间轴对齐
- [ ] 自动生成 X 帖子的完整流程

---

## 8. 环境与部署（更新）

推荐使用 Docker Compose 包含以下服务：
- Java 后端
- Whisper 服务
- Ollama / NVIDIA NIM
- 数据库（H2 / PostgreSQL）

文件存储建议挂载到宿主机目录，避免容器删除后数据丢失。

---

## 9. 注意事项

- **存储空间管理**：视频文件可能较大，建议定期清理或做归档策略
- **转录文字保存**：必须单独保存，方便前端做时间轴展示和搜索
- **数据库字段**：`transcriptionJson` 和 `summaryJson` 使用 `@Lob` 或 JSON 类型存储
- **文件路径设计**：建议按 `taskId` 分文件夹存放，避免文件名冲突

---

## 10. 下一步行动

此版本文档已将**持久化保存**作为核心需求纳入。

**我可以立即提供的后续内容**：
1. 更新后的完整 Java 项目结构 + 关键代码（含 StorageService、Task 实体、Repository）
2. 数据库初始化脚本（H2 / SQLite）
3. Docker Compose 完整配置（含数据库）
4. API 返回示例（带保存路径的完整 JSON）

---

**文档状态**：v2.0（持久化保存版）  
已根据你的需求更新架构、数据模型、存储策略和 API 设计。

需要我现在就开始输出 **Phase 1 带保存功能的代码** 吗？还是先调整其他部分？请直接告诉我。