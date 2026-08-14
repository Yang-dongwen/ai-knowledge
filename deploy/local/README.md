# 本机部署

本层只服务「在自己电脑把工具台跑起来」。服务器怎么发版看 [../aws/README.md](../aws/README.md)。

本机**不要**用 Docker 跑整套生产栈。Halo 博客也**不用**本机装，发文默认打云端 `https://blog.dwcode.cloud`。

```text
浏览器  http://localhost:3000
            │  /api 被 Vite 转到 8080
            ▼
        okx-bot  :8080     （本机 MySQL 库 okx_bot）
            │  可选：发布到博客
            ▼
        云端 Halo（blog.dwcode.cloud）
```

---

## 这一层每个文件

| 文件 | 作用 | 什么时候用 |
|------|------|------------|
| **README.md** | 本文 | 第一次在本机跑、忘了步骤时 |
| **run.ps1** | `SPRING_PROFILES_ACTIVE=local` 然后 `mvn spring-boot:run` | 不想用 IDE 启动后端时 |
| **gen-local-yml.py** | 读 `deploy/env/app.env` 里的 R2 / AI / HALO_PAT，写出 `application-local.yml` | 本机还没有这份 yml，或想从服务器密钥抄一份 |
| **halo-tunnel.ps1** | SSH 把云端 Halo 容器转到本机 `127.0.0.1:18090` | **仅当** `blog.dwcode.cloud` 打不开、又要测发文时 |

---

## 第一次本机跑

准备：JDK 17、Maven、Node 20+、本机 MySQL 8，库名 `okx_bot`（账号一般是 `root` / `123456`）。

### 1. 后端配置

还没有 `okx-bot/src/main/resources/application-local.yml` 时，二选一：

```powershell
copy okx-bot\src\main\resources\application-local.yml.example okx-bot\src\main\resources\application-local.yml
# 或从服务器密钥生成（会写入 R2 / AI / HALO_PAT）：
python deploy/local/gen-local-yml.py
```

这份 yml **不要提交**。

### 2. 起后端

```powershell
# IDE：运行 OkxBotApplication，Active profiles = local
# 或：
powershell -File deploy/local/run.ps1
```

端口 **8080**。

### 3. 起前端

```powershell
cd okx-trading-web
npm install
npm run dev
```

浏览器 http://localhost:3000 。开发账号看 yml 里 `auth.admin`，常见：

- 邮箱 `admin@okx-bot.local`
- 密码 `Admin@123456`

到这里工具台（知识库、AI 工具）已经能用，**不必**起博客。

---

## 本机「发布到博客」

笔记在**本机** MySQL，文章写到**云端** Halo。两套库，本机不用连 RDS。

`application-local.yml` 里配的是博客 **API 地址 + PAT**，不是博客数据库：

```yaml
halo:
  enabled: true
  base-url: https://blog.dwcode.cloud
  token: pat_xxxx          # 与服务器 deploy/env/app.env 的 HALO_PAT 相同
  public-base-url: https://blog.dwcode.cloud
```

没有 token 时，从 `deploy/env/app.env` 拷，或启动前：

```powershell
Get-Content deploy\env\app.env | ForEach-Object {
  if ($_ -match '^\s*HALO_PAT=(.+)$') { $env:HALO_PAT = $Matches[1].Trim().Trim('"') }
}
```

域名暂时不通时才开隧道（不要用 8090，本机代理常占这个口）：

```powershell
powershell -File deploy/local/halo-tunnel.ps1
$env:HALO_BASE_URL = "http://127.0.0.1:18090"
```

只有改博客皮肤时，才在本机 `docker run halohub/halo:2.25`。那是另一套数据，日常发文不要用。

---

## 常见问题

| 现象 | 处理 |
|------|------|
| 缺少 `application-local.yml` | 复制 example，或跑 `gen-local-yml.py` |
| 发布 503 / 博客未配置 | yml 里 `halo.token` 为空或 `enabled: false` |
| 本机 8090 假 502 | Clash 劫持，用隧道默认的 **18090** |
| MySQL 连不上 | 服务没开，或库名 / 账号和 yml 不一致 |
