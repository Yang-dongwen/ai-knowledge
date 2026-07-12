# 视频任务状态推送 — 落地方案（SSE + 智能轮询兜底）

> 替代固定 3s 轮询；主通道 SSE，断线/不支持时智能轮询。  
> 日期：2026-07-13

---

## 1. 目标

| 目标 | 说明 |
|------|------|
| 实时性 | 步骤切换（下载/转录/总结/暂停/成功/失败）接近实时 |
| 减负 | 无活跃任务时零轮询；有任务时不空转打列表 |
| 兼容 | 开发代理 / 断线时自动降级轮询 |
| 安全 | 仅推当前用户自己的任务；JWT 鉴权 |

---

## 2. 架构

```
Pipeline / Service 写库成功
        │
        ▼
VideoTaskEventPublisher  (userId → SseEmitter 列表)
        │  text/event-stream
        ▼
前端 fetch 读流解析 SSE
        │
        ├─ 连接成功：停轮询，事件合并列表/详情
        └─ 断线：智能轮询 + 指数退避重连 SSE
```

单机：内存 fan-out。多实例后续可换 Redis Pub/Sub，接口不变。

---

## 3. 接口

### 3.1 用户级事件流

```http
GET /api/v1/video/events
Authorization: Bearer <jwt>
Accept: text/event-stream
```

- 超时：约 60 分钟，超时后客户端重连  
- 每用户最多 3 条连接，超出踢掉最旧  
- 心跳：约每 20s `event: ping`  

### 3.2 事件类型

| type | 时机 |
|------|------|
| `connected` | 连接建立 |
| `ping` | 心跳 |
| `task.created` | 提交任务 |
| `task.status` | 状态/步骤/耗时/标题等变更 |
| `task.deleted` | 删除任务 |

### 3.3 data 字段（轻量，不含全文转录/summary）

`taskId, status, url, title, platform, llmProvider, llmModel, currentStep, errorMessage, durationSeconds, videoAvailable, createdAt, startedAt, finishedAt, downloadDurationMs, transcribeDurationMs, summarizeDurationMs, totalDurationMs`

终态 `SUCCESS`：前端再 `GET /tasks/{id}` 拉完整 result。

---

## 4. 后端发布点

| 位置 | 事件 |
|------|------|
| `VideoProcessService.submit` | task.created |
| `pauseTask` / `retryTask` | task.status |
| `deleteTask` | task.deleted |
| `VideoProcessingPipeline.updateStatus` | task.status |
| 下载完成写 title/耗时 | task.status |
| 转录/总结写库 | task.status |
| SUCCESS / FAILED / markPaused | task.status |

---

## 5. 前端

1. 首屏：`listTasks` 一次  
2. `connectVideoTaskEvents`（fetch + Authorization，非原生 EventSource）  
3. 事件合并 `tasks[]`；选中任务同步 `detail`；SUCCESS 全量刷新详情  
4. SSE 已连接 → 不轮询  
5. SSE 断开 → 智能轮询：  
   - 仅 `needsPoll` 时请求  
   - `document.hidden` 时拉长间隔  
   - 间隔约 2–5s 自适应  
6. 卸载页面关闭 SSE  

---

## 6. 代理注意

- Vite dev：`/api` 代理目标 8080，超时放宽  
- 生产 Nginx：`proxy_buffering off;`、`proxy_read_timeout` 足够大  

---

## 7. 分期

| 阶段 | 内容 | 状态 |
|------|------|------|
| 文档 | 本方案 | 本次 |
| 实现 | SSE + Publisher + 前端 + 智能轮询兜底 | 本次一并完成 |
| 后续 | Redis 多实例、下载百分比 | 可选 |

---

## 8. 相关代码

- `video/event/VideoTaskEventPublisher.java`  
- `GET .../VideoProcessController` → `/events`  
- `okx-trading-web/src/api/video.events.ts`  
- `views/video-extract/index.vue`  
