# GoldPrice Android App — 完整设计文档

> 版本：v7（2026-04-26）
> 作者：hejiangtao03
> APK 下载：[GoldPriceApp_v7.apk](https://dwz.cn/Vfqair5T)
> 源码包：[GoldPriceApp_v7_source.zip](https://dwz.cn/dQ6Exmkj)

---

## 一、项目概述

GoldPrice 是一款 Android 原生黄金价格追踪与模拟投资 App，面向黄金投资爱好者设计。核心功能包括：

- 实时黄金（Au99.99）价格获取（上海黄金交易所）
- 历史价格走势查看与大盘对比
- AI 智能价格预测（Holt 双指数平滑算法）
- 多账户模拟操盘（AI 策略 + 手动）
- 预测偏差回顾与算法准确率分析
- DCA（定期定额）投资回报对比（黄金 vs 沪深300 vs 纳斯达克）

### 技术规格
| 项目 | 值 |
|------|----|
| 语言 | Kotlin |
| 最低 SDK | API 26 (Android 8.0) |
| 目标 SDK | API 34 (Android 14) |
| 包名 | `com.goldprice.app` |
| 构建工具 | Gradle 7.5 |
| 架构模式 | MVVM + Repository |

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────┐
│                     UI 层                            │
│  RealtimeFragment  HistoryFragment  TradingFragment  │
│  DeviationFragment                                   │
│         ↕ LiveData / ViewBinding                     │
├─────────────────────────────────────────────────────┤
│                  ViewModel 层                        │
│  RealtimeViewModel  HistoryViewModel                 │
│  TradingViewModel   DeviationViewModel               │
│         ↕ suspend functions / coroutines             │
├─────────────────────────────────────────────────────┤
│                 Repository 层                        │
│                GoldRepository                        │
│    网络请求(OkHttp)    本地持久化(Room DB)            │
│         ↕                    ↕                       │
│  SGE API / Fallback     AppDatabase v8               │
└─────────────────────────────────────────────────────┘
```

### 分层职责

| 层 | 类 | 职责 |
|---|---|---|
| UI | `RealtimeFragment` 等 4 个 Fragment | 视图渲染、用户交互 |
| ViewModel | 4 个 ViewModel | 业务逻辑、LiveData 暴露 |
| Repository | `GoldRepository` | 统一数据入口，协调网络 + 本地 DB |
| 数据库 | `AppDatabase (Room v8)` | 持久化 8 张表 |
| 工具 | `PredictionUtils`, `TradingHoursUtils`, `DateUtils` | 算法、时段、日期 |

---

## 三、数据层设计

### 3.1 Room 数据库（AppDatabase v8）

共 8 张表：

| 表名 | 实体类 | 说明 |
|------|--------|------|
| `price_history` | `PriceHistory` | 黄金日收盘价，dateKey 唯一索引 |
| `prediction_record` | `PredictionRecord` | AI 预测记录，predictionDate 唯一索引（防重复） |
| `account` | `Account` | 模拟账户（AI / MANUAL），含颜色字段 |
| `trade_record_v2` | `TradeRecordV2` | 交易流水，含 notes 备注字段 |
| `trade_record` | `TradeRecord` | 旧版交易记录（兼容保留） |
| `portfolio` | `Portfolio` | 旧版持仓（兼容保留） |
| `csi300_history` | `Csi300History` | 沪深300历史收盘点位 |
| `nasdaq_history` | `NasdaqHistory` | 纳斯达克（QQQ ETF）历史价格 |

#### Account 实体关键字段
```kotlin
data class Account(
    val id: Long,
    val name: String,
    val type: String,           // "AI" 或 "MANUAL"
    val cashBalance: Double,    // 可用现金
    val holdingGrams: Double,   // 持仓克数
    val avgCostPrice: Double,   // 平均持仓成本
    val color: String,          // 账户主题色 HEX
    val isDefault: Boolean
)
```

#### PredictionRecord 唯一约束
`predictionDate` 加了 `@Index(unique=true)`，配合 Repository 层的"当天已有记录则直接返回"逻辑，确保每个交易日最多写入 1 条预测，切换页面不会重复计数。

### 3.2 种子数据（HistoricalDataSeeder）

初次安装自动写入 3 组真实历史数据（akshare 实时拉取后硬编码）：

| 数据集 | 来源 | 记录数 | 时间范围 |
|--------|------|--------|----------|
| 黄金 Au99.99 | akshare `spot_hist_sge()` | 801 条 | 2023-01-03 ~ 2026-04-24 |
| 沪深300 | akshare `stock_zh_index_daily('sh000300')` | 800 条 | 同上 |
| 纳斯达克(QQQ ETF) | akshare `stock_us_daily('QQQ')` | 830 条 | 同上 |

---

## 四、网络层

### 4.1 实时金价 API

**主接口：上海黄金交易所（SGE）**
```
POST https://www.sge.com.cn/graph/quotations
Body: instid=Au99.99
Headers: User-Agent, Referer: https://www.sge.com.cn/
Response: {"code":0,"data":[{"price":"1033.25",...}]}
```

**备用接口（Fallback）**：goldprice.org/CNY（主接口失败时降级）

### 4.2 刷新策略

| 市场状态 | 刷新间隔 |
|----------|---------|
| 开市（交易中） | 30 秒 |
| 休市 | 5 分钟 |

**上海黄金交易所交易时段**（Asia/Shanghai）：
- 日盘：周一至周五 09:00 ~ 15:30
- 夜盘：周一至周四 20:00 ~ 次日 02:30（周五无夜盘）

---

## 五、UI 功能模块

### 5.1 首页 — 实时行情

**布局**：`fragment_realtime.xml`，`RealtimeFragment` + `RealtimeViewModel`

**功能要点**：
- 顶部显示当前金价（大字）+ 涨跌幅（绿涨红跌）+ 市场状态标签
- 下拉刷新（SwipeRefreshLayout），每次进入页面自动刷新
- **7日走势图**（MPAndroidChart LineChart）
  - X 轴标签在图表下方
  - 金色实线：近 7 天真实收盘价
  - 紫色虚线（`enableDashedLine`）：AI 预测明日点，图例标注"AI预测（明日）"
  - 点击任意数据点弹出气泡（日期 + 金价）
  - 首次加载播 500ms 入场动画；切换 Tab 回来仅 invalidate，不重复动画
- **今日实时行情子图**（蓝色折线）
  - 每次轮询到新价格自动追加时间点
  - 数据不足 2 点时显示"等待更多数据..."提示
  - 点击显示时间 + 价格气泡
- **AI 预测区域**：仅显示"明日预测价格"（1天），不展示2天/3天
- **DCA 对比**：3个面板（沪深300 / 纳斯达克 / 黄金），显示近3年月定投¥2000的收益率

### 5.2 历史走势

**布局**：`fragment_history.xml`，`HistoryFragment` + `HistoryViewModel`

**功能要点**：
- 时间段切换按钮：近3个月 / 近6个月 / 近1年 / 全部，高亮当前选中
- 统计数据：最高价、最低价、区间涨跌幅、振幅
- **叠加大盘（沪深300）**：双 Y 轴模式
  - 左轴金色：黄金价格
  - 右轴蓝色虚线：沪深300（按日期对齐）
  - 叠加状态下点击图表，气泡同时显示：金价（金色）+ 沪深300（蓝色）— `DualChartMarkerView`
  - 关闭叠加时先清除高亮（防 crash），再重建图表
- 点击任意数据点弹出气泡（日期 + 价格）

### 5.3 模拟操盘

**布局**：`fragment_trading.xml`（CoordinatorLayout + FAB），`TradingFragment` + `TradingViewModel`（AndroidViewModel）

**功能要点**：
- **多账户卡片**（动态构建 `item_account_card.xml`）
  - 顶部色条（账户主题色）
  - 账户名 + 类型徽章（AI / 手动）
  - 编辑（线框图标）/ 展开（chevron）/ 删除（trash outline）按钮
  - 展开后：现金余额、持仓克数、当前价值、盈亏（含百分比，红绿色）
  - 买入 / 卖出 / 重置 操作按钮
  - AI 账户展示当前策略参数
- **新建账户**：右下角 FAB（+）点击弹出对话框
  - 填写账户名
  - 颜色选择行（7色）支持横向滑动（`HorizontalScrollView` 包裹）
- **编辑账户**：名称 + 颜色均可修改，颜色选择同样支持滑动
- **AI 策略参数**（SharedPreferences 持久化，重启不丢失）：
  - 买入阈值（% 预测涨幅触发买入）
  - 仓位上限（% 最大仓位比）
  - 买入比例（% 现金买入）
  - 卖出阈值（% 预测跌幅触发卖出）
  - 卖出比例（% 持仓卖出）
  - 止损线（% 浮亏止损）
  - 支持两位小数精度输入（`%.2f`）
- **DCA 对比区域**：同首页，显示3年月定投收益对比

### 5.4 预测回顾

**布局**：`fragment_deviation.xml`，`DeviationFragment` + `DeviationViewModel`

**功能要点**：
- 统计卡片（4格）：预测总次数 / 方向准确率 / 平均误差% / 最大误差%
- **预测vs实际折线图**（近30条记录）：金色虚线=预测，绿色实线=实际
- **偏差分布柱状图**：蓝色=正偏差，红色=负偏差
- 两个图表均支持点击气泡（日期 + 数值）
- 底部算法说明标签（蓝色，带 ⓘ）：**点击弹出详细算法说明弹窗**
  - 包含：核心思路、完整算法公式（Holt ES）、优缺点、准确率说明

---

## 六、预测算法：Holt 双指数平滑

### 原理
对比旧版线性加权移动平均，Holt 算法同时维护两个状态量：

| 状态量 | 含义 | 更新公式 |
|--------|------|---------|
| `L`（水平） | 当前价格中枢 | `Lₜ = α·pₜ + (1-α)·(Lₜ₋₁ + Tₜ₋₁)` |
| `T`（趋势） | 涨跌斜率 | `Tₜ = β·(Lₜ - Lₜ₋₁) + (1-β)·Tₜ₋₁` |

**多步预测（带阻尼）**：
```
预测(h步) = Lₙ + (φ¹ + φ² + … + φʰ) × Tₙ
```

### 超参数默认值
| 参数 | 值 | 作用 |
|------|-----|------|
| α | 0.3 | 价格平滑系数（越大越敏感） |
| β | 0.1 | 趋势平滑系数 |
| φ | 0.85 | 阻尼因子（防趋势无限外推） |
| 数据窗口 | 最近 60 天 | 避免远期历史引入噪声 |

### 准确率说明
**方向准确率**：当预测的涨/跌方向与次日实际走向一致时计为1次正确。

### 优缺点
- ✅ 趋势反转识别比 WMA 更及时
- ✅ 阻尼因子使远期预测合理收敛
- ✅ 纯本地运算，无需任何外部 API
- ❌ 无法预测黑天鹅/突发政策
- ❌ 震荡行情下误差仍较大

---

## 七、第三方依赖

| 库 | 版本 | 用途 |
|----|------|------|
| MPAndroidChart | v3.1.0 | 折线图、柱状图渲染 |
| Room | 2.5.2 | 本地 SQLite ORM |
| OkHttp3 | 4.11.0 | SGE/goldprice.org HTTP 请求 |
| Navigation Component | 2.6.0 | Fragment 导航 + 底部导航栏 |
| ViewModel / LiveData | 2.6.1 | MVVM 响应式架构 |
| Material Components | 1.9.0 | Material 3 主题 UI |
| SwipeRefreshLayout | 1.1.0 | 下拉刷新 |
| CoordinatorLayout | 1.2.0 | FAB + ScrollView 浮动布局 |
| Coroutines Android | 1.7.1 | 异步网络 / 数据库操作 |

---

## 八、版本演进记录

| 版本 | 主要变更 |
|------|---------|
| v1 | 初版：基础 4 Tab 导航框架 |
| v2 | 集成 akshare 真实历史数据（801条金价 + 800条CSI300 + 830条QQQ）；SGE 实时API；夜盘时段支持 |
| v3 | 重建完整多账户模拟操盘UI（可展开卡片、AI策略、FAB）；CSI300叠加开关；预测偏差Tab（折线+柱状图）；Nasdaq 替换为 QQQ ETF 免费接口 |
| v4 | 换App图标（金色金币科技风）；修复预测次数bug（唯一索引防重入）；主页AI预测简化为1天；主图X轴标签移到下方；今日实时行情子图；历史走势双值气泡；账户卡片按钮图标美化 |
| v5 | 修复叠加大盘关闭Marker闪退；颜色选择行横向滑动；AI策略参数SharedPreferences持久化；预测算法说明可点击弹窗 |
| v6 | 修复预测点首次不显示（prediction Observer触发重绘）；AI策略参数支持两位小数；升级预测算法为Holt双指数平滑 |
| v7 | 切换Tab主图不重绘（ViewModel绑定Activity级别，首次播动画后仅invalidate）；删除算法说明中"已升级"字样 |

---

## 九、源码目录结构

```
app/src/main/java/com/goldprice/app/
├── GoldPriceApp.kt              # Application 单例，初始化 DB + Repository
├── MainActivity.kt              # 底部导航 Activity
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt       # Room 数据库 v8，fallbackToDestructiveMigration
│   │   └── Daos.kt              # 8 个 DAO 接口
│   ├── model/
│   │   └── Models.kt            # 8 个 Room Entity 数据类
│   ├── repository/
│   │   └── GoldRepository.kt    # 统一数据仓库（网络 + DB + AI策略）
│   └── seeder/
│       └── HistoricalDataSeeder.kt  # 3组历史数据（801+800+830条）
├── ui/
│   ├── common/
│   │   ├── ChartMarkerView.kt   # 图表单值点击气泡
│   │   └── DualChartMarkerView.kt # 图表双值气泡（叠加大盘用）
│   ├── realtime/
│   │   ├── RealtimeFragment.kt
│   │   └── RealtimeViewModel.kt
│   ├── history/
│   │   ├── HistoryFragment.kt
│   │   └── HistoryViewModel.kt
│   ├── trading/
│   │   ├── TradingFragment.kt
│   │   └── TradingViewModel.kt  # AndroidViewModel，SharedPrefs持久化策略参数
│   └── deviation/
│       ├── DeviationFragment.kt
│       └── DeviationViewModel.kt
└── utils/
    └── Utils.kt                 # DateUtils + TradingHoursUtils + PredictionUtils(Holt ES)
```

---

## 十、关键设计决策

### 数据持久化策略
- **历史价格**：Room DB，首次安装时由 `HistoricalDataSeeder` 写入真实数据，后续由 SGE API 追加
- **账户数据**：Room DB `account` 表，操作立即持久化
- **AI策略参数**：`SharedPreferences("ai_strategy_params")`，Key-Value 存储 6 个浮点参数
- **预测记录**：Room `prediction_record` 表，`predictionDate` 唯一索引防重入

### 防 Crash 设计
- 历史走势：切换大盘叠加前调用 `highlightValues(null)` 清除高亮，防旧 Marker 在新数据上越界
- `updateChart()` 开头同样 clear highlight，防时间段切换时 crash

### ViewModel 绑定策略
- `RealtimeViewModel` 绑定 `requireActivity()` 级别，切换 Tab 时 ViewModel 不销毁，不触发重复数据加载和图表重绘
- 其他页面 ViewModel 绑定 Fragment 级别（各自独立）

### 图表气泡
- `ChartMarkerView`：单值，接受 labels 列表 + 前缀/后缀，通用于所有单条折线图
- `DualChartMarkerView`：双值，历史走势叠加大盘时使用，分行显示金价（金色）+ 沪深300（蓝色）
