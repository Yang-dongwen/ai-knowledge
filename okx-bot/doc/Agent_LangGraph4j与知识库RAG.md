# Agent LangGraph4j 与知识库 RAG（对照代码）

**包：** `com.dwcode.okxbot.chat.agent.graph`、`com.dwcode.okxbot.rag`  
**配置：** `config/ai.yml`（embedding / vector-store）、`config/platform.yml`（`kb.rag` / `kb.search.mode`）  
**密钥：** `application-local.yml` 或环境变量 `GOOGLE_AI_STUDIO_API_KEY`、`QDRANT_HOST`、`QDRANT_API_KEY`

对照代码，不是把 LangChain AgentExecutor 接进来。

---

## 1. 干什么

- 开 **Agent 模式** 时，一轮对话走 LangGraph4j 图：检索知识库 → 决策 JSON → 工具。
- **写工具**（文生图 / 成片 / 视频提取 / 存笔记）仍只出确认卡，`POST /chat/agent/confirm` 后才真正创建。
- 知识库列表搜索与 `search_notes` 走 **混合检索**（Gemini 向量 + MySQL LIKE）。密钥没配齐时自动降级 LIKE，启动不挂。

---

## 2. 图

```
START → retrieve → decide
                    ├ read  → execute → summarize → END
                    ├ write → execute（draft_* 确认卡）→ END
                    ├ reply / fallback / unknown → END
```

类：`AgentGraphFactory` 编译单例 `CompiledGraph`；`ChatAgentOrchestrator` 调 `invoke`。  
节点是 Spring Bean，cancel / SSE phase 经 `AgentTurnScope` ThreadLocal，不把回调塞进单例字段。

LangChain4j 只用于 Chat 出站（`LlmChatGateway`）和（可选）以后换 embedding 实现。图本身只用 `langgraph4j-core`。

---

## 3. RAG 端口

| Port | 默认实现 | Noop |
|------|----------|------|
| `EmbeddingPort` | `GoogleAiStudioEmbeddingAdapter`（REST `embedContent`） | `NoopEmbeddingAdapter` |
| `VectorStorePort` | `QdrantVectorStoreAdapter`（官方 gRPC，强制 `user_id`） | `NoopVectorStoreAdapter` |
| `TextExtractPort` | PDFBox / POI / 纯文本 | 不支持则空串 |

换模型或换库：新 Adapter + 改 `RagBeanConfig`，不要改 `HybridSearchService` / Agent 图。

Qdrant collection：`kb_chunks`，Cosine，维度 = `ai.embedding.output-dimensionality`。payload：`user_id`、`source_type`、`note_id`、`file_id`、`text`、`title`。

---

## 4. 索引

表 `kb_index_job`（Flyway V10）。笔记/附件增改删入队，`KbIndexWorker` 每 20s 消费。  
`POST /api/v1/kb/rag/reindex` 把当前用户笔记和附件全部再入队。知识库侧栏有「重建向量索引」。

公开分享接口不走向量库。

---

## 5. 降级

`kb.rag.enabled=true` 但 embedding/qdrant 未配：`RagAvailability.live()=false`，搜索 LIKE，Agent 图 retrieve 得到空列表，其余工具照常。
