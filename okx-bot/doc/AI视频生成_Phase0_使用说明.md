# AI 视频生成 Phase 0 — 使用说明

**状态**：骨架已通（mock 流水线，无真实成片）  
**日期**：2026-07-13  

---

## 已交付内容

| 项 | 说明 |
|----|------|
| 表 | `aigen_task`（脚本：`doc/sql/aigen_task.sql`） |
| 后端包 | `com.dwcode.okxbot.aigen` |
| API | `/api/v1/aigen/*` |
| 假流水线 | PENDING → PLANNING → ASSET → RENDERING → SUCCESS |
| SSE | `/api/v1/aigen/events` |
| 前端 | `/video-generate` 页面壳 |

---

## 启动前

1. 执行建表（若尚未执行）：

```bash
mysql -uroot -p okx_bot < okx-bot/doc/sql/aigen_task.sql
```

2. 确认 `application.yml` 中：

```yaml
aigen:
  mock-pipeline: true   # Phase 0 必须为 true
  mock-step-delay-ms: 1500
  work-dir: ./data/aigen
```

3. 启动后端 `okx-bot`、前端 `okx-trading-web`。

---

## 验证路径

1. 登录后打开菜单 **工具使用 → AI 视频生成**（`/video-generate`）。  
2. 输入提示词，选模板/画幅，点 **生成视频**。  
3. 左侧任务列表应出现任务，进度经 SSE 推进。  
4. 约数秒后状态 **成功**（文案会提示 Phase 0 无真实 MP4）。  
5. 可点 **查看 mock 分镜** 看生成的 `storyboard.json` 内容。  
6. 支持取消 / 失败后重试 / 删除。

### 接口速查

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/aigen/tasks` | 创建 |
| GET | `/api/v1/aigen/tasks` | 列表 |
| GET | `/api/v1/aigen/tasks/{id}` | 详情 |
| GET | `/api/v1/aigen/tasks/{id}/storyboard` | 分镜 JSON |
| POST | `/api/v1/aigen/tasks/{id}/cancel` | 取消 |
| POST | `/api/v1/aigen/tasks/{id}/retry` | 重试 |
| DELETE | `/api/v1/aigen/tasks/{id}` | 删除 |
| GET | `/api/v1/aigen/templates` | 模板列表 |
| GET | `/api/v1/aigen/events` | SSE |

---

## Phase 0 明确不做

- 真实 LLM 分镜（LangChain4j）  
- TTS / 配图  
- Remotion 出 MP4  
- 分镜编辑器  

下一阶段（Phase 1）在 mock 关闭后接入真实步骤；配置将 `aigen.mock-pipeline: false`。

---

## 相关文档

- 极简说明：`AI视频生成_极简说明.md`  
- 完整架构：`AI视频生成_架构设计方案.md`  
