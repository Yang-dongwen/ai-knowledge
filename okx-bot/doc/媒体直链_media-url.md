# 媒体直链 `GET .../media-url`

**日期**：2026-09-09  
**状态**：已落地  
**目的**：播放 / 另存为**先拿一个很小的 JSON URL**，媒体字节走 R2 预签名或同源代理，不经「整包进 Java / JS 堆」再返回。

三个模块映射相同，签发逻辑共用 `MediaUrlService`；**差异只在「定位哪一个文件」**。

| 模块 | 路径 | Service |
|------|------|---------|
| 视频提取 | `GET /api/v1/video/tasks/{taskId}/media-url` | `VideoProcessService.resolveVideoMediaUrl` |
| AI 成片 | `GET /api/v1/aigen/tasks/{taskId}/media-url` | `AigenTaskService.resolveOutputMediaUrl` |
| 文生图 | `GET /api/v1/imggen/tasks/{taskId}/media-url?fileName=` | `ImgGenTaskService.resolveMediaUrl` |

Query：`disposition=inline`（默认，播放/预览）| `attachment`（另存为）。imggen 另需 `fileName`。

成片还有 `GET /api/v1/aigen/tasks/{taskId}/shots/{shotId}/media-url`（镜头静图，固定 inline），签发同样走 `MediaUrlService`。

---

## 1. 总流程

```
前端 Authorization: Bearer
        │
        ▼
Controller  GET .../media-url?disposition=
        │
        ▼
① 鉴权：requireOwnedTask（登录 + 任务归属）
        │
        ▼
② 定位文件：object key 或本地绝对路径（模块各写各的）
        │
        ▼
③ MediaUrlService.resolve(keyOrPath, proxyPath, attachment, downloadName)
        │
        ├─ 可预签名 → HEAD exists + R2 presignGet → { mode: presign, url: https://... }
        └─ 否则      → { mode: proxy,   url: /api/v1/...  (+ ?download=true) }
        │
        ▼
ApiResult<MediaUrlResponse>
        │
        ▼
前端 attachAccessTokenIfProxy：proxy 时给相对路径补 access_token
        │
        ├─ inline      → <video src> / <img src>
        └─ attachment  → triggerNativeDownload（浏览器自己拉流）
```

本接口**不读媒体正文**。真正传文件的是下一步：R2 GET，或同源代理（video / aigen output / imggen media）。

---

## 2. 响应

`MediaUrlResponse`：

| 字段 | presign | proxy |
|------|---------|-------|
| `url` | 带签名的 HTTPS | 同源相对路径（attachment 时带 `download=true`） |
| `mode` | `presign` | `proxy` |
| `expiresAtMs` / `ttlSeconds` | 过期时刻 / TTL（默认 ≥60s，配 `storage.r2.presign-ttl-seconds`） | 0 |
| `objectKey` | 对象 key | 本地绝对路径时为 null |
| `proxyPath` | 始终带上，前端预签名失败可降级 | 与 `url` 相同 |

---

## 3. 核心：`MediaUrlService.resolve`

配置：`storage.serve-mode` = `proxy` | `presign` | `hybrid`；`storage.provider` = `local` | `r2`。

**能预签名**（同时满足）：

1. 当前存储 `providerId() == r2`
2. `objectKeyOrPath` 非空，且**不是**本地绝对路径（`ObjectKeyBuilder.looksLikeLocalAbsolutePath`）
3. `serve-mode` 为 `presign` 或 `hybrid`

然后：

1. `objectStorage.exists(key)`，没有则 404（仅 `presign` 模式把 404 抛给前端；`hybrid` 预签名失败会降级 proxy）
2. `presignGet(key, ttl, attachment, downloadName)`  
   - `attachment=true`：预签名带 `Content-Disposition: attachment; filename*=UTF-8''...`（跨域 `<a download>` 靠这个文件名）
3. 返回 `mode=presign`

**否则 proxy**：

- `url` / `proxyPath` = 各模块传入的代理路径
- `attachment=true` 时追加 `?download=true`（已有 `download=` 不重复），代理 GET 据此写 `Content-Disposition: attachment`

local 存储、本机绝对路径、serve-mode=proxy、预签名异常 → 都走 proxy。

---

## 4. 定位文件（模块差异）

### 4.1 视频提取（最分叉）

`VideoProcessService.resolveVideoMediaUrl`

| disposition | 定位 | 会不会转码 |
|-------------|------|------------|
| `attachment` | `resolveDownloadableVideo` | **否**。优先同目录源片 `video.mp4` / `video.webm`（即使 `videoPath` 已是 `video.browser.mp4`） |
| `inline` | `resolvePlayableVideo` | **可以**。为 Chrome 播抖音 HEVC，必要时懒转 H.264 并回写 browser 对象 |

`attachment` 路径：本地盘用 `preferOriginalLocal`；对象 key 用 `siblingOriginalObjectKeys` 探测源片是否存在，没有才退回当前 `videoPath`。不调 `ensureBrowserPlayable`，不从 R2 拉回整片。

`inline` 路径：本地 `ensureBrowserPlayable`；对象存储优先 `video.browser.mp4`，没有且源片是 mp4 时可能 **materialize → ffmpeg → publish browser**（这是播放卡顿来源，下载已拆开）。

代理回退：`/api/v1/video/tasks/{id}/video`（Range；`?download=true` 同样走源片）。

### 4.2 AI 成片

`resolveOutputKeyOrPath`：`outputPath` 是本地文件则用绝对路径；否则 object key，必要时再试标准 key `.../output.mp4`。成片本身已是渲染结果，**没有** HEVC 懒转码。

代理：`/api/v1/aigen/tasks/{id}/media/output`。

### 4.3 文生图

`fileName` 禁止 `..` / 路径分隔。先找 scratch/旧 work-dir 的 `outputs/{fileName}`，再找 object key `.../outputs/{fileName}`。

代理：`/api/v1/imggen/tasks/{id}/media/{fileName}`。

---

## 5. 前端怎么用

`attachAccessTokenIfProxy`（`okx-trading-web/src/api/video.api.ts`）：

- `mode=presign` 且 `url` 是 `http(s):` → 原样给 `<video>` / `<a>`（R2 靠签名，不要再加 JWT）
- `mode=proxy` 或相对路径 → `?access_token=`（`<video src>` 带不了 Authorization）

媒体 GET 允许 query JWT 的白名单在 `JwtAuthFilter.MEDIA_QUERY_TOKEN_PATH`（`/video`、`/media/output`、`/media/{file}` 等）。**`media-url` 本身必须 Header Bearer**，不在白名单里。

播放：`disposition=inline` → 赋 `src`。  
下载：`disposition=attachment` → `triggerNativeDownload`，禁止 `arrayBuffer` 整包。详见 [媒体下载_流式另存为方案.md](./媒体下载_流式另存为方案.md)。

---

## 6. 和代理 GET 的关系

| | media-url | 代理 GET（如 `.../video`） |
|--|-----------|---------------------------|
| 返回 | JSON 地址 | 媒体字节（可 Range） |
| 何时打到 | 每次点播放/下载先打一次 | presign 失败或 local；或 `<video>` 直接用 proxy URL |
| 流量 | 可忽略 | 整文件经 Java（nginx 已关 proxy_buffering） |

生产 `serve-mode=presign|hybrid` + R2 时，播放/下载的字节应直连桶；本接口只做归属校验和签名。

---

## 7. 配置要点

```yaml
storage:
  provider: local | r2
  serve-mode: proxy | presign | hybrid
  r2:
    presign-ttl-seconds: 900
```

本机默认 `local` + `proxy`：media-url 几乎总是返回 `/api/v1/...`，文件仍走后端流。线上 R2 才体现「流量不经应用」。
