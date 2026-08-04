# 知识库微信小程序（Phase M2）

移动端知识库入口，与 PC / H5 共用 `okx-bot` 的 JWT 与 `/api/v1/kb/*`，**不**包含 AI 工具。

当前版本 **0.7.1**（输入框真机字色修复 + Markdown 表格/排版）。

## 功能对照（相对 PC）

| 能力 | 小程序 | 说明 |
|------|--------|------|
| 邮箱登录 | ✅ | |
| **微信一键登录 / 绑定 / 解绑** | ✅ | 后端 `auth.wechat.mini`；开发可模拟 openid |
| 笔记列表 / 搜索 / 分页 / 筛选 | ✅ | 分类 / 未分类 / 标签 / 回收站 |
| 左滑置顶 / 删除 | ✅ | 亦可长按 |
| 文件夹 CRUD / 移动 / 同级排序 | ✅ | 上移下移 |
| 标签 CRUD | ✅ | |
| 快记 + 轻量格式工具栏 | ✅ | 粗体/列表/引用 |
| 插图 / 附件 / 分享 | ✅ | 站内分享阅读 + 转发好友 |
| 完整 WangEditor / 拖拽树 / AI | — | 请用 PC |

## 微信登录说明

1. **本地 mock（默认）**  
   - 后端 `auth.wechat.mini.enabled=false`  
   - 小程序「我的 → 模拟微信 openid」开启（develop 默认开）  
   - 使用稳定本机 mock openid，首次需绑定邮箱密码，之后一键登录  

2. **正式微信**  
   - 后端配置：
     ```yaml
     auth:
       wechat:
         mini:
           enabled: true
           app-id: ${WX_MINI_APP_ID}
           app-secret: ${WX_MINI_APP_SECRET}
     ```
   - 小程序关闭「模拟微信」，并配置真实 AppID  
   - 数据库迁移：`V2__sys_user_wx_mini_openid.sql`（Flyway 自动）

## 本地运行

1. 启动 `okx-bot`（:8080），确认 Flyway 已执行 V2。  
2. 微信开发者工具导入本目录；勾选「不校验合法域名」。  
3. 「我的」填服务端地址（真机用局域网 IP）。  
4. **release** 包不展示调试配置区。

## 页面

```
pages/notes | edit | detail | folders | tags | share | me | login
```
