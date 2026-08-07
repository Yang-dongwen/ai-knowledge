# API 校验与安全加固 — 后续事项

> 背景：`api-validation-smoke` 静态冒烟审计 → `api-validation-remediate` 按 P0/P1/P2 落地修复。  
> 工作流定义：`.grok/workflows/api-validation-smoke.rhai`、`.grok/workflows/api-validation-remediate.rhai`  
> 本文记录**已落地内容摘要**与**仍建议跟进的事项**，便于排期与回归。

---

## 1. 已落地（本轮已合入代码）

| 优先级 | 包 | 要点 |
|--------|-----|------|
| P0 | kb-xss | 上传拦截 html/htm/svg/xhtml 等扩展名与危险 MIME；私有/公开流 `isSafeInline` + 非安全类型 `attachment`/`octet-stream` + nosniff |
| P1 | aigen-member-size | `regenerateShot` / `uploadShotImage` 调用 `requireActiveMember`；上传先 `getSize()>15MB` 再 `getBytes()` |
| P1 | video-models-test | `POST /api/v1/video/models/test`：`SUPER_ADMIN`（SecurityConfig + service 双闸） |
| P1 | send-code-quota | 验证码发送 IP / 全局内存滑动窗口（默认 IP 20/h、全局 200/h）→ 429 |
| P2 | auth-hardening | 登录密码 max 72；邮箱 max 128；昵称 max 64 + clamp；`auth.trust-x-forwarded-for` 默认 false |
| P2 | chat-size | `ChatRequest` / `EditResendRequest` message `@Size(max=32000)` |
| P2 | video-dto | process/retry url、options `@Size`；未知 `understandingMode` → 400 |
| P2 | article-valid | create `@Valid` + 字段上限；language 存 trim 后值 |
| P2 | imggen-size | prompt max 4000 / negativePrompt max 1024（DTO + service） |
| P2 | kb-batch-size | batch-move / tree reorder 列表 `@Size(max=200)` |
| P2 | exception-400 | `HttpMessageNotReadableException`、`DataIntegrityViolationException` → 稳定 HTTP 400 |

相关配置（节选）：

- `auth.trust-x-forwarded-for`（默认 `false`）
- `auth.code.send-quota-enabled` / `max-sends-per-ip-per-window` / `ip-window-seconds` / `max-sends-global-per-window` / `global-window-seconds`

---

## 2. 残留风险（本轮未完全关闭）

1. **KB 内容魔数**：扩展名/MIME 已拦，但「假 `.jpg` + 实际 HTML」仍可能入库；流式策略与 nosniff 可缓解执行面，**未做上传 magic-byte 拒绝**。
2. **send-code 配额按 JVM 内存**：多实例 / 重启会稀释限流；并发 assert-then-send 可能略超标。
3. **`auth.trust-x-forwarded-for` 与 `pay.trust-x-forwarded-for` 独立**：反代场景下登录/验证码 IP 可能全部落到 `remoteAddr`（代理 IP），需运维显式打开并保证边缘剥离伪造 XFF。
4. **Chat regenerate**：`send` / `edit-resend` 有 `@Valid`，**`regenerate` 路径若未挂 `@Valid` 则 message 上限不生效**。
5. **部分 auth/OAuth 字符串**（ticket、部分 code 字段）仍可能缺 `@Size`。
6. **自动化回归偏少**：多数门闸依赖人工/工作流静态复核，缺少接口级 400/403/429 用例。

---

## 3. 建议下一步（排期清单）

### P0 / 安全加固（优先）

| ID | 事项 | 说明 | 建议验收 |
|----|------|------|----------|
| F-01 | Chat regenerate 补 `@Valid` | `ChatController` 上 regenerate 绑定体与 send 对齐，使 `@Size(max=32000)` 生效 | 超长 body → 400 |
| F-02 | KB 上传 magic-byte 嗅探 | 对「声明为图片」的内容探测 HTML/SVG 特征，拒绝入库 | `.html` 改后缀伪装 → 400 |
| F-03 | 生产反代 IP 对齐 | 文档化并配置 `auth.trust-x-forwarded-for`；与 nginx `X-Real-IP` / 剥离客户端 XFF 策略一致 | 登录限流按真实客户端 IP 分桶 |

### P1 / 可靠性与多实例

| ID | 事项 | 说明 | 建议验收 |
|----|------|------|----------|
| F-04 | send-code 共享配额 | Redis（或同等）承载 IP/全局计数，替代纯内存 | 多实例下 IP 喷邮仍 429 |
| F-05 | aigen 上传防御加深 | 在 15MB 早拒基础上可选 magic-byte 校验图片头；注意 `getSize()==-1` 回退 | 非图片 MIME 伪装 → 400 |
| F-06 | video model-test 文档 | 使用说明 / 接口文档标明仅 SUPER_ADMIN | 普通用户 403 |

### P2 / 校验补齐与测试

| ID | 事项 | 说明 | 建议验收 |
|----|------|------|----------|
| F-07 | 回归测试包 | 至少覆盖：KB 危险后缀 400；public/private 流不 inline 主动内容；send-code IP 429；video models/test 403；坏 JSON 400；超长 DTO 400 | CI 稳定绿 |
| F-08 | 剩余字符串 `@Size` | OAuth ticket、wx code、article `llmProvider`/`llmModel` 等与列宽对齐 | 超长 → 400 而非 500 |
| F-09 | `ConstraintViolationException` 消息 | 全局异常对路径参数校验避免原样回传内部细节 | 响应体稳定、无栈信息 |
| F-10 | article rewriteVariants 白名单 | 除长度外限制为已知 variant id | 未知 id → 400 |

### 运维清单（上线时）

- [ ] 确认 `application.yml` / 环境变量中 `auth.trust-x-forwarded-for` 与真实部署拓扑匹配  
- [ ] 确认 send-code 配额阈值适合生产（当前默认偏开发友好）  
- [ ] 部署后冒烟：注册发码、登录、KB 上传图片、会员 aigen 写路径、非超管 video models/test  

---

## 4. 相关入口

| 类型 | 路径 |
|------|------|
| 静态审计工作流 | `.grok/workflows/api-validation-smoke.rhai` |
| 修复工作流 | `.grok/workflows/api-validation-remediate.rhai` |
| KB MIME / 流式 | `kb/service/KbMediaTypes.java`、`KbFileService.java`、`KbShareService.java` |
| 验证码配额 | `auth/service/EmailCodeService.java`、`auth/config/AuthProperties.java` |
| 全局异常 | `common/exception/GlobalExceptionHandler.java` |

---

## 5. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-08-07 | 首版：P0/P1/P2 修复落地后整理残留风险与后续清单 |
