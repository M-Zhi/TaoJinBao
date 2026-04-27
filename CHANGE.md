# 淘金宝（TaoJinBao）设计文档 & 迭代记录

> Android 黄金价格追踪与 AI 预测 App
> 仓库：https://github.com/M-Zhi/TaoJinBao

---

## 一、整体架构设计

### 1.1 架构模式

采用 **MVVM + Repository** 分层架构：

```
UI 层 (Fragment / Activity)
    ↕ LiveData / ViewBinding
ViewModel 层 (RealtimeViewModel / HistoryViewModel / DeviationViewModel / TradingViewModel)
    ↕ suspend fun / coroutine
Repository 层 (GoldRepository)
    ↕                    ↕
网络层 (OkHttp)       本地 DB 层 (Room)
新浪财经 API          PriceHistory / PredictionRecord
```

### 1.2 核心组件

| 组件 | 说明 |
|------|------|
| `GoldRepository` | 唯一数据源，封装网络请求与 DB 操作 |
| `Room Database` | 本地持久化，当前版本 v11，使用 `fallbackToDestructiveMigration()` |
| `OkHttp` | 网络请求客户端 |
| `Coroutines + LiveData` | 异步数据流与 UI 响应 |
| `ViewBinding` | 类型安全的 View 访问，替代 `findViewById` |
| `Navigation Component` | 底部导航栏 Tab 切换 |
| `MPAndroidChart` | 折线图渲染（7日走势 / 今日走势） |

### 1.3 数据模型

```kotlin
// 每日金价记录（统计基准）
@Entity(tableName = "price_history",
        indices = [Index(value = ["dateKey"], unique = true)])  // v11 新增唯一约束
data class PriceHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,      // 格式 "yyyy-MM-dd"
    val price: Double,        // 当日开盘价（CNY/克）
    val timestamp: Long
)

// AI 预测记录
@Entity(tableName = "prediction_record")
data class PredictionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val predictionDate: String,  // 被预测的日期（明天）
    val createdDate: String,     // 预测创建日期（今天）
    val predictedPrice: Double,
    val slope: Double,
    val intercept: Double
)
```

---

## 二、金价数据源设计

### 2.1 API 选型过程

在选型阶段对多个公开金价接口进行了沙箱实测：

| 接口 | 测试结果 | 原因 |
|------|----------|------|
| `hq.sinajs.cn/list=Au9999`（现货金） | ❌ 返回空字符串 | SGE 现货接口屏蔽非浏览器 UA |
| `push2.eastmoney.com` | ❌ HTTP 000 | 沙箱 IP 被 EastMoney 拒绝连接 |
| `sge.com.cn` 官网 | ❌ 触发反爬 HTML | 返回人机验证页面 |
| `qt.gtimg.cn` 腾讯财经 | ❌ 格式不稳定 | 字段布局与文档不符 |
| `hq.sinajs.cn/list=nf_AU0`（沪金连续） | ✅ 返回 1040.84 CNY/克 | 期货接口，稳定可用 |
| `hq.sinajs.cn/list=sh518880`（黄金 ETF） | ✅ 可用作备用 | ×100 换算为 CNY/克 |
| 新浪近月合约（动态符号） | ✅ 可用作最终兜底 | 动态发现近月合约代码 |

### 2.2 最终 API 链（三级链式兜底）

```
fetchGoldPriceSina()       → nf_AU0 沪金连续主力合约（首选）
    ↓ null（网络失败）
fetchGoldPriceEastMoney()  → sh518880 黄金ETF（备用）
    ↓ null
fetchGoldPriceSGE()        → 动态近月合约 nf_AuYYMM（最终兜底）
```

### 2.3 字段解析规则

**nf_AU0（沪金连续，GBK 编码）：**
```
var hq_str_nf_AU0="名称,代码,昨结算,今开盘,最低,?,最新价,买价,卖价,...,日期"
index:              [0]   [1]  [2]     [3]    [4]  [5] [6]    [7]  [8]
```
- `fields[3]` → 今开盘价（每日统计基准）
- `fields[6]` → 最新价（实时展示用）

**sh518880（黄金 ETF，1份=0.01克）：**
```
var hq_str_sh518880="名称,今开盘,昨收,当前价,最高,最低,..."
index:               [0]  [1]    [2]  [3]    [4]  [5]
```
- `fields[1] × 100` → 今开盘价（CNY/克）
- `fields[3] × 100` → 当前价（CNY/克）

### 2.4 开盘价 vs 实时价的架构分离

这是整个数据层最核心的设计决策：

```kotlin
// 返回 Pair(开盘价?, 实时价)
// openPrice = null 表示今日尚未开盘（盘前），不写 DB
private fun fetchGoldPriceSina(): Pair<Double?, Double>? {
    ...
    // 盘前 fields[3] = 0，返回 null 而非替代值
    Pair(if (openPrice > 100) openPrice else null, currentPrice)
}

suspend fun fetchAndSaveCurrentPrice(): Double? {
    val (openPrice, currentPrice) = result
    if (openPrice != null) {
        // INSERT IGNORE：只在当天无记录时写入，之后不更新
        // 保证每天统计基准唯一且固定
        priceDao.insertIgnore(PriceHistory(dateKey, openPrice, ...))
    }
    return currentPrice  // 实时价只用于首页展示，不存 DB
}
```

**设计原则：**
- **开盘价** = 每日统计基准，写入 DB 一次，不可更新，用于历史图表和预测基线
- **实时价** = 用于首页大字展示，纯内存，不持久化
- **盘前** = `openPrice` 返回 `null`，跳过 DB 写入，防止脏数据污染统计序列

---

## 三、四大功能模块设计

### 3.1 实时行情页（RealtimeFragment）

**核心功能：**
- 实时价格大字展示（30 秒自动刷新）
- 今日走势迷你折线图（每次刷新追加一个时间点）
- 近 7 日走势图 + AI 预测明日值（虚线延伸）
- 市场状态标签（交易中 / 休市中 / 网络异常）
- AI 预测标签显示具体日期而非"明天"

**关键实现：**
```kotlin
// 预测标签显示具体日期
binding.tvPredLabel.text = "AI 预测（${DateUtils.formatDate(DateUtils.daysFromNowKey(1))}）"

// 今日走势图：避免重复时间点
if (todayPricePoints.isEmpty() || todayPricePoints.last().first != nowLabel) {
    todayPricePoints.add(nowLabel to price)
} else {
    todayPricePoints[todayPricePoints.lastIndex] = nowLabel to price
}
```

### 3.2 历史价格页（HistoryFragment）

- 展示所有历史开盘价记录（Room DB 查询）
- 可视化折线图展示价格趋势
- 日期 → 价格列表

### 3.3 预测回看页（DeviationFragment）

**预测记录的语义设计：**

```
predictionDate = 被预测的日期（明天）
createdDate    = 预测创建于今天
```

**展示逻辑：**
- 只展示 `predictionDate` 对应的实际价格已入库的记录（即已到期的预测）
- 实时比对预测值 vs 实际开盘价，计算偏差率
- 新安装时无历史记录，不展示任何数据（杜绝"假数据"问题）

**去重机制：**
```kotlin
// 每天只生成一条预测，以 createdDate 去重
val existing = predictionDao.getPredictionByCreatedDate(todayKey)
if (existing != null) return@withContext  // 今日已预测，跳过
```

### 3.4 交易策略页（TradingFragment）

- 基于近期价格走势给出买入/持有/卖出建议
- 展示技术指标（移动均线、趋势方向）

---

## 四、AI 预测算法

### 4.1 线性回归预测

```kotlin
// 取近 N 天历史开盘价做线性回归
// y = slope × x + intercept
// 预测第 N+1 天的开盘价
fun computeAndSavePrediction() {
    val prices = priceDao.getRecentPrices(PREDICTION_WINDOW)
    val (slope, intercept) = linearRegression(prices)
    val predictedPrice = slope * (prices.size) + intercept
    ...
}
```

### 4.2 预测可靠性说明

- 基于历史线性趋势外推，仅供参考
- 金价受宏观经济、美联储政策、地缘政治等复杂因素影响
- 预测回看功能便于用户评估模型准确率

---

## 五、DB 版本迭代

| DB 版本 | 主要变更 |
|---------|----------|
| v1 ~ v8 | 初始建表，基础字段 |
| v9 | 新增 `PredictionRecord` 表 |
| v10 | 预测字段调整 |
| v11（当前）| `PriceHistory` 新增 `dateKey` 唯一索引；修正预测语义（`predictionDate`=明天，`createdDate`=今天）；`insertIgnore` 替代 `insertOrUpdate` |

> 版本升级策略：`fallbackToDestructiveMigration()` —— 清空旧数据重建，确保 Schema 干净

---

## 六、版本迭代记录

### v1 ~ v7（初始开发阶段）
- 项目搭建，MVVM 架构确立
- 基础 UI 布局，四 Tab 导航
- Room DB 初始设计
- MPAndroidChart 折线图集成

### v8（首个可用版本）
- 基本金价展示功能完成
- 接入新浪财经 API（`Au9999` 现货接口，后发现不稳定）
- 历史价格列表

### v9
- 新增 AI 预测功能（线性回归）
- 新增预测回看 Tab
- 修复若干 UI 显示问题

### v10
- 接口调整：`Au9999` → `nf_AU0`（沪金连续，稳定可用）
- 修复价格数量级错误（ETF 需 ×100 换算）

### v11
- 新增今日走势迷你图
- 7 日走势图 + AI 预测虚线延伸点
- 交易策略页完善

### v12（API 统一化重构）
- **问题**：代码中存在多处金价获取逻辑，不同模块使用不同接口，导致数据不一致
- **修复**：所有金价来源统一走 `GoldRepository` 单一链路
- 三级链式兜底：`nf_AU0` → `sh518880` → 近月合约

### v13（开盘价架构 + 多项 Bug 修复）

**Bug 1：7 日图全显示同一日期**
- **根因**：`PriceHistory` 使用自增 `id` 做主键，`insertOrUpdate(id=0)` 每次都插入新行而非更新；DB 积累了大量同日期重复记录；`takeLast(7)` 取出的 7 条全是今天
- **修复**：`dateKey` 加唯一约束（`@Index unique=true`）+ 改用 `insertIgnore`

**Bug 2：ETF 接口使用错误字段**
- **根因**：`sh518880` 代码误用 `fields[5]`（当日最低价）作为开盘价
- **修复**：`fields[1]`=今开盘，`fields[3]`=当前价

**Bug 3：预测回看安装后立即有数据**
- **根因**：`predictionDate = todayKey`，种子数据里有今天的价格，`DeviationViewModel` 立即找到匹配
- **修复**：`predictionDate = tomorrowKey`，只有明天到来并记录实际开盘价后才显示

**核心架构升级：开盘价作为每日统计基准**
- 统一以每日**开盘价**作为历史统计和预测的数据基准（金价日内波动较大，以开盘价为锚点保证可比性）
- `fetchAndSaveCurrentPrice()` 返回类型从 `Double?` 升级为分离设计：DB 存开盘价，返回实时价
- `DateUtils` 新增 `daysFromNowKey(days: Int)` 工具方法

**UI 改进**
- 预测标签从"AI 预测（明天）"改为"AI 预测（具体日期）"，如"AI 预测（4月28日）"

### v14（盘前保护 + 数据一致性加固）

**问题：盘前打开 App 会写入脏数据**
- **场景**：上期所开盘时间 09:00，若用户在 09:00 前打开 App，`fields[3]`（今开盘）尚未赋值，值为 0
- **旧逻辑**：`openPrice = 0` 时降级使用 `currentPrice` 作为开盘价写入 DB
- **问题**：盘前竞价价格 ≠ 09:00 开盘价，污染历史统计序列

**修复方案：**
```kotlin
// 三个 fetch 函数返回类型变更
// 旧：Pair<Double, Double>?   (openPrice, currentPrice)
// 新：Pair<Double?, Double>?  (openPrice?, currentPrice)
//     openPrice=null 表示盘前，调用方不写 DB

Pair(if (openPrice > 100) openPrice else null, currentPrice)
//                                      ^^^^
//                              盘前返回 null，不再用 currentPrice 替代
```

**DB 写入保护：**
```kotlin
if (openPrice != null) {
    priceDao.insertIgnore(...)  // 只有开盘价有效时才写入
}
// openPrice=null 时：仅展示实时价，不污染 DB
```

**用户体验：**
- ViewModel 的休市判断逻辑（`TradingHoursUtils`）保持不变，盘前显示"休市中"
- 盘前仍可查看实时竞价价格，但不计入历史统计

---

## 七、关键设计决策总结

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 金价接口 | 新浪财经期货接口 `nf_AU0` | 唯一通过实测的稳定接口，无反爬限制 |
| 统计基准 | 每日开盘价 | 日内波动大，开盘价可比性最强，且全天可获取 |
| DB 更新策略 | INSERT IGNORE（不 UPDATE）| 保证每日开盘价记录唯一且不可篡改 |
| 盘前处理 | `openPrice=null`，跳过 DB 写入 | 防止盘前竞价数据污染历史统计序列 |
| 预测语义 | `predictionDate`=明天，`createdDate`=今天 | 与"实际价到来后对比"的业务逻辑严格一致 |
| DB 迁移策略 | `fallbackToDestructiveMigration` | 开发阶段 Schema 变更频繁，清空重建代价小 |
| ViewModel 共享 | 绑定到 Activity 而非 Fragment | 切换 Tab 时 ViewModel 不重建，避免图表闪烁 |

---

## 八、待优化事项

- [x] 盘前保护：v14 已实现，盘前不写 DB，09:00 后首次打开自动写入真实开盘价，无需额外补录逻辑
- [ ] 节假日感知：`TradingHoursUtils` 目前仅判断周一至周五，不感知法定节假日。影响：节假日显示"网络异常"而非"休市中"（不影响数据准确性）；调休补班周六不会记录当日开盘价（历史图偶发缺点）。优先级低，可按需接入节假日日历接口
- [ ] 预测模型升级：线性回归可升级为 ARIMA 或移动平均 + 动量因子
- [ ] 数据持久化迁移策略：生产版本应使用 `Migration` 而非 `fallbackToDestructiveMigration`
- [ ] 单元测试：`GoldRepository` 核心逻辑、`DateUtils`、预测算法缺乏测试覆盖
- [ ] 多品种支持：目前仅跟踪沪金，可扩展至白银、铂金等品种
