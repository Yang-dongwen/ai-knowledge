# Polymarket 市场数据分析报告

## 一、Gamma API 返回字段全景

通过 `GET https://gamma-api.polymarket.com/markets` 获取的每个市场对象包含以下字段：

### 1.1 核心身份字段

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `id` | string | 市场唯一 ID | `"2164066"` |
| `question` | string | 市场问题 | `"Will the US acquire part of Greenland in 2026?"` |
| `slug` | string | URL slug | `"will-the-us-acquire-part-of-greenland"` |
| `conditionId` | string | 条件合约 ID（0x 开头） | `"0xfd551ce..."` |
| `questionID` | string | 问题哈希 | `"0x67ea07..."` |
| `description` | string | 市场描述（可能很长） | `"In the upcoming game..."` |
| `resolutionSource` | string | 解决来源 URL | `"https://spfl.co.uk/"` |

### 1.2 市场状态字段

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `active` | bool | 是否活跃 | `true` |
| `closed` | bool | 是否已关闭 | `false` |
| `archived` | bool | 是否已归档 | `false` |
| `acceptingOrders` | bool | 是否接受订单 | `true` |
| `new` | bool | 是否新市场 | `false` |
| `featured` | bool | 是否推荐 | `false` |
| `restricted` | bool | 是否受限市场 | `true`（体育市场多为 restricted） |
| `approved` | bool | 是否已审核 | `true` |
| `ready` | bool | 是否就绪 | `false` |
| `funded` | bool | 是否已注资 | `false` |

### 1.3 时间字段

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `startDate` | string | 市场开始时间（ISO 8601） | `"2026-05-05T09:04:24.386692Z"` |
| `endDate` | string | 市场结束时间（ISO 8601） | `"2026-12-31T00:00:00Z"` |
| `startDateIso` | string | 开始日期 | `"2026-05-05"` |
| `endDateIso` | string | 结束日期 | `"2026-12-31"` |
| `createdAt` | string | 创建时间 | `"2026-05-05T09:00:02.516919Z"` |
| `updatedAt` | string | 更新时间 | `"2026-05-17T03:58:40.052135Z"` |

### 1.4 交易核心字段（程序使用的）

| 字段 | 类型 | 说明 | **关键发现** |
|------|------|------|------|
| `clobTokenIds` | string (JSON) | YES/NO token ID 列表 | `'["token_yes...", "token_no..."]'` |
| `outcomes` | string (JSON) | 结果选项名称 | `'["Yes", "No"]'` 或 `'["CD Tolima", "Atlético Nacional"]'` |
| `outcomePrices` | string (JSON) | 对应结果的价格 | `'["0.33", "0.67"]'` |
| `volume` | string/number | 成交量（USDC） | `"99.999392"` 或 `9992170` |
| `volumeNum` | float | 成交量（数值） | `9,992,170.0` |
| `bestBid` | float/null | 最优买价 | `0.13` 或 `null` |
| `bestAsk` | float/null | 最优卖价 | `0.14` 或 `null` |
| `spread` | float/null | 价差 | `0.01` 或 `null` |
| `lastTradePrice` | float/null | 最近成交价 | `0.14` 或 `null` |

### 1.5 流动性字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `liquidity` | string/float | 总流动性 |
| `liquidityNum` | float | 流动性数值 |
| `liquidityClob` | float | CLOB 流动性 |
| `volume24hr` / `volume24hrClob` | float | 24 小时成交量 |
| `volume1wk` / `volume1wkClob` | float | 1 周成交量 |
| `volume1mo` / `volume1moClob` | float | 1 月成交量 |

### 1.6 negRisk 组合市场字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `negRisk` | bool | 是否为负风险（组合）市场 |
| `negRiskMarketID` | string | 负风险市场组 ID |
| `negRiskRequestID` | string | 负风险请求 ID |
| `negRiskOther` | bool | 是否为负风险 OTHER 选项 |

### 1.7 体育市场专用字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `sportsMarketType` | string | `"moneyline"` / `"spreads"` / `"totals"` |
| `gameId` | int | 比赛 ID |
| `gameStartTime` | string | 比赛开始时间 |
| `line` | float | 让球线（spreads 市场） |
| `groupItemTitle` | string | 组选项标题 |
| `groupItemThreshold` | string | 组选项阈值 |
| `secondsDelay` | int | 延迟秒数 |

### 1.8 其他字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `image` / `icon` | string | 图片 URL |
| `orderPriceMinTickSize` | float | 最小价格变动单位 |
| `orderMinSize` | float | 最小下单数量 |
| `feeType` | string | 费用类型 |
| `feeSchedule` | object | 费用规则 |
| `events` | array | 关联事件列表（嵌套结构） |
| `oneDayPriceChange` | float | 24h 价格变化 |
| `oneWeekPriceChange` | float | 1 周价格变化 |
| `umaBond` | string | UMA 保证金 |
| `umaReward` | string | UMA 奖励 |

---

## 二、关键发现与问题分析

### 2.1 outcomes 不总是 "Yes"/"No"

**问题**：代码 `parse_market_info()` 假设 outcomes 总是 `["Yes", "No"]`，但实际情况：

| 市场类型 | outcomes 示例 | token 映射 |
|----------|--------------|------------|
| 二元是/否市场 | `["Yes", "No"]` | YES=token[0], NO=token[1] |
| 体育 moneyline | `["Yes", "No"]` | YES=token[0], NO=token[1] |
| 体育 spreads | `["CD Tolima", "Atlético Nacional"]` | **无 YES/NO** |
| 体育 totals | `["Over", "Under"]` | **无 YES/NO** |
| 电竞市场 | `["SINQU", "KUUSAMO.gg"]` | **无 YES/NO** |

**影响**：当 outcomes 不是 `"Yes"` / `"No"` 时，代码无法匹配 token_id，导致 `yes_token_id` 和 `no_token_id` 为空字符串，市场被跳过。

**代码逻辑**（`market_service.py:137-148`）：
```python
if len(clob_token_ids) >= 2 and len(outcomes) >= 2:
    for i, outcome in enumerate(outcomes):
        if outcome.upper() == "YES" and i < len(clob_token_ids):
            yes_token_id = clob_token_ids[i]
        elif outcome.upper() == "NO" and i < len(clob_token_ids):
            no_token_id = clob_token_ids[i]
elif len(clob_token_ids) >= 2:
    # 默认第一个是 YES，第二个是 NO
    yes_token_id = clob_token_ids[0]
    no_token_id = clob_token_ids[1]
```

**问题复盘**：
1. 如果 outcomes 是 `["CD Tolima", "Atlético Nacional"]`，两个 outcome 都不匹配 `"YES"` 或 `"NO"`，`yes_token_id` 和 `no_token_id` 都保持空字符串。
2. 进入 `elif` 分支时，只有 `len(clob_token_ids) >= 2` 但 `len(outcomes) < 2` 才会走默认逻辑——而实际场景中 outcomes 始终有值，所以 `elif` 不会被触发。
3. 结果：非 Yes/No 格式的市场**全部被跳过**。

### 2.2 Gamma API 自带价格数据

**发现**：Gamma API 返回中直接包含了 `bestBid`、`bestAsk`、`spread`、`lastTradePrice`、`outcomePrices` 字段，**不需要额外调用 CLOB API 获取**。

**当前代码行为**：
- `parse_market_info()` 调用 `get_best_prices()` → 调用 `get_orderbook()` → 调用 CLOB API
- 对于 Greenland 市场，CLOB 返回的是**错误数据**（best bid=0.01, best ask=0.99），而 Gamma API 返回的 bestBid=0.13, bestAsk=0.14 才是正确的

**实测对比**：

| 来源 | YES best_bid | YES best_ask | NO best_bid | NO best_ask |
|------|-------------|-------------|-------------|-------------|
| Gamma API | 0.13 | 0.14 | — | — |
| CLOB API (YES token) | 0.01 | 0.99 | — | — |
| Gamma outcomePrices | — | 0.135 | — | 0.865 |

CLOB 单 token 查询返回的是该 token 的买卖盘口，但不代表 YES 市场价格——需要分别查询 YES 和 NO token 才能得到完整数据。

### 2.3 volume 字段类型不一致

**发现**：`volume` 字段在不同市场中类型不同：
- 高成交量市场：`volume: 9992170`（数字类型）
- 低成交量市场：`volume: "99.999392"`（字符串类型）

当前代码用 `float(m.get("volume", 0) or 0)` 处理，兼容了类型差异。但 `volumeNum` 始终是 float，更可靠。

### 2.4 高成交量市场稀少

**实测数据**（100 个市场样本）：
- 成交量 ≥ 50,000 USDC：**28 个**
- 成交量 ≥ 10,000 USDC：**28 个**（与 ≥ 50,000 重合）
- 成交量范围：10 ~ 9,992,170 USDC
- 中位数成交量：约 9,862 USDC

大量拉取的市场是低成交量的体育/电竞市场（成交量仅 100 左右），高成交量的政治/加密/宏观市场实际上只有几十个。

### 2.5 CLOB 订单簿的实际结构

CLOB API `/book?token_id=...` 返回：
```json
{
  "bids": [{"price": "0.13", "size": "5000"}, ...],
  "asks": [{"price": "0.14", "size": "12000"}, ...]
}
```

每个 token（YES 或 NO）有自己的 orderbook。当前代码只查 YES token 的 orderbook 就当作 YES 的 best_bid/best_ask，再单独查 NO token 的 orderbook 获取 NO 价格——这是正确的，但每次对每个市场要发 2 次 CLOB 请求。

### 2.6 默认排序按 volume 但低成交量市场居多

当前 `fetch_active_markets()` 请求参数 `limit=20` 且 `order=volume, ascending=False`，理论上结果是按成交量从高到低排列的。但实测发现返回的前几个市场成交量仅 ~100 USDC，并非真正的高成交量市场。

**可能原因**：Gamma API 的排序可能受 `enableOrderBook` 参数或其他因素影响。

---

## 三、数据流经代码的问题汇总

### 问题 1：非 Yes/No outcomes 导致 token_id 丢失（严重）

**现象**：所有 outcomes 不是 `["Yes", "No"]` 格式的市场，`yes_token_id` 和 `no_token_id` 均为空，市场被跳过。

**影响范围**：
- 体育 spread/totals 市场（outcomes 为队名或 Over/Under）
- 电竞市场（outcomes 为队伍缩写）
- 某些非标市场

**修复方案**：修改 token_id 解析逻辑，不依赖 outcomes 名称匹配：
```python
# 对于所有二元市场，直接取 token[0] 作为买入方向 A，token[1] 作为买入方向 B
# 用 outcomes 名称作为显示标签，而非硬编码 YES/NO
```

### 问题 2：CLOB API 返回的价格可能不准确（中等）

**现象**：单独查询某个 token 的 orderbook，返回的 best_bid/best_ask 可能反映了极端挂单，并非真实成交价。

**修复方案**：优先使用 Gamma API 返回的 `bestBid`、`bestAsk`、`spread` 字段，仅在缺失时 fallback 到 CLOB API。

### 问题 3：低成交量市场浪费 AI 调用（轻微）

**现象**：拉取的 20 个市场中大量是成交量仅 100 USDC 的体育市场，不满足 `MIN_VOLUME_USDC=50000` 的条件，浪费了 API 调用和解析时间。

**修复方案**：可以在 Gamma API 请求时添加成交量筛选参数，或在拉取后更早过滤。

### 问题 4：Gamma API 已包含完整价格信息（优化）

**现象**：`bestBid`、`bestAsk`、`spread`、`lastTradePrice`、`outcomePrices` 都在 Gamma API 返回中，当前代码额外调用 CLOB API 获取这些数据。

**修复方案**：先使用 Gamma API 的价格数据，减少 API 调用次数和延迟。

---

## 四、市场数据字段与代码映射关系

| Gamma API 字段 | 代码中使用位置 | 当前处理方式 |
|---------------|---------------|-------------|
| `id` | `market_info["market_id"]` | 直接取值 |
| `question` | `market_info["question"]` | 直接取值 |
| `description` | `market_info["description"]` | 截取前 500 字符 |
| `volume` / `volumeNum` | `market_info["volume"]` | `float(m.get("volume", 0) or 0)` |
| `endDate` | `market_info["end_date"]` | 直接取值 |
| `end_date_iso` | `market_info["end_date"]` fallback | 直接取值 |
| `conditionId` / `condition_id` | `market_info["condition_id"]` | 优先 conditionId，回退 condition_id |
| `clobTokenIds` | `market_info["yes/no_token_id"]` | **JSON 解析 + outcomes 名称匹配**（有 bug） |
| `outcomes` | token_id 映射 | **用于 YES/NO 名称匹配**（有 bug） |
| `outcomePrices` | 价格 fallback | 仅在 CLOB 返回 ask=1 时使用 |
| `bestBid` | **未使用** | Gamma API 已返回但代码忽略 |
| `bestAsk` | **未使用** | Gamma API 已返回但代码忽略 |
| `spread` | **未使用** | Gamma API 已返回但代码忽略 |
| `negRisk` | **未使用** | 组合市场标记，代码未处理 |
| `sportsMarketType` | **未使用** | 体育市场类型标记，可用于过滤 |
| `category` | **不存在** | Gamma API 无 category 字段 |

---

## 五、原始市场数据完整示例

### 示例 1：高成交量政治市场（Yes/No 二元）

```json
{
  "id": "2162994",
  "question": "Will the US acquire part of Greenland in 2026?",
  "volume": 9992170,
  "volumeNum": 9992170.0,
  "liquidity": "78695.8454",
  "outcomes": "[\"Yes\", \"No\"]",
  "outcomePrices": "[\"0.135\", \"0.865\"]",
  "clobTokenIds": "[\"6074...8964...\", \"1048...7366...\"]",
  "bestBid": 0.13,
  "bestAsk": 0.14,
  "spread": 0.01,
  "lastTradePrice": 0.14,
  "negRisk": false,
  "active": true,
  "closed": false,
  "acceptingOrders": true,
  "endDate": "2026-12-31T00:00:00Z",
  "description": "This market will resolve...",
  "events": [...]
}
```

### 示例 2：体育市场（非 Yes/No outcomes）

```json
{
  "id": "2276733",
  "question": "Spread: CD Tolima (-1.5)",
  "outcomes": "[\"CD Tolima\", \"Atlético Nacional\"]",
  "outcomePrices": "[\"0.0005\", \"0.9995\"]",
  "clobTokenIds": "[\"3838...1219...\", \"8898...5259...\"]",
  "sportsMarketType": "spreads",
  "negRisk": false,
  "bestBid": null,
  "bestAsk": 0.001,
  "spread": 0.001
}
```

### 示例 3：电竞市场（非 Yes/No outcomes）

```json
{
  "question": "Counter-Strike: SINQU vs KUUSAMO.gg (BO3)",
  "outcomes": "[\"SINQU\", \"KUUSAMO.gg\"]",
  "outcomePrices": "[\"0.57\", \"0.43\"]",
  "bestBid": 0.47,
  "bestAsk": 0.67,
  "spread": 0.2
}
```