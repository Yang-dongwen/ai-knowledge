# AI 对话 Agent 架构设计与开发方案

**产品定位**：AI 工具台的**操作 Agent**——用对话完成「想清楚 → 调站内工具 → 看结果 → 再迭代」，不是通用全能 Bot。  
**范围**：`chat` + 复用 `video` / `aigen` / `imggen` 既有 Service；**不**重写任务流水线。  
**日期**：2026-07-17  
**前置能力（已具备）**：纯聊天、用户隔离、流式 SSE、真取消、重命名、重新生成、编辑重发、会话级 temperature/maxTokens/systemPrompt、LangChain4j、模型管理。  
**非目标（本期）**：外部联网搜索/天气、交易下单 Tool、完整黑盒多步自驱 Agent、重写 aigen/video Pipeline。

---

## 1. 目标与成功标准

### 1.1 目标

| 目标 | 说明 |
|------|------|
| **可用** | 闲聊仍可用；需要干活时能稳定调站内工具 |
| **安全** | 工具只动当前用户数据；写操作默认可确认 |
| **可演进** | Tool 可插拔；引擎可走 LangChain4j，可回退「规则+直调」 |
| **不伤存量** | 现有 `/api/chat/*` 与三工具 API 行为兼容 |

### 1.2 验收线（Agent MVP）

1. 用户可用自然语言**查询自己的**视频提取 / 文生图 / 视频生成任务列表与状态。  
2. 用户可发起「创建文生图/提取/视频生成」意图，系统给出**确认卡**，确认后真正创建任务并回写对话。  
3. 工具失败时有可读错误，不拖垮会话流。  
4. 纯闲聊路径与现网一致（流式、停止、参数）。  
5. 全程强制 `userId` 隔离；越权返回统一失败，不泄露他人任务。

### 1.3 明确不做

- 模型直接访问公网。  
- 无确认的批量创建任务。  
- Agent 内嵌 TTS/Remotion/FLUX 协议细节（只调现有 Service）。  
- 第一期不做 Plan 可视化多步编排（预留接口，二期再上）。

---

## 2. 现状与缺口

### 2.1 已有

```
ChatController → ChatService
  → 会话/消息 CRUD（user_id 隔离）
  → LlmChatGateway / ChatModelFactory（langchain4j | okhttp）
  → ChatStreamRegistry（停止生成）

VideoProcessService / AigenTaskService / ImgGenTaskService
  → 异步任务 + 状态机 + 用户隔离
```

### 2.2 缺口（相对「可用 Agent」）

| 缺口 | 影响 |
|------|------|
| 无 Tool 抽象与注册表 | 对话无法结构化调站内能力 |
| 无编排层（闲聊 vs 工具） | 只能全文硬聊，结果易幻觉 |
| 无确认协议 | 写工具不安全 |
| 前端无工具结果卡片 | 体验仍是纯文本 |
| 无 tool 调用审计 | 难排障、难限流 |

---

## 3. 总体架构

### 3.1 分层（铁律）

```
┌─────────────────────────────────────────────────────────┐
│  前端 ai-chat                                             │
│  文本流 + 工具确认卡 + 任务状态卡 + 快捷跳转               │
└───────────────────────────┬─────────────────────────────┘
                            │ SSE / REST
┌───────────────────────────▼─────────────────────────────┐
│  chat.controller                                          │
│  现有 chat API +（可选）确认执行 API                      │
└───────────────────────────┬─────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────┐
│  chat.agent（新建包）                                     │
│  ChatAgentOrchestrator  意图/模式分支                     │
│  ToolRegistry / AgentTool  工具契约                       │
│  ToolExecutionService   鉴权、确认、审计、调用            │
└───────┬───────────────────┬───────────────────┬─────────┘
        │                   │                   │
        ▼                   ▼                   ▼
  ImgGenTaskService   AigenTaskService   VideoProcessService
  （禁止改 Pipeline 内部；仅 Service 门面方法）
        │
        ▼
  LlmChatGateway（文本生成 / 可选 tool-call 决策）
```

**铁律**：

1. `Pipeline` / `Scheduler` / 渲染适配器 **不**依赖 `chat.agent`。  
2. Agent **只**调用各模块 `*TaskService` 已有 public 方法（或新增薄门面，不复制业务）。  
3. 所有 Tool 入口第一行：`SecurityUtils.requireCurrentUserId()` 并传入查询条件。  
4. Controller / Entity 尽量不出现 `dev.langchain4j.*`；langchain 仅落在 agent 适配层或现有 `common.ai`。

### 3.2 运行模式

| 模式 | 触发 | 行为 |
|------|------|------|
| **chat** | 默认；无工具意图 | 与现网完全一致的流式聊天 |
| **agent** | 用户开关「Agent 模式」或 system 标记 | 允许 Tool；写工具需确认 |
| **confirm_exec** | 前端点确认卡 | 不经过模型，直接执行已批准的 tool 调用 |

第一期推荐 **双模式**：默认 chat；顶栏开关 Agent。降低误触发与成本。

### 3.3 一次 Agent 轮次时序

```
1) 用户发消息（agentMode=true）
2) Orchestrator 组装 system（含工具说明）+ 历史
3) LLM 决策：
   a) 直接回复 → 流式 delta（同现网）
   b) 请求 tool_call → 不落「假结果」
4) 若 tool 为 read：
   → 执行 Tool → 把 result 再交给 LLM 总结（可二次流式）
5) 若 tool 为 write：
   → 不下发真实执行 → SSE event: tool_confirm
   → 前端展示确认卡
6) 用户确认 → POST /chat/agent/confirm
   → ToolExecutionService 执行 → event: tool_result
   → 可选再 LLM 总结一句
7) 全程可 stop（复用 ChatStreamRegistry）
```

**简化备选（Phase 0.5）**：不用模型原生 function call，用 **规则路由 + 固定 JSON 意图**（LLM 输出 `{"action":"list_tasks",...}`），实现更快、调试更易；Phase 1 再切 LangChain4j Tool Specification。

建议：**Phase 1 用「LLM JSON 意图 + 后端白名单 Tool」**，避免一上来深陷各厂商 tool_call 差异；接口形状与正式 Tool 对齐，后续可无痛替换决策层。

---

## 4. 工具目录（Tool Catalog）

### 4.1 工具契约

```text
AgentTool
  name(): String                 // 唯一 id，snake_case
  description(): String          // 给模型看的说明
  risk(): READ | WRITE
  parametersSchema(): JSON       // 简易 JSON Schema
  execute(ctx, args): ToolResult

ToolContext
  userId, conversationId, streamId?, locale

ToolResult
  ok: boolean
  code?: string
  message: string                // 给人看的短文案
  data?: object                  // 结构化，前端卡片用
  ui?: { type, payload }         // 可选 UI 提示
```

### 4.2 Phase 1 工具集（必做）

| name | risk | 说明 | 后端落点 |
|------|------|------|----------|
| `list_my_tasks` | READ | 按类型查最近任务 | 三 Service list |
| `get_task` | READ | 查单任务摘要（状态/错误/产出预览字段） | getById + 归属校验 |
| `list_chat_models` | READ | 可用 chat 模型 | AiModelConfigService |
| `draft_imggen` | WRITE* | **仅草案**，返回确认载荷，不落库 | 参数校验 + 组装 |
| `draft_video_extract` | WRITE* | 同上，视频链接提取 | 校验 URL |
| `draft_aigen` | WRITE* | 同上，视频生成 | 校验 prompt/模板 |

\* WRITE 在「草案」阶段不写库；确认后由 `confirm` 调真正 create。

### 4.3 Phase 2 工具集

| name | risk | 说明 |
|------|------|------|
| `create_imggen` | WRITE | 确认后创建（或 confirm 内映射 draft） |
| `create_aigen` | WRITE | 同上 |
| `create_video_extract` | WRITE | 同上 |
| `retry_task` / `cancel_task` | WRITE | 封装已有 pause/retry/cancel |
| `polish_prompt` | READ | 复用润色链路，只返回文本 |
| `attach_task_context` | READ | 把任务摘要注入会话「工作记忆」 |

### 4.4 永不开放（默认）

- 任意用户任务读写  
- 模型配置 CRUD（超管走现有管理页）  
- 交易/持仓/下单  
- 任意 HTTP 代理（SSRF）

---

## 5. 数据与协议设计

### 5.1 表（建议）

**A. 轻量方案（推荐 Phase 1）**  
不新增业务表：  
- 确认载荷用 **签名 token**（JWT 或 HMAC）存 Redis/内存，TTL 10～15 分钟。  
- 审计打 **结构化日志** 即可。

**B. 持久化方案（Phase 2+）**

```sql
-- 可选：工具调用审计
CREATE TABLE chat_tool_invocation (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  stream_id VARCHAR(64),
  tool_name VARCHAR(64) NOT NULL,
  risk VARCHAR(16) NOT NULL,
  args_json JSON,
  result_json JSON,
  status VARCHAR(32) NOT NULL,  -- proposed / confirmed / success / failed / expired
  error_message VARCHAR(512),
  created_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3),
  INDEX idx_user_conv_time (user_id, conversation_id, created_at)
);
```

会话表可扩展：

```sql
ALTER TABLE chat_conversation
  ADD COLUMN agent_mode TINYINT NOT NULL DEFAULT 0 COMMENT '0chat 1agent';
```

### 5.2 SSE 事件扩展（向后兼容）

| event | 含义 | data 要点 |
|-------|------|-----------|
| `meta` | 现有 | + `agentMode`, `streamId` |
| `delta` | 现有文本增量 | 不变 |
| `tool_confirm` | 需用户确认 | `confirmId`, `tool`, `summary`, `argsPreview` |
| `tool_result` | 工具结果 | `tool`, `ok`, `message`, `data`, `ui` |
| `done` | 现有 | + `cancelled` |
| `error` | 现有 | 不变 |

旧前端忽略未知 event 即可。

### 5.3 REST 补充

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/send` | 扩展 body：`agentMode?: boolean` |
| POST | `/api/chat/agent/confirm` | `{ confirmId }` → 执行草案 Tool，可 SSE 或 JSON |
| POST | `/api/chat/agent/reject` | 取消草案 |
| GET | `/api/chat/agent/tools` | 可选：返回工具清单（调试/文档） |

`confirm` 建议 **JSON 同步返回 ToolResult**，再由前端插入一条 assistant 消息或二次请求「请用一句话总结」；实现比双段 SSE 简单。

### 5.4 确认 Token 载荷示例

```json
{
  "confirmId": "c_xxx",
  "userId": 1,
  "conversationId": "2077...",
  "tool": "draft_imggen",
  "args": { "prompt": "...", "aspectRatio": "1:1" },
  "exp": 1710000000
}
```

服务端校验：签名、过期、userId 一致、conversation 归属。

---

## 6. 编排与提示词

### 6.1 System 拼装（Agent 模式）

```
[全局/会话 systemPrompt]
+ Agent 守则：
  - 只有白名单工具可调用
  - 不确定就先问用户，不要编造任务状态
  - 创建类操作只输出草案意图，由系统生成确认卡
+ 工具列表（name + description + 参数说明）
+ 输出协议：
  - 纯文本回复；或
  - 单独一行 JSON：{"tool":"list_my_tasks","args":{...}}
```

### 6.2 决策解析（Phase 1）

1. 流式收集完整 assistant 文本（或非流式决策一轮再流式总结——第一期可用 **两段式**：先非流式决策，再流式回复，实现简单）。  
2. 若匹配 tool JSON 且 name ∈ 白名单 → 走 Tool 路径。  
3. 否则当纯文本，直接呈现（可把已流式内容当最终回复）。

**推荐两段式 MVP**（更稳）：

```
Round A: 非流式 / 短 max_tokens → 决策 JSON 或 {"tool":null,"reply":"..."}
Round B: 若 null → 把 reply 当结果（或再流式润色）
         若 tool READ → execute → Round B 流式「根据工具结果回答」
         若 tool WRITE → 只发 tool_confirm，不自动 execute
```

### 6.3 与现有停止生成

- Agent 整轮共用 `ChatStreamRegistry`。  
- 确认执行若为同步短调用，可不占 stream；长任务只「创建」毫秒级。

---

## 7. 前端设计

### 7.1 页面改动点（`ai-chat`）

| 区域 | 改动 |
|------|------|
| 顶栏 | `Agent 模式` 开关；可选「工具」说明入口 |
| 消息区 | 识别 `tool_confirm` / `tool_result` 渲染卡片 |
| 确认卡 | 工具名、摘要、参数预览、确认/取消 |
| 结果卡 | 任务 ID、状态、跳转「查看任务」按钮 |
| 快捷 chip | 「查我的任务」「帮我出图」「提取视频」预填 |

### 7.2 卡片类型

- `confirm`：确认创建  
- `task_list`：表格/列表  
- `task_status`：单任务状态 + 进度文案  
- `nav`：路由跳转（`/image-generate?prompt=` 等）——可作为无 Tool 的降级  

### 7.3 与沉浸式布局

保持现有 immersive：外层不滚动，仅消息区滚动；卡片宽度不超过消息气泡区。

---

## 8. 安全与配额

| 项 | 策略 |
|----|------|
| 鉴权 | 所有 Agent API 登录态；Tool 内二次校验 userId |
| 写操作 | 必须 confirm；confirmId 一次性 |
| 参数 | 严格 schema；URL 域名可选白名单（视频链接） |
| 限流 | 用户维度：如 20 tool/min；创建任务沿用业务侧限制 |
| 审计 | 日志字段：userId, tool, risk, ok, latencyMs |
| Prompt 注入 | 工具结果标记为 untrusted content；限制回灌长度 |

---

## 9. 包与模块划分（建议）

```
com.dwcode.okxbot.chat
  ├── agent/
  │   ├── ChatAgentOrchestrator.java
  │   ├── AgentMode.java
  │   ├── ToolRegistry.java
  │   ├── AgentTool.java
  │   ├── ToolContext.java
  │   ├── ToolResult.java
  │   ├── ToolRisk.java
  │   ├── ToolExecutionService.java
  │   ├── ConfirmTokenService.java
  │   ├── IntentJsonParser.java          // Phase1 决策解析
  │   └── tools/
  │       ├── ListMyTasksTool.java
  │       ├── GetTaskTool.java
  │       ├── ListChatModelsTool.java
  │       ├── DraftImgGenTool.java
  │       ├── DraftVideoExtractTool.java
  │       └── DraftAigenTool.java
  ├── service/ChatService.java           // 委托 orchestrator 或内嵌分支
  └── controller/ChatController.java
```

配置：

```yaml
ai:
  agent:
    enabled: true
    default-mode: chat          # chat | agent
    confirm-ttl-seconds: 900
    max-tool-rounds: 2          # 防死循环
    decision-max-tokens: 512
```

---

## 10. 开发方案（PR 切片）

### PR-1：Agent 骨架 + 只读 Tool（约 2～3 天）

**后端**

- 新建 `chat.agent` 包：Registry、契约、ExecutionService  
- 实现 `list_my_tasks` / `get_task` / `list_chat_models`  
- `ChatService`：`agentMode` 时走两段式决策；READ 自动执行并二次总结  
- SSE 增加 `tool_result`（可选，先 REST 也行）

**前端**

- Agent 开关  
- `tool_result` 任务列表卡片  

**验收**：自然语言「我最近的文生图任务」返回真实数据。

### PR-2：确认协议 + 创建草案（约 2～3 天）

**后端**

- `draft_*` 三工具 + ConfirmTokenService  
- `POST /chat/agent/confirm|reject`  
- confirm 内调 ImgGen/Aigen/Video 的 create  

**前端**

- `tool_confirm` 卡片、确认/取消  
- 成功后展示任务 ID + 跳转链接  

**验收**：「帮我生成一张…」→ 确认 → 任务出现在文生图列表。

### PR-3：体验打磨（约 1～2 天）

- 快捷 chip、错误文案、loading  
- 工具调用日志规范化  
- 与 stop 联动（决策中可取消）  
- 简单单测：解析 JSON、确认过期、越权  

### PR-4：工作流增强（Phase 2，另起迭代）

- retry/cancel tool  
- 任务完成后对话内通知（复用已有任务 SSE 若有）  
- 上下文摘要、角色模板与 Agent 守则合并  
- （可选）LangChain4j 原生 tool calling 替换 JSON 决策  

---

## 11. 关键实现细节

### 11.1 任务摘要 DTO（统一）

避免三模块字段不一致，Agent 层定义：

```text
AgentTaskSummary
  type: video | imggen | aigen
  taskId: string
  status: string
  title/prompt: string
  createdAt, updatedAt
  errorMessage?
  resultHint?   // 如「已生成 1 张图」「摘要已就绪」
  openPath?     // 前端路由
```

各 Tool 内做 mapping，不污染原 Entity 对外契约。

### 11.2 决策失败兜底

- JSON 解析失败 → 当纯聊天回复原文  
- 未知 tool → 回复「暂不支持该操作」  
- 连续 tool 轮次 > `max-tool-rounds` → 停止并说明  

### 11.3 与会话参数

- Agent 决策轮：temperature 偏低（如 0.2）、短 max_tokens  
- 用户可见总结轮：使用会话 temperature/maxTokens  
- 自定义 systemPrompt 拼在 Agent 守则之前，但 **不可覆盖**「禁止伪造任务状态」等硬规则  

### 11.4 性能

- 只读 list 限制 size≤20  
- 工具结果截断后回灌模型（如 4k 字符）  
- 创建类只返回任务 id，不在对话里塞大 JSON  

---

## 12. 测试计划

| 类型 | 用例 |
|------|------|
| 单测 | IntentJsonParser；Confirm 过期/伪造；Tool 参数校验 |
| 集成 | 用户 A 无法 get 用户 B 的 taskId |
| 手工 | 开关 Agent；list；draft 确认/拒绝；闲聊回归；stop |
| 回归 | 无 agentMode 时行为与现网一致 |

---

## 13. 风险与对策

| 风险 | 对策 |
|------|------|
| 模型乱调工具 | 白名单 + JSON schema + max rounds |
| 幻觉任务状态 | 禁止模型编造；状态只来自 ToolResult |
| 确认重放 | confirmId 一次性 + TTL |
| 实现拖期 | 先 JSON 决策，不做原生 function call |
| 前端复杂度 | 卡片组件独立 `AgentToolCard.vue` |

---

## 14. 里程碑与人力粗估

| 阶段 | 产出 | 粗估 |
|------|------|------|
| PR-1 | 只读 Agent | 2～3 人日 |
| PR-2 | 确认 + 创建 | 2～3 人日 |
| PR-3 | 体验与 hardening | 1～2 人日 |
| **合计 MVP** | 可演示的站内 Agent | **约 1～1.5 周** |
| Phase 2 | 工作流/记忆/原生 tool call | 另计 1～2 周 |

---

## 15. 决策记录（建议立项时拍板）

| 编号 | 议题 | 建议默认 |
|------|------|----------|
| D1 | 决策方式 | Phase1：LLM JSON 意图；Phase2：原生 tool call |
| D2 | 默认模式 | chat；用户手动开 Agent |
| D3 | 写工具 | 必须确认卡 |
| D4 | 确认存储 | HMAC token + 内存/Redis，不做表 |
| D5 | 外部搜索 | 不做；预留 Tool 接口即可 |
| D6 | 交易 Tool | 永不默认开启 |

---

## 16. 总结

把 AI 聊天打造成可用 Agent，**最短路径**是：

> **在现有 Chat 上加一层 Orchestrator + 白名单 Tool，复用三工具 Service；读自动、写确认；前端卡片化。**

不重写流水线、不先联网、不先上复杂多步规划。按 **PR-1 → PR-2 → PR-3** 推进，约一周级可交付「能查任务、能确认后创建」的可用站内 Agent。

---

**文档路径**：`okx-bot/doc/AI助手_Agent架构设计与开发方案.md`  
**下一步**：确认 D1～D6 后按 PR-1 开工实现。
