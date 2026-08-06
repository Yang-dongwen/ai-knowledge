# 登录 / 注册架构与安全设计

**技术选型**：Spring Security 6 + JWT（无状态）+ BCrypt + 邮箱验证码  
**前端**：Vue 3 + Pinia + Router 守卫  
**日期**：2026-07-12  

---

## 1. 目标

| 能力 | 说明 |
|------|------|
| 注册 | 邮箱即用户名，邮箱验证码校验后建号 |
| 登录 | 邮箱 + 密码 → JWT |
| 找回密码 | 邮箱验证码 + 重置密码 |
| 接口保护 | 除认证相关接口外，业务 API 需登录 |

---

## 2. 架构总览

```
┌─────────────┐     HTTPS      ┌──────────────────────────────┐
│  Vue 前端    │ ────────────► │  Spring Security Filter Chain │
│  Token 存    │  Authorization│  JwtAuthFilter                │
│  localStorage│  Bearer JWT   │  → Controller → Service       │
└─────────────┘               └───────────┬──────────────────┘
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
              sys_user              email_code              业务表
              (邮箱/密码哈希)       (验证码/用途/过期)      (如 video_task.user_id)
```

### 为何选 Spring Security + JWT

| 方案 | 评价 |
|------|------|
| **Spring Security + JWT** | 业界主流，与 Spring Boot 3 原生集成，过滤器/密码编码器成熟 |
| Sa-Token | 国内流行、上手快，但非 Spring 官方生态 |
| Session + Cookie | 需会话粘滞/Redis，前后端分离下 JWT 更轻 |

本项目为前后端分离 SPA → **无状态 JWT**。

---

## 3. 安全要点（实现必须遵守）

### 3.1 密码

- 使用 **BCrypt**（`BCryptPasswordEncoder`，强度 10+）  
- **禁止**明文、MD5、可逆加密存库  
- 前端可不加密传输（依赖 HTTPS）；内网开发可用 HTTP，生产必须 HTTPS  

### 3.2 凭证与 Token

| 项 | 策略 |
|----|------|
| Access Token | JWT，HS256，有效期建议 **2h**（可配置） |
| Claims | `sub`=userId，`email`，`iat`/`exp` |
| 存储 | 前端 `localStorage`（或后续改为 httpOnly Cookie） |
| 吊销 | 短期 Access；登出前端删除即可（可扩展黑名单） |

### 3.3 邮箱验证码

| 项 | 策略 |
|----|------|
| 长度 | 6 位数字 |
| 有效期 | 10 分钟 |
| 用途隔离 | `REGISTER` / `RESET_PASSWORD` 分类型 |
| 单次使用 | 校验成功立即标记 used |
| 发送限流 | 同邮箱 60s 内不可重复发送 |
| 尝试限流 | 同邮箱 15 分钟内失败 ≤ 10 次（防爆破） |

### 3.4 接口与账号

- 登录失败统一文案「邮箱或密码错误」（防枚举）  
- 注册：邮箱格式校验 + 密码强度（≥8，字母+数字）  
- 账号状态：`enabled`；未验证邮箱不可登录（注册时验证通过即 verified）  
- 业务资源绑定 `user_id`，查询按当前用户隔离（视频任务）  

### 3.5 传输与配置

- 生产环境强制 HTTPS  
- JWT 密钥 `auth.jwt.secret` **足够长随机串**，不入库不进前端  
- 邮件提供方 `auth.mail.provider`：`console`（开发日志）/ `agentmail`（AgentMail HTTP API，推荐）/ `smtp`（spring.mail）  
- 密钥放环境变量：`AGENTMAIL_API_KEY`、`AGENTMAIL_INBOX_ID`；开发模式可 **console 输出验证码**  

---

## 4. 数据模型

### 4.1 `sys_user`

| 字段 | 说明 |
|------|------|
| id | 雪花主键 |
| email | 唯一，即登录名 |
| password_hash | BCrypt |
| nickname | 可选，默认邮箱前缀 |
| role | `USER` 普通 / `MEMBER` 会员 / `SUPER_ADMIN` 超管；新注册默认 USER |
| status | 1 正常 0 禁用 |
| email_verified | 1 已验证 |
| last_login_at | 最后登录 |
| created_at / updated_at | |

### 4.1.1 角色权限

| 角色 | 说明 | 前端 | 后端 |
|------|------|------|------|
| USER | 普通用户（默认注册） | 工具使用；无交易菜单、无模型管理 | 视频等业务 API |
| MEMBER | 会员（充值后，后期扩展） | 与普通用户相同（暂不区分页面） | 同 USER |
| SUPER_ADMIN | 超级管理员 | 交易管理菜单 + 模型管理按钮 | 交易 API + `/model-configs/**` |

默认超管由启动时 `SuperAdminInitializer` 种子（`auth.admin`），见 `application.yml`。

### 4.2 `email_code`

| 字段 | 说明 |
|------|------|
| id | 主键 |
| email | 邮箱 |
| code | 验证码 |
| purpose | REGISTER / RESET_PASSWORD |
| expires_at | 过期时间 |
| used | 0/1 |
| created_at | |

### 4.3 业务隔离

- `video_task.user_id`：任务归属用户  
- 列表/详情/删除/重试等均校验归属  

---

## 5. API 设计

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register/send-code` | 否 | 注册验证码 |
| POST | `/api/auth/register` | 否 | 注册 |
| POST | `/api/auth/login` | 否 | 登录，返回 token + 用户信息 |
| POST | `/api/auth/password/send-code` | 否 | 找回密码验证码 |
| POST | `/api/auth/password/reset` | 否 | 重置密码 |
| GET | `/api/auth/me` | 是 | 当前用户资料（含 role/roleLabel） |
| POST | `/api/auth/logout` | 是 | 登出（前端清 token） |
| GET | `/api/admin/users` | 超管 | 分页用户列表（keyword/role/status） |
| PUT | `/api/admin/users/{id}/status` | 超管 | 启用/禁用账号 body:`{status:0|1}` |

其余 `/api/**` 需 JWT。其中：

- 交易相关：`/api/dashboard|okx|strategies|positions|trades|orders|strategy-run-logs|system|chat|backtests/**` → **仅 SUPER_ADMIN**
- 模型管理：`/api/v1/video/model-configs/**` → **仅 SUPER_ADMIN**
- 视频任务、`/api/v1/video/models` 等 → 登录用户即可

---

## 6. 前端流程

1. 未登录访问业务页 → 跳转 `/login`  
2. 登录成功存 `token`，请求头 `Authorization: Bearer <token>`  
3. 401 → 清 token 跳转登录  
4. 页面：登录、注册、忘记密码  

---

## 7. 包结构（后端）

```
com.dwcode.okxbot.auth
  config/   SecurityConfig, JwtProperties
  security/ JwtService, JwtAuthFilter, UserDetailsServiceImpl
  entity/   SysUserEntity, EmailCodeEntity
  mapper/
  service/  AuthService, EmailCodeService, MailService
  controller/ AuthController
  dto/
```

---

## 8. 实施顺序

1. 依赖与表结构  
2. JWT + Security 配置  
3. 注册/登录/重置密码  
4. 业务接口用户隔离  
5. 前端登录页与守卫  

---

## 9. PC 端 Google / GitHub OAuth（JustAuth）

**选型**：JustAuth 换码 + 本系统 JWT；**不**使用 Spring OAuth2 Client Session。  
**交付**：回调签发 **one-time ticket**（HMAC，短 TTL），前端 `POST /api/auth/oauth/exchange` 换正式 JWT。

### 9.1 流程

```
登录页 → GET /api/auth/oauth/{google|github}/authorize?redirect=/path
      → 302 Google/GitHub（或 mock callback）
      → GET /api/auth/oauth/{provider}/callback?code&state
      → 302 {frontend}/oauth/callback?ticket=…&redirect=…
      → POST /api/auth/oauth/exchange {ticket} → LoginResponse
```

### 9.2 数据

- 表 `user_oauth_binding`：`UNIQUE(provider, provider_user_id)`  
- `sys_user.password_hash` 允许 NULL（纯三方用户）  
- 同邮箱自动绑定已有账号；无 verified 邮箱则拒绝（GitHub 需公开邮箱）

### 9.3 API（均无需 JWT）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/auth/oauth/providers` | 已启用列表 + mock 标记 |
| GET | `/api/auth/oauth/{provider}/authorize` | 浏览器跳转 |
| GET | `/api/auth/oauth/{provider}/callback` | 平台回调 → 前端 |
| POST | `/api/auth/oauth/exchange` | ticket → JWT |

### 9.4 配置

见 `auth.oauth.*` / 环境变量 `AUTH_OAUTH_*`。本地 `application-local` 默认 `mock=true`。  
生产：`AUTH_OAUTH_MOCK=false`，配置 Google/GitHub client，  
`callback` 注册 `{callback-base}/api/auth/oauth/{provider}/callback`。

### 9.5 包结构

```
com.dwcode.okxbot.auth.oauth
  OAuthController / OAuthService / JustAuthClient / OAuthTokenStore
  entity.UserOAuthBindingEntity / mapper
```

---

**结论**：采用 **Spring Security + JWT + BCrypt + 邮箱验证码**，并扩展 **JustAuth 三方登录（PC）**，兼顾主流与前后端分离场景下的安全性。  
下方实现按本设计落地。
