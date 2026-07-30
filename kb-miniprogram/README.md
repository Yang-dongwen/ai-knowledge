# 知识库微信小程序（Phase 3）

移动端轻量入口，与 PC / H5 共用 `okx-bot` 的 JWT 与 `/api/v1/kb/*`，**不**包含 AI 工具。

## 功能对照（相对 PC）

| 能力 | 小程序 | 说明 |
|------|--------|------|
| 登录 / 会话 | ✅ | 邮箱 + 密码 |
| 笔记列表 / 搜索 / 分页 / 下拉刷新 | ✅ | |
| 分类筛选 | ✅ | 横向芯片 |
| 回收站 / 清空 / 恢复 / 永久删除 | ✅ | |
| 新建格式锁定（html / md） | ✅ | |
| 标题第一行 + 分隔 | ✅ | |
| 富文本 | △ | 文本域编辑 HTML；复杂排版请用 PC |
| Markdown 编辑 / 详情渲染 | ✅ | 轻量 `rich-text` 渲染（含图片） |
| 插图 / 附件 | ✅ | 插图写入正文；附件列表与上传 |
| 标签选择 | ✅ | 多选已有标签；CRUD 请用 PC |
| 附件预览 | △ | 图片预览；其它 `openDocument` |
| Word 高保真 / 完整富文本 / 图片缩放 | — | 不兼容 |
| 分类 / 标签管理 CRUD | — | 请用 PC |
| 公开分享 | ✅ | 开启/关闭/重置/复制；需配置分享域名 |
| AI 工具（Phase 2） | — | 暂缓 |

## 先别装微信工具？用浏览器

日常联调优先用 **`kb-mobile/`**（Vite H5）：

```bash
cd kb-mobile
npm install
npm run dev
# → http://127.0.0.1:5174
```

## 本地运行（微信开发者工具）

1. 启动后端 `okx-bot`（默认 `http://127.0.0.1:8080`），并已执行 kb 相关 SQL。
2. 安装 [微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html)。
3. 导入本目录 `kb-miniprogram`。
4. **详情 → 本地设置**：勾选「不校验合法域名…」。
5. 真机预览：在「我的」填写：
   - **服务端地址**：`http://局域网IP:8080`
   - **分享链接域名**：H5 或 PC 站点，如 `http://局域网IP:5174`（用于拼接 `/s/{token}`）

默认 `appid` 为 `touristappid`，正式发布请换成自己的 AppID，并配置 request 合法域名（HTTPS）。

## 配置

| 项 | 位置 |
|----|------|
| 默认 API | `utils/config.js` → `DEFAULT_BASE_URL` |
| 运行时 API | 「我的」→ 服务端地址 → `kb_base_url` |
| 分享页域名 | 「我的」→ 分享链接域名 → `kb_share_web_origin` |

## 目录

```
kb-miniprogram/
├── app.js / app.json / app.wxss
├── utils/          # config、auth、request、markdown、title
├── pages/
│   ├── login/
│   ├── notes/      # 列表 + 回收站
│   ├── edit/       # 快记 / 编辑
│   ├── detail/     # 详情 + 分享 + 附件
│   └── me/
└── assets/
```

## 说明

- 详情 Markdown 为内置轻量渲染；复杂表格/嵌套以 PC 为准。
- 注册/找回密码请在 PC 完成。
- 公开阅读页不在小程序内打开，复制链接后在浏览器访问 H5/PC。
