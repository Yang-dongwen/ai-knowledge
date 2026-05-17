# Polymarket AI Trader 项目整体实现逻辑

## 一、项目定位

Polymarket AI Trader 是一个 **Polymarket 预测市场 AI 自动交易机器人**。核心思路：

> AI 估计的事件概率 — 市场当前价格 ≥ 最小优势阈值（edge）时才下单

程序不依赖 AI 直接控制下单金额，AI 只提供概率评估和交易建议，最终是否下单由代码风控规则决定。

---

## 二、项目结构

```
polymarket-ai-trader/
├── main.py              # 主入口，循环调度全流程
├── config.py            # 从 .env 加载配置，类型转换，启动校验
├── market_service.py    # 拉取市场数据（Gamma API + CLOB API）
├── ai_provider.py       # 调用 AI 模型分析概率（统一 JSON 输出）
├── strategy.py          # Edge 计算，决定买入方向
├── risk_control.py      # 9 条风控规则，独立于 AI
├── order_service.py     # 提交订单（FAK 类型，支持 dry-run）
├── logger_service.py    # JSONL + runtime.log 日志
├── .env                 # 运行配置（密钥、阈值、开关）
├── .env.example         # 配置模板
├── requirements.txt     # Python 依赖
└── logs/
    ├── runtime.log      # 常规运行日志
    ├── signals.jsonl    # AI 信号日志
    ├── orders.jsonl     # 订单日志
    └── errors.jsonl     # 错误日志
```

---

## 三、端到端数据流

```
┌──────────────────────────────────────────────────────────────────┐
│                         main.py 主循环                            │
│                                                                  │
│  ① fetch_active_markets()                                        │
│     调用 Gamma API 拉取活跃市场列表                                │
│     过滤：成交量 ≥ MIN_VOLUME_USDC 且有 YES/NO token              │
│                              │                                    │
│  ② parse_market_info()                                           │
│     解析 clobTokenIds / outcomePrices / outcomes                  │
│     调用 CLOB API 获取订单簿 → best_bid / best_ask / spread      │
│     如果盘口为空则用 outcomePrices 作为参考价格                     │
│                              │                                    │
│  ③ analyze_market()                                              │
│     构造 prompt（市场问题+价格+成交量+结束时间）                    │
│     调用 AI（OpenAI 兼容接口）返回统一 JSON                        │
│     校验：概率[0,1]、置信度[0,1]、side ∈ {YES,NO,HOLD}             │
│                              │                                    │
│  ④ calculate_edge()                                              │
│     BUY YES: edge = AI_yes概率 − YES市场价                       │
│     BUY NO:  edge = (1−AI_yes概率) − NO市场价                    │
│     HOLD:    edge = 0, 不交易                                      │
│                              │                                    │
│  ⑤ check_risk()                                                  │
│     9 条规则全部通过才放行（详见第五节）                             │
│                              │                                    │
│  ⑥ place_order()                                                 │
│     TRADE_ENABLED=true  → 真实 FAK 订单                           │
│     TRADE_ENABLED=false → 仅记录 dry_run 日志                     │
│                              │                                    │
│  ⑦ log_signal() / log_order() / log_error()                      │
│     写入日志文件，供后续复盘                                       │
│                              │                                    │
│  等待 SCAN_INTERVAL_SECONDS → 回到 ①                             │
└──────────────────────────────────────────────────────────────────┘
```

---

## 四、各模块详细逻辑

### 4.1 config.py — 配置模块

**职责**：从 `.env` 读取所有配置，转换为 Python 类型，启动时校验必要参数。

**关键配置分组**：

| 分组 | 配置项 | 默认值 | 说明 |
|------|--------|--------|------|
| Polymarket | `POLYMARKET_PRIVATE_KEY` | — | Polygon 链钱包私钥 |
| | `POLYMARKET_FUNDER_ADDRESS` | — | 资金合约地址 |
| | `POLYMARKET_SIGNATURE_TYPE` | 0 | 签名类型（0=EOA, 2=Proxy, 3=Deposit） |
| | `CLOB_API_KEY` / `CLOB_SECRET` / `CLOB_PASS_PHRASE` | — | CLOB API 认证凭证 |
| AI | `AI_PROVIDER` | openai | 供应商：openai/deepseek/qwen/custom |
| | `AI_MODEL` | gpt-4.1-mini | 模型名称 |
| | `AI_TEMPERATURE` | 0.2 | 生成温度 |
| | `AI_TIMEOUT_SECONDS` | 60 | 请求超时 |
| Trading | `TRADE_ENABLED` | false | 实盘/模拟盘开关 |
| | `TRADE_AMOUNT_USDC` | 5.0 | 单笔金额 |
| | `MAX_DAILY_ORDERS` | 3 | 每日最大订单数 |
| | `MAX_DAILY_SPEND_USDC` | 15.0 | 每日最大花费 |
| Strategy | `SCAN_INTERVAL_SECONDS` | 60 | 扫描间隔 |
| | `MARKET_LIMIT` | 20 | 每轮扫描市场数 |
| | `MIN_VOLUME_USDC` | 50000 | 最低成交量 |
| | `MIN_EDGE` | 0.15 | 最小 edge |
| | `MIN_AI_CONFIDENCE` | 0.70 | 最低 AI 置信度 |
| | `MAX_SPREAD` | 0.04 | 最大价差 |

**校验逻辑**：启动时调用 `config.validate()`，检查：
- `TRADE_ENABLED=true` 但 `POLYMARKET_PRIVATE_KEY` 未配置 → 报错
- AI 供应商对应的 API Key 未配置 → 报错

### 4.2 market_service.py — 市场数据模块

**职责**：从 Polymarket API 获取市场数据和盘口价格。

**函数**：

1. **`fetch_active_markets(limit, min_volume)`**
   - 调用 `GET https://gamma-api.polymarket.com/markets`
   - 参数：`active=true`, `closed=false`, `order=volume`, `ascending=false`
   - 过滤：成交量 ≥ `min_volume` 且 `clobTokenIds` 有至少 2 个 token

2. **`get_orderbook(token_id)`**
   - 调用 `GET https://clob.polymarket.com/book?token_id=...`
   - 返回 bids/asks 列表

3. **`get_best_prices(token_id)`**
   - 基于订单簿计算 `best_bid`、`best_ask`、`spread`
   - 如果获取失败返回兜底值（bid=0, ask=1, spread=1）

4. **`parse_market_info(market)`**
   - 解析 `clobTokenIds`：JSON 字符串 → 列表，按 outcomes 顺序确定 YES/NO token_id
   - 获取盘口价格，如果盘口为空则用 `outcomePrices` 作为参考
   - 返回统一市场信息字典

**关键 API 数据格式**：
```
clobTokenIds:  '["yes_token_id", "no_token_id"]'  (JSON 字符串)
outcomePrices: '["0.55", "0.45"]'                  (JSON 字符串)
outcomes:       '["Yes", "No"]'                     (JSON 字符串)
```

### 4.3 ai_provider.py — AI 模型模块

**职责**：调用 AI 模型分析市场，返回统一结构化结果。

**支持的供应商**（均使用 OpenAI 兼容接口）：
- openai → `OPENAI_API_KEY` + `OPENAI_BASE_URL`
- deepseek → `DEEPSEEK_API_KEY` + `DEEPSEEK_BASE_URL`
- qwen → `QWEN_API_KEY` + `QWEN_BASE_URL`
- custom → `CUSTOM_AI_API_KEY` + `CUSTOM_AI_BASE_URL`

**AI 返回格式（JSON Schema 校验）**：
```json
{
  "estimated_probability": 0.68,   // AI 估计的事件发生概率 [0, 1]
  "confidence": 0.74,               // AI 置信度 [0, 1]
  "recommended_side": "YES",        // YES / NO / HOLD
  "should_trade": true,             // 是否值得交易
  "reason": "...",                   // 分析原因
  "risk_level": "medium"             // low / medium / high
}
```

**校验逻辑**：
- 解析 AI 返回的 JSON（处理 markdown 代码块包裹的情况）
- 检查 6 个必需字段是否齐全
- 数值范围 clamp：概率和置信度 clamp 到 [0, 1]
- `recommended_side` 非法值默认为 `HOLD`
- 任何错误返回 `None`，主循环跳过该市场

**System Prompt 核心要求**：
- 评估事件的真实概率
- 如果市场价格已反映公允价值，建议 HOLD
- 只有存在显著 edge 时才建议交易
- 必须返回纯 JSON，不包含任何其他文本

### 4.4 strategy.py — 策略模块

**职责**：根据 AI 结果和市场价格计算 edge，确定交易方向。

**Edge 计算逻辑**：
```
如果 AI 推荐 YES:
  edge = AI_估计YES概率 − YES市场买入价(best_ask)
  buy_price = YES市场买入价
  token_id  = YES token

如果 AI 推荐 NO:
  edge = (1 − AI_估计YES概率) − NO市场买入价(best_ask)
  buy_price = NO市场买入价
  token_id  = NO token

如果 AI 推荐 HOLD:
  edge = 0, 不交易
```

**示例**：
```
YES 市场价 = 0.42
AI 估计 YES 概率 = 0.68
edge = 0.68 − 0.42 = 0.26

如果 edge ≥ MIN_EDGE (0.15)，则有交易价值
```

### 4.5 risk_control.py — 风控模块

**职责**：独立于 AI，由代码规则决定是否允许下单。

**9 条风控规则**（全部通过才放行）：

| # | 规则 | 阈值配置 | 说明 |
|---|------|----------|------|
| 1 | AI 建议交易 | — | `should_trade=true` |
| 2 | AI 非 HOLD | — | `recommended_side ≠ HOLD` |
| 3 | AI 置信度足够 | `MIN_AI_CONFIDENCE` (0.70) | `confidence ≥ 0.70` |
| 4 | Edge 足够 | `MIN_EDGE` (0.15) | `edge ≥ 0.15` |
| 5 | 市场成交量足够 | `MIN_VOLUME_USDC` (50000) | `volume ≥ 50000` |
| 6 | 盘口价差合理 | `MAX_SPREAD` (0.04) | `spread ≤ 0.04` |
| 7 | 当日订单数未超限 | `MAX_DAILY_ORDERS` (5) | 统计 `orders.jsonl` |
| 8 | 当日花费未超限 | `MAX_DAILY_SPEND_USDC` (50) | 统计 `orders.jsonl` |
| 9 | 标题不含屏蔽关键词 | `BLOCK_KEYWORDS` | 默认屏蔽: war, death, tragedy |

**关键设计**：
- 每日订单统计通过读取 `logs/orders.jsonl` 中当天记录实现
- 返回 `{"allowed": bool, "reject_reasons": [str]}`，所有不通过的原因都记录
- 风控模块**不依赖 AI 判断**，AI 可以建议交易但风控可以拒绝

### 4.6 order_service.py — 下单模块

**职责**：根据交易信号提交订单到 Polymarket CLOB。

**下单流程**：
```
TRADE_ENABLED=false → 记录 dry_run 日志，不提交真实订单
TRADE_ENABLED=true  → 初始化 ClobClient → 构造 OrderArgs → submit FAK 订单
```

**订单类型**：FAK (Fill-And-Kill)
- 允许部分成交，未成交部分自动取消
- 避免订单长期挂在盘口
- 避免 AI 信号过期后仍然成交

**下单参数**：
- `side`: 固定 "BUY"（只支持买入）
- `token_id`: YES 或 NO 的 token
- `price`: 策略模块计算的 buy_price
- `size`: `amount_usdc / price`（向下取整到 2 位小数）
- `chain_id`: 137（Polygon）

**错误处理**：
- `py-clob-client` 未安装 → 记录错误日志
- 其他异常 → 记录错误日志 + 写入 `errors.jsonl`

### 4.7 logger_service.py — 日志模块

**职责**：记录运行日志，JSONL 格式方便后续复盘。

**日志文件**：

| 文件 | 格式 | 内容 |
|------|------|------|
| `runtime.log` | 文本 | 常规运行日志（Python logging） |
| `signals.jsonl` | JSONL | AI 信号（每行一个 JSON） |
| `orders.jsonl` | JSONL | 订单结果 |
| `errors.jsonl` | JSONL | 错误详情（含 traceback） |

**JSONL 记录自动附加 `time` 字段**（UTC ISO 格式）。

### 4.8 main.py — 主循环

**职责**：组装各模块，循环执行市场扫描和交易。

**执行流程**：
```
main()
  ├─ config.validate()  校验配置
  └─ while True:
       ├─ run_scan()
       │    ├─ 打印配置信息
       │    ├─ fetch_active_markets()  拉取市场
       │    └─ for each market:
       │         ├─ parse_market_info()  解析市场
       │         ├─ 检查 token_id 是否有效
       │         ├─ analyze_market()  AI 分析
       │         ├─ calculate_edge()  计算 edge
       │         ├─ check_risk()  风控校验
       │         ├─ log_signal()  记录信号
       │         └─ place_order() / skip  下单或跳过
       └─ time.sleep(SCAN_INTERVAL_SECONDS)
```

**异常处理**：
- 单个市场异常 → 跳过该市场，继续下一个
- 整轮扫描异常 → 记录错误日志，继续下一轮
- `KeyboardInterrupt` → 优雅退出

---

## 五、关键数据格式

### Gamma API 返回的市场字段

```
id:                市场唯一标识
question:          市场问题（如 "Will BTC hit $120k before June 30?"）
description:       市场描述
volume:            成交量（USDC）
clobTokenIds:      JSON 字符串 '["yes_token_id", "no_token_id"]'
outcomePrices:     JSON 字符串 '["0.55", "0.45"]'
outcomes:          JSON 字符串 '["Yes", "No"]'
endDate:           市场结束时间
conditionId:       条件合约 ID
active:            是否活跃
closed:            是否已关闭
```

### 策略模块返回格式

```json
{
  "side": "YES",
  "edge": 0.26,
  "buy_price": 0.42,
  "token_id": "7216...',
  "should_trade": true,
  "reason": "edge=0.26, min_edge=0.15"
}
```

### 风控模块返回格式

```json
{
  "allowed": true,
  "reject_reasons": []
}
```

或

```json
{
  "allowed": false,
  "reject_reasons": ["AI 置信度不足: 0.55 < 0.70", "Edge 不足: 0.05 < 0.15"]
}
```

---

## 六、当前运行配置

根据 `.env` 文件，当前配置为：
- **AI 模型**：custom provider（glm-5.1 模型）
- **交易模式**：dry-run（`TRADE_ENABLED=false`）
- **单笔金额**：1 USDC
- **每日限额**：最多 5 单、50 USDC
- **扫描间隔**：30 秒
- **策略阈值**：最低概率 65%、最低置信度 70%、最低 Edge 15%、最大价差 4%
- **最低成交量**：50,000 USDC
- **屏蔽关键词**：war, death, tragedy

---

## 七、开发阶段（来自设计文档）

| 阶段 | 目标 | 状态 |
|------|------|------|
| 第 1 阶段 | 基础行情和配置搭建 | 已完成 |
| 第 2 阶段 | AI 自动分析集成 | 已完成 |
| 第 3 阶段 | 策略和风控实现 | 已完成 |
| 第 4 阶段 | 模拟盘运行验证 | 当前阶段 |
| 第 5 阶段 | 实盘小金额下单 | 待开启 |

---

## 八、已知问题

runtime.log 显示市场在 `parse_market_info()` 阶段因 **token_id 为空** 被跳过。原因可能是 Gamma API 返回的 `clobTokenIds` 字段格式与解析逻辑不匹配，需要排查 API 实际返回的数据结构。

---

## 九、MVP 不做的功能

- Web 后台 / 数据库 / 用户系统
- 多账户管理
- 自动止盈止损 / 自动卖出
- 复杂仓位管理
- 高频 WebSocket 交易
- 多市场套利
- 回测系统