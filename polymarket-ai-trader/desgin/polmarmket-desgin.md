**独立 Python 脚本版 Polymarket AI 自动交易开发方案**，只围绕核心能力：

> Python 实现
> 交易金额可配置
> AI 自动分析
> 自动化下单
> 日志记录
> 模型供应商/模型可配置

此方案不接 OKX、不接后台、不接数据库，先做一个可运行的 MVP 脚本。

---

# 一、项目目标

开发一个独立 Python 程序：

```text
polymarket-ai-trader
```

程序启动后自动完成：

```text
1. 拉取 Polymarket 活跃市场
2. 筛选高流动性、规则清晰的事件
3. 获取 YES / NO 当前价格和盘口
4. 调用 AI 模型进行概率分析
5. 计算 AI 预测概率和市场价格之间的价差
6. 通过风控规则判断是否允许交易
7. 自动下单
8. 记录信号、订单、错误日志
```

Polymarket 官方文档说明，Gamma API / Data API 是公开数据接口，不需要认证；CLOB API 包含公开行情接口和需要认证的订单管理接口。下单、撤单等交易接口需要认证。([Polymarket 文档][1])

官方也提供 Python、TypeScript、Rust SDK，支持 CLOB API 的市场数据、订单管理和认证。([Polymarket 文档][2])

---

# 二、技术选型

## 1. 开发语言

```text
Python 3.10+
```

原因：

```text
1. Polymarket 有官方 Python SDK
2. AI API 调用方便
3. 单脚本实现成本低
4. 日志、配置、定时任务处理简单
```

Polymarket 官方文档也建议使用开源 SDK，因为手动准备订单签名比较复杂，SDK 会处理订单签名和提交。([Polymarket 文档][3])

---

## 2. 核心依赖

```bash
pip install py-clob-client-v2
pip install requests
pip install python-dotenv
pip install openai
pip install jsonschema
```

如果后续接 Claude，再加：

```bash
pip install anthropic
```

---

# 三、项目目录设计

```text
polymarket-ai-trader/
├── main.py                         # 程序入口
├── config.py                       # 配置读取
├── market_service.py               # Polymarket 市场和盘口数据
├── ai_provider.py                  # AI 模型供应商适配
├── strategy.py                     # 策略判断和 edge 计算
├── risk_control.py                 # 风控规则
├── order_service.py                # 自动下单
├── logger_service.py               # JSONL 日志记录
├── .env                            # 配置文件
└── logs/
    ├── runtime.log
    ├── signals.jsonl
    ├── orders.jsonl
    └── errors.jsonl
```

第一版也可以先写成一个文件，但我建议按这个结构拆开，后续维护更清楚。

---

# 四、核心流程设计

```text
启动程序
  ↓
读取 .env 配置
  ↓
初始化 Polymarket CLOB 客户端
  ↓
初始化 AI Provider
  ↓
拉取活跃市场
  ↓
过滤低成交量 / 低流动性 / 规则不清晰市场
  ↓
获取 YES / NO 盘口价格
  ↓
调用 AI 分析事件概率
  ↓
计算 AI edge
  ↓
执行风控校验
  ↓
如果 TRADE_ENABLED=true，则自动下单
  ↓
记录日志
  ↓
等待下一轮扫描
```

Polymarket 的 CLOB 是中央限价订单簿模式，官方说明其交易系统采用链下撮合、链上结算的方式。([Polymarket 文档][4])

---

# 五、配置文件设计

`.env` 负责所有可变配置。

```env
# =========================
# Polymarket
# =========================
POLYMARKET_PRIVATE_KEY=你的钱包私钥
POLYMARKET_FUNDER_ADDRESS=你的资金钱包地址
POLYMARKET_SIGNATURE_TYPE=0

CLOB_API_KEY=
CLOB_SECRET=
CLOB_PASS_PHRASE=

# =========================
# AI Provider
# =========================
AI_PROVIDER=openai
AI_MODEL=gpt-4.1-mini
AI_TEMPERATURE=0.2
AI_TIMEOUT_SECONDS=60

OPENAI_API_KEY=你的OpenAI_KEY
OPENAI_BASE_URL=https://api.openai.com/v1

DEEPSEEK_API_KEY=你的DeepSeek_KEY
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat

QWEN_API_KEY=你的Qwen_KEY
QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_MODEL=qwen-plus

CUSTOM_AI_API_KEY=
CUSTOM_AI_BASE_URL=
CUSTOM_AI_MODEL=

# =========================
# Trading Switch
# =========================
TRADE_ENABLED=false

# =========================
# Order Config
# =========================
TRADE_AMOUNT_USDC=10
MAX_DAILY_ORDERS=5
MAX_DAILY_SPEND_USDC=50
MAX_SINGLE_MARKET_POSITION_USDC=30

# =========================
# Strategy Config
# =========================
SCAN_INTERVAL_SECONDS=60
MARKET_LIMIT=20

MIN_VOLUME_USDC=50000
MIN_AI_PROBABILITY=0.65
MIN_AI_CONFIDENCE=0.70
MIN_EDGE=0.15
MAX_SPREAD=0.04

# =========================
# Market Filter
# =========================
ALLOW_CATEGORIES=crypto,tech,macro
BLOCK_KEYWORDS=war,death,tragedy
```

重点配置是：

```env
TRADE_ENABLED=false
TRADE_AMOUNT_USDC=10
```

第一版默认必须是模拟盘：

```env
TRADE_ENABLED=false
```

等日志验证正常后，再手动改成：

```env
TRADE_ENABLED=true
```

---

# 六、核心模块设计

## 1. 配置模块 `config.py`

职责：

```text
1. 读取 .env
2. 转换数据类型
3. 校验必要参数
4. 提供统一 Config 对象
```

重点字段：

```text
trade_enabled
trade_amount_usdc
max_daily_orders
max_daily_spend_usdc
ai_provider
ai_model
min_edge
min_ai_confidence
min_volume_usdc
```

---

## 2. 市场数据模块 `market_service.py`

职责：

```text
1. 拉取活跃市场
2. 获取市场标题、描述、结束时间、成交量
3. 获取 YES / NO token_id
4. 获取订单簿
5. 计算 best_bid、best_ask、spread
```

数据来源：

```text
Gamma API：获取市场列表、问题、描述、成交量
CLOB API：获取订单簿、价格、盘口
```

官方文档说明 CLOB 的公开端点支持订单簿、价格、价差等读取数据，不需要认证；交易端点才需要认证。([Polymarket 文档][5])

---

## 3. AI 模型模块 `ai_provider.py`

职责：

```text
1. 根据配置选择模型供应商
2. 构造统一 prompt
3. 调用 AI
4. 强制 AI 返回 JSON
5. 校验 AI 返回结果
```

统一返回格式：

```json
{
  "estimated_probability": 0.68,
  "confidence": 0.74,
  "recommended_side": "YES",
  "should_trade": true,
  "reason": "市场价格低于模型估计概率，事件规则较清晰。",
  "risk_level": "medium"
}
```

支持供应商：

```text
openai
deepseek
qwen
custom_openai_compatible
```

这个模块的关键原则是：

```text
交易逻辑不关心你用哪个模型。
所有模型必须返回统一 JSON。
```

---

## 4. 策略模块 `strategy.py`

职责：

```text
1. 根据 AI 结果判断方向
2. 计算市场隐含概率
3. 计算 AI edge
```

核心逻辑：

```text
如果 AI 推荐 YES：
  edge = AI 估计 YES 概率 - YES 当前买入价格

如果 AI 推荐 NO：
  edge = AI 估计 NO 概率 - NO 当前买入价格
  AI 估计 NO 概率 = 1 - AI 估计 YES 概率
```

例子：

```text
YES 当前价格 = 0.42
AI 估计 YES 概率 = 0.68

edge = 0.68 - 0.42 = 0.26
```

只有当：

```text
edge >= MIN_EDGE
```

才有交易价值。

---

## 5. 风控模块 `risk_control.py`

职责：

```text
1. 判断 AI 置信度是否足够
2. 判断 edge 是否足够
3. 判断市场成交量是否足够
4. 判断盘口价差是否过大
5. 判断每日订单数量是否超限
6. 判断每日累计交易金额是否超限
7. 判断单市场持仓是否超限
8. 判断是否启用实盘交易
```

建议第一版规则：

```text
AI confidence >= 0.70
edge >= 0.15
market volume >= 50000 USDC
spread <= 0.04
daily orders <= 5
daily spend <= 50 USDC
single market position <= 30 USDC
```

风控模块必须独立于 AI。

也就是说：

```text
AI 可以建议交易
但是否真正下单由代码决定
```

不要让 AI 直接控制下单金额。

---

## 6. 下单模块 `order_service.py`

职责：

```text
1. 初始化 Polymarket CLOB Client
2. 根据方向选择 YES / NO token_id
3. 使用配置金额下单
4. 提交订单
5. 返回订单结果
6. 写入订单日志
```

第一版建议只支持：

```text
BUY YES
BUY NO
```

暂时不做：

```text
卖出
止盈
止损
补仓
网格
马丁格尔
```

自动化下单建议优先使用 FAK 或 FOK 类型。Polymarket 官方文档说明，Market Orders 会使用 FOK 或 FAK 类型立即对盘口流动性成交；FAK 可以部分成交并取消剩余部分，FOK 则必须全部成交否则取消。([Polymarket 文档][6])

第一版建议：

```text
OrderType = FAK
```

原因：

```text
1. 避免订单长期挂在盘口
2. 避免 AI 信号过期后仍然成交
3. 允许部分成交
4. 自动化脚本更安全
```

---

## 7. 日志模块 `logger_service.py`

职责：

```text
1. 记录运行日志
2. 记录 AI 信号
3. 记录订单结果
4. 记录异常信息
5. 支持后续复盘
```

日志文件：

```text
logs/runtime.log
logs/signals.jsonl
logs/orders.jsonl
logs/errors.jsonl
```

信号日志示例：

```json
{
  "time": "2026-05-17T10:00:00Z",
  "market_id": "123",
  "question": "Will BTC hit $120k before June 30?",
  "yes_price": 0.42,
  "no_price": 0.58,
  "ai_probability": 0.68,
  "confidence": 0.74,
  "recommended_side": "YES",
  "edge": 0.26,
  "allowed": true,
  "trade_enabled": false,
  "status": "dry_run"
}
```

订单日志示例：

```json
{
  "time": "2026-05-17T10:01:00Z",
  "market_id": "123",
  "side": "YES",
  "amount_usdc": 10,
  "order_type": "FAK",
  "status": "submitted",
  "order_result": {}
}
```

---

# 七、自动交易判断逻辑

完整判断链路：

```text
AI 输出 should_trade=true
        ↓
recommended_side 不是 HOLD
        ↓
confidence >= MIN_AI_CONFIDENCE
        ↓
edge >= MIN_EDGE
        ↓
volume >= MIN_VOLUME_USDC
        ↓
spread <= MAX_SPREAD
        ↓
未超过每日订单数
        ↓
未超过每日交易金额
        ↓
TRADE_ENABLED=true
        ↓
自动下单
```

伪代码：

```python
if not ai_result["should_trade"]:
    skip()

if ai_result["recommended_side"] == "HOLD":
    skip()

if ai_result["confidence"] < config.min_ai_confidence:
    skip()

if edge < config.min_edge:
    skip()

if market.volume < config.min_volume_usdc:
    skip()

if spread > config.max_spread:
    skip()

if daily_spend + config.trade_amount_usdc > config.max_daily_spend_usdc:
    skip()

if config.trade_enabled:
    place_order()
else:
    log_dry_run()
```

---

# 八、开发阶段拆分

## 第 1 阶段：基础行情和配置

目标：

```text
1. 项目结构搭建
2. .env 配置读取
3. 拉取 Polymarket 活跃市场
4. 获取市场标题、成交量、结束时间
5. 获取 YES / NO token_id
6. 获取 YES / NO 当前盘口价格
```

验收标准：

```text
运行 main.py 后，可以打印出 20 个活跃市场：
- 问题
- 成交量
- YES 价格
- NO 价格
- spread
```

---

## 第 2 阶段：AI 自动分析

目标：

```text
1. 封装 AIProvider
2. 支持 openai / deepseek / qwen / custom_openai_compatible
3. AI 返回统一 JSON
4. 对 JSON 做 schema 校验
5. 记录 signals.jsonl
```

验收标准：

```text
每个市场都能生成 AI 分析结果：
- estimated_probability
- confidence
- recommended_side
- should_trade
- reason
- risk_level
```

---

## 第 3 阶段：策略和风控

目标：

```text
1. 计算 edge
2. 判断是否满足交易条件
3. 加入每日订单数限制
4. 加入每日交易金额限制
5. 加入成交量过滤
6. 加入 spread 过滤
```

验收标准：

```text
程序不会直接下单。
只会在 signals.jsonl 里记录：
- allowed=true
- allowed=false
- reject_reason
```

---

## 第 4 阶段：模拟盘运行

目标：

```text
1. TRADE_ENABLED=false
2. 完整跑通市场扫描、AI 分析、风控判断
3. 不提交真实订单
4. 记录 dry_run 信号
```

建议模拟盘至少跑：

```text
3 到 7 天
```

观察：

```text
1. AI 是否频繁误判
2. 是否经常选择低流动性市场
3. edge 是否合理
4. 风控是否过松
5. 日志是否足够复盘
```

---

## 第 5 阶段：实盘小金额下单

目标：

```text
1. TRADE_ENABLED=true
2. TRADE_AMOUNT_USDC=5
3. MAX_DAILY_ORDERS=3
4. MAX_DAILY_SPEND_USDC=15
5. 使用 FAK 下单
6. 记录 orders.jsonl
```

验收标准：

```text
符合风控条件时，程序能自动提交订单。
订单结果能写入 orders.jsonl。
失败订单能写入 errors.jsonl。
```

---

# 九、第一版推荐参数

保守版本：

```env
TRADE_ENABLED=false

TRADE_AMOUNT_USDC=5
MAX_DAILY_ORDERS=3
MAX_DAILY_SPEND_USDC=15
MAX_SINGLE_MARKET_POSITION_USDC=20

MIN_VOLUME_USDC=100000
MIN_AI_PROBABILITY=0.70
MIN_AI_CONFIDENCE=0.75
MIN_EDGE=0.20
MAX_SPREAD=0.03

MARKET_LIMIT=20
SCAN_INTERVAL_SECONDS=60
```

等模拟盘表现稳定后，再调整为：

```env
TRADE_ENABLED=true

TRADE_AMOUNT_USDC=10
MAX_DAILY_ORDERS=5
MAX_DAILY_SPEND_USDC=50

MIN_EDGE=0.15
MIN_AI_CONFIDENCE=0.70
```

---

# 十、MVP 不做的功能

第一版不要做这些：

```text
1. Web 后台
2. 数据库
3. 用户系统
4. 多账户管理
5. 自动止盈止损
6. 自动卖出
7. 复杂仓位管理
8. 高频 WebSocket 交易
9. 多市场套利
10. 回测系统
```

Polymarket 提供 WebSocket，可用于近实时订单簿、成交和用户订单活动；但第一版可以先用定时轮询，等基础自动下单稳定后再接 WebSocket。([Polymarket 文档][7])

---

# 十一、最终 MVP 能力清单

第一版完成后，程序应具备：

```text
1. Python 独立运行
2. 通过 .env 配置交易金额
3. 通过 .env 开关实盘 / 模拟盘
4. 自动拉取 Polymarket 活跃市场
5. 自动获取 YES / NO 当前价格
6. 自动调用 AI 分析事件概率
7. 支持切换不同 AI 供应商和模型
8. 自动计算 AI edge
9. 自动执行风控判断
10. 满足条件后自动下单
11. 自动记录信号日志
12. 自动记录订单日志
13. 自动记录错误日志
```

---

# 十二、核心结论

这个项目第一版可以按下面这个最小架构实现：

```text
main.py
  ↓
market_service.py
  ↓
ai_provider.py
  ↓
strategy.py
  ↓
risk_control.py
  ↓
order_service.py
  ↓
logger_service.py
```

它的核心不是“AI 觉得会发生就买”，而是：

```text
AI 估计概率 - 市场价格 >= 最小优势阈值
```

只有同时满足：

```text
AI 置信度足够
市场成交量足够
盘口价差合理
交易金额未超限
每日订单未超限
TRADE_ENABLED=true
```

才允许自动下单。

这样做出来的脚本才是一个可控的 **Polymarket AI 概率交易 MVP**，而不是一个完全不可控的 AI 自动下注程序。

[1]: https://docs.polymarket.com/api-reference/introduction?utm_source=chatgpt.com "Introduction - Polymarket Documentation"
[2]: https://docs.polymarket.com/api-reference/clients-sdks?utm_source=chatgpt.com "Clients & SDKs"
[3]: https://docs.polymarket.com/trading/orders/overview?utm_source=chatgpt.com "Overview - Polymarket Documentation"
[4]: https://docs.polymarket.com/trading/overview?utm_source=chatgpt.com "Overview - Polymarket Documentation"
[5]: https://docs.polymarket.com/cn/api-reference/introduction?utm_source=chatgpt.com "简介"
[6]: https://docs.polymarket.com/trading/orders/create?utm_source=chatgpt.com "Create Order - Polymarket Documentation"
[7]: https://docs.polymarket.com/market-data/websocket/overview?utm_source=chatgpt.com "Overview - Polymarket Documentation"
