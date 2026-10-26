# MASTER Design System — Kinetic Dark & Acid Lime

> 本文件是本项目前端唯一的视觉真理来源（Single Source of Truth）。
> 任何页面在实现前先读本文件；页面级差异写入 `design-system/pages/<page>.md` 覆盖 Master。
>
> 设计方法：先由 `ui-ux-pro-max` 检索设计系统与图表/无障碍规则，再由 `frontend-design` 做「反模板化」审查。
> 产品的核心矛盾是 **一个把身体当成仪表来读的工具**：它必须像运动科学实验室的仪器面板（精确、冷静、数据密度高），
> 而不是通用 SaaS 后台。所有决策服务于「一眼读到负荷、容量、缺口、风险」。

---

## 1. 设计定位

| 维度 | 决策 |
|---|---|
| 产品类型 | 个人 AI 健身系统 · 训练/营养/伤病数字孪生控制台 |
| 使用者 | 认真训练、看得懂 RPE 与容量的进阶健身者（不是零基础打卡用户） |
| 核心任务 | 3 秒内回答：今天还差多少热量？这周练到位了吗？该加重还是该减载？哪个部位被伤病锁住了？ |
| 视觉隐喻 | 运动科学仪器面板（measurement instrument），不是内容卡片流 |
| 气质关键词 | Kinetic（动态）、Instrumented（可测量）、Evidence-based（循证）、Dark-first |
| 明确拒绝 | ElementPlus 传统后台风、蓝紫渐变、Emoji 当图标、同一圆角的 SaaS 卡片套件、大面积装饰性渐变 |

---

## 2. 色彩系统（Dark Mode First）

### 2.1 背景层级（三层景深，靠边框而非阴影分层）

| Token | Hex | 用途 |
|---|---|---|
| `--bg-obsidian` | `#090A0F` | 页面主背景（深邃曜石黑） |
| `--bg-surface` | `#12151E` | Bento 卡片 / 次级容器（毛玻璃基底） |
| `--bg-elevated` | `#1A1F2E` | 悬浮、激活、hover 高亮态 |

卡片分层规则：`background: rgba(18,21,30,.72)` + `backdrop-filter: blur(12px)` + `1px solid rgba(255,255,255,.07)`。
**不使用彩色阴影**；层级由「边框亮度 + 背景明度」表达，避免暗色界面上阴影不可见又互相叠脏。

### 2.2 品牌色与语义色

| Token | Hex | 语义 | 使用纪律（Important） |
|---|---|---|---|
| `--acid-lime` | `#CCFF00` | 品牌主色 / 活力荧光酸橙绿 | **仅用于**：主 CTA 按钮、1RM 突破高亮、主导进度环填充。其余地方一律不用，保持它的冲击力 |
| `--acid-lime-dim` | `rgba(204,255,0,.14)` | 酸橙绿低透明底 | 徽章底、进度环轨道、聚焦光环 |
| `--signal-orange` | `#FF5E36` | 力量 / 高碳水活力橙 | 数据编码专用：容量柱、力量曲线、HIGH_CARB 标签 |
| `--signal-cyan` | `#00E5FF` | 蛋白质 / 恢复电光蓝 | 数据编码专用：蛋白质胶囊、恢复/减载状态 |
| `--signal-coral` | `#FF3366` | 伤病预警 / 护栏拦截珊瑚红 | 数据编码专用：ACTIVE 伤病、GUARDRAIL_BLOCKED、错误 |
| `--signal-violet` | `#A78BFA` | 碳水（第四营养素，唯一非用户指定的补充色） | 仅用于碳水胶囊，避免与橙/蓝/珊瑚抢语义 |
| `--text-primary` | `#F4F6FA` | 主文本 | — |
| `--text-secondary` | `#9BA3B4` | 次级文本、单位、轴标签 | 对 `#12151E` 对比度 ≈ 7.2:1 ✓ |
| `--text-muted` | `#5C6478` | 三级文本、禁用 | 仅用于非关键信息 |
| `--hairline` | `rgba(255,255,255,.07)` | 细分隔线 / 卡片边框 | 结构性信息，不是装饰 |

**对比度验收**：酸橙绿按钮必须用 `#090A0F` 文字（`#CCFF00` 上黑字 ≈ 16:1）；珊瑚红/橙/蓝均只用于 12px 以上的标签或图形，若用作正文需加暗底。

### 2.3 数据色板（图表专用，按语义固定，不随图表类型漂移）

`LEGS/BACK → #FF5E36`（力量橙）｜`CHEST/SHOULDERS → #00E5FF`（电光蓝）｜`ARMS/CORE → #CCFF00`（酸橙）｜`目标线 → rgba(255,255,255,.32)` 虚线｜`伤病/拦截 → #FF3366`。
同一语义在全站必须同色：`LOWER_BACK` 在雷达图、伤病矩阵、审计台里都是珊瑚红。

---

## 3. 排版（Typography）

### 3.1 字体角色（两种族，宽度轴差异明显 —— 刻意区分，不做「同一无衬线两种字重」的偷懒方案）

| 角色 | 字体 | 用途 | 理由 |
|---|---|---|---|
| **读数体 Display Numeric** | `Barlow Condensed` 500/600/700 | 所有大数字：1RM、kg、kcal、组数、BMR/TDEE、百分比 | 窄体数字是记分牌/计时板的体育语汇，能在小宽度里塞进大数字，天然「仪器感」 |
| **界面体 UI Sans** | `Outfit` 300/400/500/600 | 标题、正文、按钮、标签、表单 | 几何无衬线，字腔干净，与窄体读数形成明确的宽度对比 |
| 等宽（可选） | `ui-monospace` 系统栈 | 审计日志的 args/result、时间戳 | 仅用于代码/JSON 片段，不用于普通数据 |

引入方式（`index.html` 预连接 + 单次请求）：
`https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@500;600;700&family=Outfit:wght@300;400;500;600&display=swap`

### 3.2 字号阶梯（Dashboard 场景，density=8/10 偏紧凑）

| 用途 | Tailwind | 备注 |
|---|---|---|
| Hero 读数（卡路里环中心） | `text-5xl` (48px) | Barlow Condensed 600 + `tabular-nums` |
| 卡片主数字 | `text-3xl` (30px) | Barlow Condensed 600 |
| 卡片标题 | `text-sm` (14px) / Outfit 500 | 不使用 ALL-CAPS + 字距的「eyebrow」套路 |
| 正文 / 表格 | `text-sm` (14px)，line-height 1.5 | — |
| 单位与轴标签 | `text-xs` (12px) | 最小可读下限，不再往下 |
| 徽章 | `text-xs` (12px) | — |

### 3.3 数字排版规则（强制）

1. **所有数值必须开启** `font-variant-numeric: tabular-nums`（等宽数字），否则刷新数据时数字会左右跳动 —— 这是仪表盘的专业度底线。
2. 单位（`kg` / `kcal` / `g` / `组`）一律用小一号、`--text-secondary`，紧贴数字右下，**不用括号包裹**。
3. 大数字字距收紧：`letter-spacing: -0.02em`（Barlow Condensed 700 时可用 `-0.03em`）。
4. 变化量用带符号的紧凑写法：`+2.5kg`（酸橙绿） / `−10%`（电光蓝，减载）。

---

## 4. 布局架构（Bento Grid + 常驻 AI 副驾驶）

### 4.1 三区骨架

```
┌────┬───────────────────────────────────────────────┬──────────────────┐
│    │  TopBar: 当前用户 · 活跃伤病胶囊 · 数据新鲜度 · [一键播种演示数据] │
│ N  ├───────────────────────────────────────────────┼──────────────────┤
│ a  │                                               │                  │
│ v  │   BENTO GRID  主工作区（12 列 / 非对称）        │  AI Coach        │
│ 68 │                                               │  Copilot 舱      │
│ px │   ┌──────────────┐ ┌────────┐ ┌────────┐      │  （可折叠）      │
│    │   │  今日热量环   │ │本周完成 │ │身体引擎 │      │                  │
│    │   │  (2×2 hero)  │ └────────┘ └────────┘      │  流式回答区      │
│    │   │              │ ┌─────────────────────┐    │  ──────────      │
│    │   └──────────────┘ │ 双渐进雷达（宽卡 2×1）│    │  输入框          │
│    │   ┌───────────────────────────────┐ ┌──────┐ │                  │
│    │   │ 1RM 力量进阶（折线，宽 2×1）    │ │肌群  │ │  Step 8.2 实装   │
│    │   └───────────────────────────────┘ │玫瑰图│ │                  │
│    │   ┌───────────────────────────────┐ └──────┘ │                  │
│    │   │ 营养趋势堆叠柱 + 目标虚线（宽 3×1）      │ │                  │
│    │   └───────────────────────────────────────┘  │                  │
└────┴───────────────────────────────────────────────┴──────────────────┘
```

- **左导航**：68px 宽纯图标列（Lucide `stroke-width:1.75`），选中态 = 酸橙绿 2px 左侧标 + 图标转 `--text-primary`；hover 显示右侧浮出文字标签。图标按钮命中区 ≥44×44px。
- **Bento 非对称规则**：12 列栅格，卡片跨列只为「信息重要性」服务 —— hero 读数跨 4 列 × 2 行，宽图表跨 6~8 列，指标芯片跨 2 列。**禁止所有卡片等宽等高**（这是模板感的来源）。
- **卡片圆角分级（刻意的层级差异）**：hero 与宽图表 `rounded-2xl (16px)`；指标芯片 `rounded-xl (12px)`；徽章/胶囊 `rounded-full`。全站不存在「一个圆角用到底」。
- **间距**：卡片内 `p-5`，卡片间 `gap-3`（紧凑仪表盘），区块间 `gap-5`。移动端降为 1 列，`gap-3` 不变。
- **AI Copilot 舱**：桌面端常驻右侧 340px，可折叠为 56px 竖条；`xl` 以下折叠为右下角浮动按钮 + 抽屉。Step 8.1 交付的是可视骨架与交互闭环，流式对话在 8.2 接入 `/api/ai/chat/stream`。

### 4.2 响应式断点

`375 / 768 / 1024 / 1280 / 1440`：375 单列；768 两列；1024 十二列栅格启用（导航收为图标列）；1280 起 Copilot 常驻。**任何断点不得出现横向滚动。**

---

## 5. 组件规范

### 5.1 卡片（Bento Tile）
```
表面: rgba(18,21,30,.72)  +  backdrop-blur(12px)  +  1px rgba(255,255,255,.07)
hover: 边框 → rgba(255,255,255,.14)，表面 → rgba(26,31,46,.78)，transform: translateY(-2px)
过渡: 180ms cubic-bezier(.22,.61,.36,1)   ← 只做「抬起 + 边框提亮」，不做缩放/发光
```
可点击卡片必须有 `cursor-pointer`、`:focus-visible` 酸橙绿 2px 光环（`outline-offset:2px`）。

### 5.2 按钮
- **主 CTA**：酸橙绿实心 + `#090A0F` 文字 + `rounded-full`，hover 提亮至 `#D9FF3D`，active 轻微下压 1px。
- **次按钮**：透明底 + 1px hairline 边框 + `--text-secondary` 文字。
- **危险/状态操作**：珊瑚红描边（不填充），避免大面积红造成警报疲劳。
- 所有按钮文案使用「动词 + 对象」：`采纳建议`、`保存档案`、`播种演示数据`（不用「提交」）。

### 5.3 徽章与状态
- 伤病：`ACTIVE` = 珊瑚红描边 + 呼吸灯（2.4s 深呼吸，`opacity .55→1`，`prefers-reduced-motion` 下静止）；`RECOVERED` = hairline 中立灰。
- 进阶状态：`OVERLOAD_READY` 酸橙绿、`MAINTAIN` 电光蓝、`DELOAD_NEEDED` 珊瑚红、`INSUFFICIENT_DATA` 中立灰。
- 徽章更新使用 `role="status" aria-atomic="true"` 且播报有意义短语（如「已标记 LEFT_SHOULDER 为恢复」），**不播报裸数字**。
- 图标一律 Lucide SVG；**不使用 Emoji**（`⚠️` → `<AlertTriangle />`），Emoji 在不同平台字形与基线都不受控。

### 5.4 图表主题（ECharts 暗黑定制）
- 去网格噪点：`splitLine` 用 `rgba(255,255,255,.05)` 虚线且仅保留 Y 轴；X 轴无轴线、无刻度。
- 面积曲线：`linear` 渐变 `rgba(255,94,54,.28) → rgba(255,94,54,0)`；`smooth: .35`；`symbol: circle, symbolSize: 6, showSymbol: false`（hover 才显）。
- Tooltip：玻璃拟态 —— `rgba(10,12,18,.92)` 底、`blur`、hairline 边框、`rounded-xl`、数字用 `tabular-nums`，`axisPointer` 用酸橙绿细线。
- 目标线：白色 32% 虚线 + 末端标签（「目标 3033」），不被柱子遮挡。
- 玫瑰图/雷达图的图例必须带数值，**不能只靠颜色区分**（无障碍要求）。

### 5.5 数据新鲜度（Data Freshness）
每个数据区块右上角显示 `更新于 HH:mm`；失败时显示 `数据获取失败 · 重试`。演示数据被播种后显示 `演示数据 · 已播种 14 天`。**不允许界面在无数据时假装有数据。**

---

## 6. 动效（Motion）

**唯一编排时刻（one orchestrated moment）**：首屏加载时，热量环从 0 扫到目标比例（900ms `cubic-bezier(.16,1,.3,1)`），同时 Bento 卡片以「由中心向外」的波浪错峰浮现（`scale .97 → 1` + `translateY 12px → 0`，每格 55ms，总时长 ≤650ms）。
- 除此之外，全站**只有**：卡片 hover 抬起（180ms）、按钮 active 下压、徽章呼吸灯、图表数据切换的 400ms 过渡。
- **不做**：每个区块的 fade-and-slide、滚动触发的逐段入场、装饰性视差、持续脉冲动画。
- 数值变更用 300ms 的数字滚动过渡（BMR/TDEE 重算时可见「跳动」）。
- `@media (prefers-reduced-motion: reduce)`：全部动画时长归零，直接渲染终态。

---

## 7. 无障碍清单（交付前逐条验收）

- [ ] 正文对比度 ≥4.5:1；次级文本 `#9BA3B4` 仅用于 ≥14px
- [ ] 所有可点击元素有 `cursor-pointer` + `:focus-visible` 可见光环（键盘可达）
- [ ] 图标按钮带 `aria-label`；纯装饰图标 `aria-hidden="true"`
- [ ] 进度环、玫瑰图旁必须有可见的数字/目标文本，不能只靠图形与颜色
- [ ] 实时更新的徽章/提示使用 `role="status" aria-atomic="true"`，聚焦不被打断
- [ ] 表单字段有可见 `<label>`，错误信息就近展示在字段旁
- [ ] 触控目标 ≥44×44px，相邻间距 ≥8px
- [ ] `prefers-reduced-motion` 被尊重
- [ ] 375 / 768 / 1024 / 1440 四档无横向滚动

---

## 8. 反模式（本项目明确不做）

1. 蓝紫渐变、玻璃拟态 + 彩色光晕堆叠的「AI 生成感」头图。
2. ElementPlus 式表格式后台：左侧深色菜单 + 白色内容区 + 蓝色主按钮。
3. 所有卡片同一圆角、同一阴影、等宽等高。
4. 每个标题上方加 ALL-CAPS 小字距「eyebrow」标签；中缀点拼接的元信息串（`A · B · C`）。
5. Emoji 当图标；图标按钮无标签。
6. 无数据时用 `0` 或假曲线填充；不显示数据时间与失败态。
7. 把酸橙绿当装饰色到处用 —— 它只服务 CTA、1RM 突破与主进度环。

---

## 9. Tailwind Token 映射（代码与文档必须一致）

```js
// tailwind.config.js — theme.extend
colors: {
  obsidian: '#090A0F', surface: '#12151E', elevated: '#1A1F2E',
  acid: { DEFAULT: '#CCFF00', bright: '#D9FF3D', dim: 'rgba(204,255,0,0.14)' },
  signal: { orange: '#FF5E36', cyan: '#00E5FF', coral: '#FF3366', violet: '#A78BFA' },
  ink: { primary: '#F4F6FA', secondary: '#9BA3B4', muted: '#5C6478' },
},
borderColor: { hairline: 'rgba(255,255,255,0.07)' },
fontFamily: {
  display: ['"Barlow Condensed"', 'system-ui', 'sans-serif'],
  sans: ['Outfit', 'system-ui', 'sans-serif'],
},
borderRadius: { tile: '16px', chip: '12px' },
transitionTimingFunction: { kinetic: 'cubic-bezier(.22,.61,.36,1)' },
```

CSS 变量同名落在 `src/styles/tokens.css`，供 ECharts 主题与内联 SVG 复用（JS 侧统一从 `src/theme/tokens.ts` 读取，**禁止在组件里写裸 hex**）。