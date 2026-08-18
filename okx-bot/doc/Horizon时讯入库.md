# Horizon × 工具台（本机 / 线上独立）

Horizon 只产 Markdown。工具台只读自己这边的数据。  
**本机和线上两套 Horizon、两套数据，互不推送。Halo 发博先不做。**

---

## 原则

| | 本机 | 线上 |
|--|------|------|
| Horizon 进程 | 你电脑上的 `D:\gitprojects\Horizon` | EC2 容器，**源码来自本机发版打包** |
| 工具台谁产稿 | 本机 okx-bot 调 `uv run horizon` | **Horizon 容器自己循环跑**，okx-bot 不调本机、不收本机 POST |
| 工具台读什么 | 本机 MySQL `horizon_digest`，没有再读本机 summaries | **只读线上 RDS `horizon_digest`** |
| 文件落盘 | `Horizon/data/summaries/` | `/data/auto-exchange/horizon/data/summaries/` |
| 互相同步 | 无 | 无 |

公共表 `horizon_digest`（无 `user_id`）。本机库和 RDS 不是同一个库。

---

## 本机（工具台对接）

`application-local.yml`：`horizon.refresh-enabled: true`。

1. 启动 okx-bot：当天没稿 → 后台 `uv run horizon --hours 24`
2. 写出本机 md，写入**本机** `horizon_digest`
3. `http://localhost:3000/news` 读本机库/文件
4. 每小时 `:05` 再跑 `--hours 24` 覆盖当天
5. 超管「重新生成」再跑一轮

不向 `dwcode.cloud` 推送。本机开不开着，不影响线上。

---

## 线上（工具台对接）

线上 Horizon **源码跟本机走**：`deploy/win/deploy.ps1` 把 `D:\gitprojects\Horizon` 打进包（不含 `.venv` / `.env` / summaries）。改完 Horizon 源码再发版即可。

运行时数据仍在服务器：`/data/auto-exchange/horizon/data/summaries`（和本机 summaries 分开）。

发版时 `server-deploy.sh`：

1. `bootstrap-horizon.sh`  
   - 用打上来的 `auto-exchange/horizon-src` 构建镜像  
   - 同步本机 `data/config.json` 到服务器数据目录  
   - `.env` 仍用服务器 `app.env` 的 NVIDIA Key（不传本机 `.env`）
2. `docker compose --profile horizon` 起 `horizon` 容器  
   - `horizon-loop.sh`：始终 `--hours 24`（整点 3h 窗口太容易只抓到 1 条）
   - 稿写在宿主机 summaries
3. `okx-bot` 只读挂载 `/app/data/horizon/summaries`  
   - 容器里**没有** Horizon 源码，不跑 `uv`  
   - 每 10 分钟把当天 md **导入 RDS** `horizon_digest`  
   - `https://dwcode.cloud/news` 读这张表

线上入库后会**自动发/更新 Halo 文章**（`/archives/horizon-日期`），并把导航「资讯」指到当天这篇。空稿（没到阈值）不发。

看容器：

```bash
docker logs -f horizon
ls /data/auto-exchange/horizon/data/summaries
```

2G 小机同时开 Halo + Horizon 可能紧。Horizon 限 400M。不够就先停 blog profile 或升配。

---

## 空稿怎么调（两道闸）

页面出现「已分析 1 条…没到阈值」**不是今天没新闻**，是过滤太严。改 `Horizon/data/config.json`（发版会同步到线上）：

| 闸 | 字段 | 空稿常见值 | 今日资讯建议 |
|----|------|-----------|-------------|
| 采集窗 | `--hours` / `collection.time_window_hours` | 3 | **24**（线上 loop 已写死） |
| HN 热度 | `sources.hackernews.min_score` | 150 | **50**（再低到 30 会更吵） |
| HN 条数 | `sources.hackernews.fetch_top_stories` | 10 | **20** |
| AI 重要性 | `processing.profile_settings.tech-news.threshold` | 7.0 | **5.5**（0–10，`>=` 才进稿；4.0 更满，7.0 容易空） |
| 日报上限 | `digest.max_items` | — | **8** |

`threshold` 对照画像打分：9–10 突破、7–8 高价值、5–6 值得一看、3–4 例行。产品页要「每天有东西」用 5.0–5.5，不要用 7。

少开源：2G + NVIDIA 限流，HN + Simon Willison + r/MachineLearning 够用。再开 RSS/Reddit 会多烧 token。

---

## 页面与接口（两边一样）

`/news`：当日正文、近日切换、超管「重新生成」（本机=跑 Horizon；线上=把已有 summaries 再入库）。

| 方法 | 路径 | 作用 |
|------|------|------|
| GET | `/api/v1/horizon/latest` | 当天稿（先库后文件） |
| GET | `/api/v1/horizon/recent` | 近日列表 |
| GET | `/api/v1/horizon/feed.xml` | **公开 RSS**，Halo Cosolar「资讯」订阅（无需登录） |
| POST | `/api/v1/horizon/refresh` | 超管：本机跑 CLI；线上仅导入 |

发 Halo 的按钮可以先不用。

---

## 代码入口

- 本机拉 CLI：`HorizonCliRunner`（找不到仓库就跳过）
- 入库：`HorizonIngestService.save` → `horizon_digest`
- 定时：`HorizonRefreshJob`（启动补当天 / 整点刷新 / 10 分钟导入）
- 线上容器：`deploy/stack/compose.horizon.yml`、`horizon-loop.sh`、`deploy/aws/bootstrap-horizon.sh`
