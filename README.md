<div align="center">

# Personal AI Fitness System

**个人 AI 健身系统 —— LLM 负责对话与工具编排，Java 负责一切计算与写库**

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.22-1C3C3C)](https://github.com/langchain4j/langchain4j)
[![Vue](https://img.shields.io/badge/Vue-3.5-42B883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)

一个可落地、可演示、带**硬安全边界**的 AI 健身教练系统：<br>
数字孪生档案 · 三种排课方式 · 训练打卡与双渐进进阶 · 饮食营养与自定义套餐 · AI 教练对话 · 可观测性

</div>

---

## 📑 目录

- [为什么值得一看](#-为什么值得一看)
- [功能一览](#-功能一览)
- [技术栈](#-技术栈)
- [架构总览](#-架构总览)
- [六个核心设计](#-六个核心设计)
- [界面截图](#-界面截图)
- [快速开始](#-快速开始)
- [目录结构](#-目录结构)
- [测试](#-测试)
- [密钥与安全](#-密钥与安全)
- [常见问题](#-常见问题)

---

## ✨ 为什么值得一看

| 亮点 | 具体做了什么 |
|---|---|
| 🧮 **「NO Math in LLM」是一条硬约束** | BMR / TDEE / 1RM / 训练容量 / 热量与三大营养素折算**全部由 Java 确定性公式计算**（Mifflin-St Jeor、Epley、按每 100g 折算），大模型只准引用工具返回值。工具层**刻意不暴露「自报数值」参数**，从机制上堵死模型口算，而不是靠提示词祈祷 |
| 🛡️ **写库必须穿过 Java 护栏** | 单日 ≤ 30 组、单动作 1–15 组、次数 1–100、RPE 1–10、热量底线 `max(1000, BMR×0.8)`、**伤病禁忌动作安全过滤**。越界时工具返回 `ERROR:`，模型必须自行纠正参数重试；所有工具调用由 Spring AOP 记录参数、耗时与 `GUARDRAIL_BLOCKED`，护栏是否真的生效**可查证** |
| 🔐 **工具是唯一的写库入口，且无法越权** | 12 个 `@Tool` 覆盖档案 / 训练 / 营养三类读写，`userId` 通过 `@ToolMemoryId` 由框架注入——**模型既看不到也伪造不了**，天然防跨用户操作与 ID 编造 |
| 🔍 **自研 RAG 混合检索，零外部依赖** | BM25（k1=1.2, b=0.75）+ 0.6×TF-IDF 余弦 + 领域关键词加成，中文 bigram 分词；8 片运动科学知识（肌肥大容量、RPE/RIR、肩/膝/下背损伤替代方案、减载周、碳水循环、双渐进法）。**不引入向量库**，召回结果可解释、可在页面里逐条调试 |
| 💬 **流式对话「只讲结果、不讲过程」** | SSE 逐 token 推送的同时，在工具执行完成时下发 `event:reset`，让前端**丢弃模型在调用工具前的内部独白**（"我先查一下食物字典…"），用户只看到结论与已落库明细；配合 10 条 `@SystemMessage` 铁律（禁止提及工具名 / 接口路径 / JSON 字段 / 重试过程） |
| 🐞 **排查并根治了雪花 ID 精度问题** | JS `Number` 只有 53 位尾数，`Long` 主键 `...362` 被 `JSON.parse` 四舍五入成 `...400`（偏移 −38）→ 后端报「套餐模板不存在」。用**全局 Jackson `Long → String`** + 前端 `Id = string \| number` 类型迁移根治，并顺手修掉 3 处 `Number(id)` / `v-model.number` 二次丢失精度的写法 |
| ✅ **36 个自动化测试，含真实大模型在环** | 8 个测试类：确定性公式与实体映射、Redis ChatMemory、Agent 工具场景、RAG 与结构化排课、AOP 审计、删除接口护栏……全部使用**一次性用户**并在结束时清理自身数据，绝不污染演示数据 |

---

## 🧩 功能一览

### 五个页面

| 路由 | 页面 | 内容 |
|---|---|---|
| `/` | **数据看板** | 数字孪生指标卡（BMR/TDEE/今日摄入/本周完成/活跃伤病）、力量趋势（Epley 1RM 曲线）、肌群容量分布、营养趋势、热量环、**一键播种演示数据** |
| `/workout` | **周课表与打卡** | 三种排课、按天切换、编辑/换动作/删除动作、逐条打卡、双渐进建议面板、最近打卡流水 |
| `/nutrition` | **饮食与营养** | 当日目标与缺口、热量/三大营养素进度、饮食流水（区分字典 / 自定义 / AI 估算来源）、**自定义套餐**、**自定义食物** |
| `/twin` | **数字孪生** | 基础档案（改身高体重即自动重算 BMR/TDEE）、伤病矩阵（登记 / 一键恢复）、周课表概览 |
| `/observability` | **可观测性与 RAG** | 工具调用审计流水（类名/方法/入参/返回摘要/状态/耗时）、护栏拦截计数、**RAG 召回调试**（打分拆解：BM25 / 余弦 / 关键词加成 / 命中词） |

### 三种排课方式（覆盖"确定性 → 生成式 → 完全自由"）

1. **经典模板**：`PPL_3DAY`（推拉腿三分化）、`UPPER_LOWER_4DAY`（上下肢四分化）——纯 Java 排课，毫秒级，自动避开当前 ACTIVE 伤病的禁忌动作；
2. **AI 结构化排课**：LLM 按 JSON 结构输出 → Java 校验（伤病冲突 / 训练量 / 取值域）→ 不通过就把失败原因回灌给模型**重试**，最多 N 次后降级，并返回"第几次尝试通过护栏"与重试历史；
3. **完全自定义**：自己决定每天练什么，**同一个动作可以挂多个「重量组」**（例如 80kg×1组 / 90kg×2组 / 100kg×3组），每个重量组是一条独立明细 —— 因此**各自独立打卡、各自算 1RM 与容量、双渐进分别进阶**。

### 另外两个"自己起名字"的能力

- **自定义动作**：任意名称 + 肌群/器械/难度/要领，并**必须面对「禁忌受伤部位」**（护栏靠它判断安全性；留空会明确告警"护栏无法拦截该动作"，拼错部位名直接拒绝，防止护栏静默失效）；
- **自定义套餐**：自由组合食物与克重（可从字典选、也可自定义每 100g 营养值、还可让 AI 估算后确认），保存为常吃套餐一键打卡。

### AI 营养估算（suggestion-only）

`POST /api/ai/food-estimate` 返回每 100g 参考值 + 依据 + 置信度 + **Java 侧一致性校验**（`|4P+4C+9F − 热量| ≤ max(25, 25%)`），**绝不写库**；真正落库仍由用户确认后走 Java 折算，来源标记为 `AI_ESTIMATED`。

---

## 🛠 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21（**虚拟线程**）· Spring Boot 3.5.16 · Spring 6.2 · LangChain4j 1.22.0（BOM）· MyBatis-Plus 3.5.9 · MySQL 8.4 · Redis 7 · Lombok · knife4j / springdoc |
| 前端 | Vue 3.5 · Vite 6 · TypeScript 5.7 · Tailwind CSS 3.4 · ECharts 5.6 · lucide-vue-next · axios · vue-router |
| AI | DeepSeek（OpenAI 兼容协议，`LLM_BASE_URL` / `LLM_MODEL_NAME` 可换任意兼容模型）· `@AiService` + `@Tool` + `ToolMemoryId` · 自研 `ContentRetriever` · SSE 流式 |
| 数据 | 11 张业务表 · 27 个内置动作 · 27 种食物营养字典 · 2 套分化模板 · 8 片 RAG 知识 |

---

## 🏗 架构总览

```
┌──────────────────────────────────────────────────────────────────────┐
│  Vue 3 SPA（5 视图 / 24 组件 / ECharts）        http://localhost:5173 │
└───────────────┬──────────────────────────┬───────────────────────────┘
        HTTP /api/*                  SSE /api/ai/chat/stream
                │                          │   (event: message | reset | done | error)
┌───────────────▼──────────────────────────▼───────────────────────────┐
│  Spring Boot 3 · 8080 · Java 21 虚拟线程                              │
│                                                                      │
│  Controller (38 REST)  ──▶  Result<T> 统一响应 + 全局异常处理          │
│         │                                                            │
│  Service 层 ── 确定性公式（BMR/TDEE/Epley/容量/折算）                  │
│         │    └─ 安全护栏（训练量 / RPE / 热量底线 / 伤病禁忌）          │
│         │                                                            │
│  LangChain4j @AiService（FitnessCoachAgent）                          │
│         ├─ 12 × @Tool ───────────────▶ 业务 Service（唯一写库入口）     │
│         ├─ retrievalAugmentor ───────▶ 自研 ContentRetriever（BM25+TF-IDF）│
│         └─ chatMemoryProvider ───────▶ Redis（按 userId 分区）         │
│                                                                      │
│  Spring AOP ──▶ tool_audit_log（入参/返回摘要/状态/耗时/护栏拦截）      │
│  MyBatis-Plus ──▶ MySQL 8.4（雪花 ID · 逻辑删除 · 自动填充 · 防全表更新）│
└──────────────────────────────────────────────────────────────────────┘
```

---

## 🎯 六个核心设计

**① 把"不让模型算数"变成架构约束，而不是提示词愿望**

- 计算全部落在 Service 层：`BMR = 10×体重 + 6.25×身高 − 5×年龄 + s`（Mifflin-St Jeor）→ `TDEE = BMR × 活动系数` → 按目标（增肌/减脂/维持）调整 → 碳水循环日型（HIGH/MEDIUM/LOW_CARB）分配三大营养素；
- 训练侧用 **Epley**：`1RM = w × (1 + reps/30)`，容量 `= 组 × 次 × 重量`；
- 工具签名里**不存在**"热量/1RM"这类可自报参数，模型想编都没有入口。

**② 护栏是"可执行的业务规则"，不是注释**

| 护栏 | 阈值 | 触发行为 |
|---|---|---|
| 单日训练量 | ≤ 30 组 | 拒绝并给出当日明细拆解（`其他动作 X 组 + 本条 Y 组`） |
| 单动作组数 | 1–15 组 | 拒绝并提示合法区间 |
| 次数 / RPE | 1–100 / 1.0–10.0 | 拒绝 |
| 热量底线 | `max(1000, BMR×0.8)` kcal | 拒绝并建议调整日型 |
| 伤病禁忌 | 动作的 `avoid_injury_parts` 命中 ACTIVE 伤病 | **从安全动作库中剔除**；强行排入则直接拦截 |

被拦截的调用会以 `ERROR:` 前缀回给模型（附带只在内部可见的修正建议），同时记入审计的 `GUARDRAIL_BLOCKED` 计数。

**③ 双渐进（Double Progression）进阶引擎**

按「课表明细」聚合历史（而不是按动作），因此**同一动作的多个重量组各自独立进阶**；结合最近样本的达标情况与 RPE 给出 `OVERLOAD_READY`（加重）/ `DELOAD_NEEDED`（减载）/ `MAINTAIN`（维持），并支持一键采纳写回课表。

**④ RAG 检索可解释、可调试**

BM25 与 TF-IDF 加权融合 + 领域关键词加成，返回 `score / bm25 / cosine / keywordBonus / matchedTerms` 明细；`/observability` 页面能直接看到"这次为什么召回这一片"，便于调参与排错。

**⑤ 流式对话只呈现结论**

工具调用前那一轮，模型会输出"我打算先查…"的独白。这类内容对用户无价值，因此在每次工具执行后下发 `reset`，前端清空气泡，只保留最终答复（实测：同一问题从 1000 字降到 489 字，删掉的正是过程叙述）。

**⑥ 工程细节不留坑**

- **雪花 ID**：全局 `Jackson Long → String`，前端 `Id` 类型统一，杜绝精度丢失；
- **防全表更新**：MyBatis-Plus `BlockAttackInnerInterceptor`；
- **审计与业务解耦**：AOP 环绕通知，异常也不会丢记录；
- **测试隔离**：一次性用户 + 结束时清理自身产生的全部流水（含自定义动作），保证演示数据不被污染；
- **演示可复现**：一键播种固定只用**系统内置**动作，避免用户后建的自定义动作让演示场景漂移。

---

## 🖼 界面截图

> 设计系统：**Kinetic Dark & Acid Lime**（Apple Fitness+ × Whoop 风格）—— 深空底色 `#090A0F`、酸柠檬高亮 `#CCFF00`、12 栅格 Bento 非对称布局、Barlow Condensed 数字读数（等宽数字）、Lucide 线性图标、统一动效曲线。

<!--
  截图占位：把图片放到 docs/screenshots/ 后替换下面的路径即可（GitHub 会自动渲染）
-->
| 数据看板 | 周课表与打卡 |
|---|---|
| _（待补充 `docs/screenshots/dashboard.png`）_ | _（待补充 `docs/screenshots/workout.png`）_ |

| 饮食与自定义套餐 | 可观测性与 RAG |
|---|---|
| _（待补充 `docs/screenshots/nutrition.png`）_ | _（待补充 `docs/screenshots/observability.png`）_ |

---

## 🚀 快速开始

### 环境与端口

| 组件 | 版本 | 地址 | 是否需手动启动 |
|---|---|---|---|
| MySQL | 8.4 | `127.0.0.1:3306`，库 `fitness_ai`，`root/123456` | 否（Windows 服务 `MySQL84`，已设自动启动） |
| Redis | 7 (Docker) | `127.0.0.1:6379`，容器名 `fitness-redis` | **是**（Docker Desktop 未设自启） |
| 后端 | Spring Boot 3.5 | `http://localhost:8080`（健康检查 `/api/health`） | **是** |
| 前端 | Vite 6 | **`http://localhost:5173`** | **是** |

> ⚠️ 访问前端必须用 **`localhost`**，不要用 `127.0.0.1` —— Vite 只绑定了 IPv6 `[::1]`。
> 首次运行前请先建库并导入 `backend/src/main/resources/sql/schema.sql` 与 `data.sql`。

### 方式一：一键启动（推荐）

```cmd
cd /d D:\jsgl
start-all.cmd
```

脚本按依赖顺序执行（**可重复运行，已在跑的会跳过**）：检查 MySQL → 启动 Docker Desktop 与 `fitness-redis`（`PING` 验证）→ 新窗口启动后端 → 新窗口启动前端 → 轮询就绪并打印访问地址。

```cmd
start-all.cmd -InfraOnly         :: 只起 MySQL + Redis（让 IDE 去跑应用，避免抢 8080）
start-all.cmd -Stop              :: 停后端 + 前端
start-all.cmd -Stop -StopRedis   :: 连 Redis 容器一起停
```

> **为什么发的是 `.cmd` 而不是 `.ps1`？** Windows 默认的 PowerShell 执行策略通常禁止运行脚本，直接运行 `.ps1` 会报 `UnauthorizedAccess`；`start-all.cmd` 内部用 `-ExecutionPolicy Bypass`，**仅对本次调用放行，不修改系统设置**。
> 也可手动执行：`powershell -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1`

> **两个 Windows 环境注意点（脚本已自动处理）**：① PowerShell 5.1 读取**无 BOM** 的 UTF-8 脚本时会按本地代码页（中文系统为 GBK）解析 → 中文乱码与语法错误，因此 `start-all.ps1` 以 **UTF-8 with BOM** 保存，用编辑器改动后请保持该编码；② 脚本会强制把 `JAVA_HOME` 指向 JDK 21，避免机器上存在多个 JDK 时误用旧版本。

### 方式二：IntelliJ IDEA

`pom.xml` 位于 `backend/`。仓库根目录已放了一个**聚合 `pom.xml`**（`packaging=pom` + `<module>backend</module>`），因此**直接打开仓库根目录即可**被识别为 Maven 工程。

| 已配置好的文件 | 作用 |
|---|---|
| `pom.xml`（根目录） | 聚合 POM：让 IDEA 把根目录识别为 Maven 工程并导入 `backend` 模块 |
| `.idea/compiler.xml` | 开启**注解处理器**（Lombok 必需，否则满屏"找不到符号 getXxx()/log"） |
| `.run/FitnessAiApplication.run.xml` | 后端启动配置，打开项目后直接出现在运行下拉框 |

操作三步：

1. **确认 JDK 21**：`File → Project Structure → Project`，SDK 选 21（指向 JDK 21 安装目录）。
2. **若未自动加载 Maven**：右键根目录 `pom.xml` → **Add as Maven Project**（或 Maven 面板点 ⟳）。
3. **配置 `LLM_API_KEY`**：`setx LLM_API_KEY "sk-..."` 后重启 IDEA（运行配置继承系统环境变量，密钥不写进仓库），或在 `Run → Edit Configurations → Environment variables` 里填。

然后 `start-all.cmd -InfraOnly` 起基础设施 → 点 ▶ 运行 `FitnessAiApplication (backend)`。

### 方式三：手动启动

```powershell
# ① Redis
docker start fitness-redis
docker exec fitness-redis redis-cli ping          # 期望 PONG

# ② 后端（新窗口）
cd backend
$env:JAVA_HOME = '<JDK 21 安装目录>'
$env:LLM_API_KEY = 'sk-...'
.\mvnw.cmd spring-boot:run                        # 看到 Started FitnessAiApplication 即成功

# ③ 前端（再开一个窗口）
cd frontend
npm install
npm run dev
```

浏览器打开 **<http://localhost:5173>**。

### 演示数据

数据看板的「**一键播种演示数据**」会以当天为终点重建 14 天训练记录与 7 天饮食账单，并确保演示伤病（`LEFT_SHOULDER` ACTIVE）存在；自行创建的自定义动作 / 套餐不会被删除。

> ⚠️ 数字孪生页的「标记为已恢复」会让**整套伤病护栏演示失效**（安全动作库从 16 个放开到全部 28 个，模板不再跳槽位、AI 换动作不再受限、手动选禁忌动作也不会被拦截）。恢复方式：再点一次「一键播种演示数据」，或重新登记一次该部位不适（恢复后再登记会新增记录，历史不会被覆盖）。

---

## 📁 目录结构

```
D:\jsgl
├── pom.xml                    # 聚合 POM（供 IDE 识别，后端模块在 backend/）
├── start-all.ps1 / .cmd       # 一键启动/停止脚本
├── README.md · AGENTS.md      # 项目说明 · AI 协作规范
├── .gitignore · .gitattributes
├── .run/                      # IDEA 运行配置（无密钥，可共享）
├── design-system/MASTER.md    # 设计系统规范（色彩/字体/栅格/组件/动效）
├── backend/                   # Java 21 + Spring Boot（102 个 Java 文件 / 9,473 行）
│   └── src/main/java/com/fitness/ai/
│       ├── common/            # Result / ErrorCode / 全局异常 / Jackson / MyBatis 配置
│       ├── profile/           # 数字孪生档案与伤病标签
│       ├── workout/           # 动作字典、周课表、打卡、自定义动作
│       ├── nutrition/         # 食物字典、宏量目标、饮食账单
│       ├── progression/       # 双渐进进阶引擎
│       ├── catalog/           # 训练模板、套餐模板
│       ├── dashboard/         # 看板聚合 + 演示数据播种
│       ├── observability/     # 工具调用审计（AOP）
│       ├── scheduler/         # 每日宏量目标定时任务
│       └── ai/                # agent / tool / rag / memory / service / controller
└── frontend/                  # Vue 3 + Vite（24 个组件 / 5,350 行）
    └── src/{views,components,api,composables,stores,theme,utils}
```

---

## 🧪 测试

```powershell
cd backend
$env:JAVA_HOME = '<JDK 21 安装目录>'
$env:LLM_API_KEY = 'sk-...'      # Agent / RAG / 结构化排课用例需要真实大模型
.\mvnw.cmd test                  # 8 个测试类 / 36 个用例
```

覆盖：实体列映射与元数据自动填充、全表更新拦截、确定性公式、Redis ChatMemory、Agent 工具场景（含护栏拦截）、SSE 与结构化排课、RAG 召回、AOP 审计、课表/明细删除护栏、自定义动作隔离与删除保护。

前端类型检查与构建：

```powershell
cd frontend
npm run build                    # vue-tsc --noEmit && vite build
```

> 测试使用**一次性用户**并在结束时清理其全部流水；仅 `AdvancedModulesTest` 中有一个用例会以 `userId=1` 重新播种演示数据（属预期行为）。

---

## 🔐 密钥与安全

项目里**没有任何密钥**，全部通过环境变量注入：

| 环境变量 | 用途 | 代码中的默认值 |
|---|---|---|
| `LLM_API_KEY` | 大模型 API Key（AI 功能必需） | 占位符 `sk-your-api-key` |
| `MYSQL_HOST` / `MYSQL_USER` / `MYSQL_PWD` | 数据库连接 | `localhost` / `root` / `123456`（仅本地开发） |
| `REDIS_HOST` / `REDIS_PWD` | Redis 连接 | `localhost` / 空 |
| `LLM_BASE_URL` / `LLM_MODEL_NAME` | 模型地址与名称 | `https://api.deepseek.com/v1` / `deepseek-chat` |

`application.yml` 只以 `${LLM_API_KEY:...}` 形式**引用**环境变量，因此**换 Key 不需要改任何代码或提交**。

密钥自检（任何时候都可以运行这三条命令，确认仓库内容里不含密钥）：

```bash
git grep -n -I -E "sk-[A-Za-z0-9_-]{20,}"                               # 期望：无输出
git grep -n -I "api-key" -- backend/src/main/resources/application.yml  # 期望：只有 ${LLM_API_KEY:sk-your-api-key}
git status --short                                                       # 期望：无 node_modules / target / .idea / *.log
```

仓库已配置 `.gitignore`（忽略 `node_modules/`、`target/`、`dist/`、`.idea/`、`.npm-cache/`、`*.log`、`.env`、`*.pem` 等）与 `.gitattributes`（统一换行符，并保证 `mvnw` 在 Linux/macOS 下可执行）。

---

## ❓ 常见问题

| 现象 | 原因与解决 |
|---|---|
| IDEA 里 Java 文件全是红的 / 跑不起来 | 未作为 Maven 工程加载 → 右键根目录 `pom.xml` → **Add as Maven Project**（仓库根已有聚合 POM） |
| IDEA 满屏"找不到符号 getXxx() / log" | Lombok 注解处理未开启 → `Settings → Compiler → Annotation Processors` 勾选 *Enable annotation processing*，并确认 Lombok 插件启用 |
| IDEA 报 `Port 8080 was already in use` | 脚本已占用端口 → 先 `start-all.cmd -Stop` |
| 运行配置提示 `Module not specified` | IDEA 生成的模块名不是 `backend` → 右键 `FitnessAiApplication.java` → **Run**，自动生成配置 |
| `无法加载文件 ... start-all.ps1，因为在此系统上禁止运行脚本` | 用 `start-all.cmd`（内部已 Bypass 策略），不要直接运行 `.ps1` |
| 脚本报 `Unexpected token` 且中文乱码 | `start-all.ps1` 被存成「UTF-8 无 BOM」→ 重新以 **UTF-8 with BOM** 保存 |
| `'"node"' 不是内部或外部命令` | 前端窗口未把 Node 加入 PATH → 用 `start-all.cmd`，或手动 `set PATH=<Node 目录>;%PATH%` |
| 浏览器打不开 `127.0.0.1:5173` | Vite 只绑 IPv6 → 改用 `http://localhost:5173` |
| 后端编译报 Java 版本错误 | `JAVA_HOME` 指向了 JDK 11 → 切换到 JDK 21 |
| 后端能起但 AI 报鉴权失败 | 未注入 `LLM_API_KEY`（`Authentication Fails ... api key is invalid`），其余功能不受影响 |
| `docker` 报 `pipe dockerDesktopLinuxEngine not found` | Docker Desktop 未启动 → 先打开并等引擎就绪 |
| AI 对话报 Redis 相关异常 | Redis 容器未启动 → `docker start fitness-redis` |
| 端口被占用 | `start-all.cmd -Stop`；或 `netstat -ano \| findstr :8080` 找到 PID 后 `Stop-Process -Id <PID> -Force` |

> 说明：README 与脚本中的 `D:\jsgl`、`D:\jdk`、`D:\NodeJS` 为开发机路径，迁移到其他环境时按需替换。

---

暂未附带开源许可证。若希望他人可自由使用/修改，建议补充 `LICENSE`（MIT 或 Apache-2.0）。
