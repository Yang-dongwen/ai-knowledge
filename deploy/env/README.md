# 环境变量（密钥）

这一层只有「填什么值」，没有启动逻辑。本机跑看 [../local/README.md](../local/README.md)，发到 AWS 看 [../aws/README.md](../aws/README.md)。

**原则：真实密钥永不进 Git。** 进仓库的只有带 `.example` 的模板。

---

## 这一层每个文件

| 文件 | 进 Git？ | 作用 |
|------|----------|------|
| **README.md** | 是 | 本文 |
| **app.env.example** | 是 | **服务器**环境变量模板。复制成 `app.env` 再填 |
| **app.env** | **否** | 服务器真实密钥（RDS、JWT、R2、AI、`HALO_PAT`…）。本机也放一份，方便 `sync-env.ps1` 上传 |
| **app.env.local.example** | 是 | 本机如果想用 env 而不是 yml，可参考这份。多数情况用 `application-local.yml` 即可 |
| **app.env.local** | **否** | 本机 env（很少用） |

复制：

```powershell
copy deploy\env\app.env.example deploy\env\app.env
# 用编辑器填真实值，然后：
powershell -File deploy/aws/sync-env.ps1
```

`app.env` 改完只影响**已经在跑的服务器**（同步并重启之后）。本机后端读的是 `application-local.yml`，不是这份 env。`gen-local-yml.py` 可以从 `app.env` 抽 R2 / AI / PAT 写进本机 yml。

---

## `app.env` 里要动的几组

打开 `app.env.example` 按注释填。和发版最相关的：

| 前缀 / 变量 | 给谁用 |
|-------------|--------|
| `SPRING_DATASOURCE_*` / `RDS_*` / `DB_*` | okx-bot 连 RDS；`init-rds.sh` 建库 |
| `AUTH_*` / `WX_MINI_*` / `PAY_*` | 登录、小程序、支付 |
| `R2_*` / `STORAGE_ENV_PREFIX` | 对象存储。服务器必须是 `ec2`，本机 yml 里是 `local` |
| `AI_*` | 对话 / 提取 / 文生图 |
| `HALO_*` | 博客。`HALO_PAT` 空则工具台发不了文 |
| `JAVA_OPTS` / `HALO_JVM_OPTS` | 堆大小。2G 机器别加大 |

占位符 `YOUR_RDS_ENDPOINT`、`CHANGE_ME` 若还在，`server-deploy.sh` 会直接拒绝启动。
