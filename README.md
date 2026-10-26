# Personal AI Fitness System

个人 AI 健身系统：**Java 21 + Spring Boot + LangChain4j** 后端 + **Vue 3 + Vite** 前端。
确定性计算（BMR/TDEE/1RM/热量折算）全部在 Java 里做，大模型只负责对话与工具编排。

---

## 一、环境与端口

| 组件 | 版本 | 地址 / 路径 | 是否需手动启动 |
|---|---|---|---|
| MySQL | 8.4 | `127.0.0.1:3306`，库 `fitness_ai`，`root/123456` | 否（Windows 服务 `MySQL84`，已设自动启动） |
| Redis | 7 (Docker) | `127.0.0.1:6379`，容器名 `fitness-redis` | **是**（Docker Desktop 未设自启） |
| 后端 | Spring Boot 3.5 | `http://localhost:8080`（健康检查 `/api/health`） | **是** |
| 前端 | Vite 6 | **`http://localhost:5173`** | **是** |

> ⚠️ 访问前端必须用 **`localhost`**，不要用 `127.0.0.1` —— Vite 只绑定了 IPv6 `[::1]`，IPv4 会连不上。

---

## 二、一键启动（推荐）

在项目根目录双击或执行：

```cmd
cd /d D:\jsgl
start-all.cmd
```

脚本会按依赖顺序做这些事（**可重复执行，已在跑的会跳过**）：

1. 检查 MySQL（没起就启动 `MySQL84` 服务）
2. 启动 Docker Desktop → 启动容器 `fitness-redis` → 用 `PING` 验证
3. 在**独立新窗口**启动后端（`mvnw spring-boot:run`）
4. 在**独立新窗口**启动前端（`npm run dev`）
5. 轮询等待就绪，最后打印访问地址

停止：

```cmd
start-all.cmd -Stop              :: 停后端 + 前端
start-all.cmd -Stop -StopRedis   :: 连 Redis 容器一起停
```

> **为什么要用 `.cmd` 而不是直接跑 `.ps1`？**
> 本机 PowerShell 执行策略**禁止运行脚本**，直接 `.\start-all.ps1` 会报
> 「在此系统上禁止运行脚本 / UnauthorizedAccess」。`start-all.cmd` 只是用
> `-ExecutionPolicy Bypass` 对本次调用放行，**不会修改你的系统设置**。
> 想直接跑 ps1 也可以：`powershell -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1`

> **两个易踩的本机特性**（脚本已自动处理，手动操作时要自己注意）：
> 1. 本机只有 **Windows PowerShell 5.1**（`pwsh` 不在 PATH 里）。5.1 读取**无 BOM** 的
>    UTF-8 脚本文件会按 GBK 解析 → 中文乱码 + 语法错误。所以 `start-all.ps1` 必须以
>    **UTF-8 with BOM** 保存；用编辑器改动后请确认编码没被改回「UTF-8」。
> 2. 用户级 `JAVA_HOME` 指向 `D:\jdk11`，而本项目需要 **Java 21**（`D:\jdk`）；脚本会强制覆盖。

---

## 三、用 IntelliJ IDEA 运行（推荐做开发时用）

### 3.1 关键：工程根目录在 `backend/`，不是 `D:\jsgl`

`pom.xml` 在 `backend/` 里。如果直接在 IDEA 里打开 `D:\jsgl` 根目录，IDEA 会把它当成**普通文件夹/空 Java 模块**（没有 Maven 模型、没有依赖、没有 Spring Boot 支持），表现就是**所有 Java 文件都跑不起来**。

仓库根目录已经放了一个**聚合 `pom.xml`** 专门解决这个问题（`packaging=pom`，只声明 `<module>backend</module>`），所以现在**打开 `D:\jsgl` 也能正常识别**。IDEA 首次打开时会提示 **Load Maven Project**，点 **Load** 即可（之后可在右侧 Maven 面板点刷新图标重新导入）。

### 3.2 已为你配置好的东西（都已随仓库提交）

| 文件 | 作用 |
|---|---|
| `pom.xml`（根目录） | 聚合 POM：让 IDEA 把 `D:\jsgl` 识别为 Maven 工程并自动导入 `backend` 模块 |
| `.idea/compiler.xml` | 开启**注解处理器**（本项目大量用 Lombok，不开启会满屏“找不到符号 getXxx()/log”） |
| `.run/FitnessAiApplication.run.xml` | 后端启动配置：等价于 `cd backend && mvnw spring-boot:run`，IDEA 打开后直接出现在运行下拉框里 |

### 3.3 你在 IDEA 里要做的三步

1. **确认 JDK 21**：`File → Project Structure → Project`，SDK 选 **21**（指向 `D:\jdk`）。
   若列表里没有：`SDKs → + → Add JDK… → 选 D:\jdk`，名字填 `21`。
2. **如果没自动加载 Maven**：右键点根目录 `pom.xml` → **Add as Maven Project**（或在右侧 Maven 面板点 ⟳ 刷新）。
   然后等 IDEA 索引依赖完成（本地仓库已有缓存，通常很快）。
3. **配置 `LLM_API_KEY`**（AI 对话需要；不做也能跑，只是 AI 接口会鉴权失败）：
   在普通 PowerShell 里执行一次 `setx LLM_API_KEY "你的-DeepSeek-API-Key"`，然后**重启 IDEA**（运行配置会继承系统环境变量，密钥不会写进仓库）；
   或者直接在 `Run → Edit Configurations → FitnessAiApplication (backend) → Environment variables` 里填 `LLM_API_KEY=...`。

### 3.4 运行与排错

- **先起基础设施**（MySQL + Redis），并且**别让脚本占用 8080**：
  ```cmd
  start-all.cmd -InfraOnly
  ```
  然后回到 IDEA 点绿色的 ▶ 运行 `FitnessAiApplication (backend)`。
  （如果之前用 `start-all.cmd` 完整启动过，先 `start-all.cmd -Stop` 释放 8080 / 5173。）
- **前端**：IDEA 会自动识别 `frontend/package.json`，在 `npm` 工具窗里双击 `dev` 即可；也可以继续用 `start-all.cmd`。
  前端访问地址仍是 **<http://localhost:5173>**（Vite 只绑 IPv6 `[::1]`，别用 `127.0.0.1`）。
- **运行配置提示“模块找不到”**：说明 IDEA 生成的模块名不是 `backend`。这时直接右键 `FitnessAiApplication.java` → **Run 'FitnessAiApplication'**，IDEA 会自动生成一个可用的配置。
- **满屏红字找不到 getter/setter/log**：Lombok 未生效 → 确认 `Settings → Build, Execution, Deployment → Compiler → Annotation Processors` 勾选了 *Enable annotation processing*，并在 `Plugins` 里确认 Lombok 已启用（2020.3+ 已内置）。

---

## 四、手动启动（脚本做了什么）

### 4.1 Redis（每次都要手动拉起）

```powershell
# 打开 Docker Desktop（开始菜单搜索 Docker Desktop），等右下角鲸鱼图标变绿
docker start fitness-redis
docker exec fitness-redis redis-cli ping     # 期望输出 PONG
```

容器不存在时创建（仅第一次）：

```powershell
docker run -d --name fitness-redis -p 6379:6379 redis:7-alpine
```

### 4.2 后端（新开一个 PowerShell 窗口）

```powershell
cd D:\jsgl\backend
$env:JAVA_HOME = 'D:\jdk'          # ← 关键！不能用默认的 jdk11
$env:LLM_API_KEY = '你的-DeepSeek-Key'
.\mvnw.cmd spring-boot:run
```

看到 `Started FitnessAiApplication` 即成功；验证：<http://localhost:8080/api/health>。

### 4.3 前端（再开一个 PowerShell 窗口）

```powershell
cd D:\jsgl\frontend
$env:PATH = 'D:\NodeJS;' + $env:PATH   # ← 关键！否则 vite 找不到 node
npm run dev
```

然后浏览器打开 **<http://localhost:5173>**。

---

## 五、日常使用要点

- **AI 功能需要 `LLM_API_KEY`**。想一劳永逸，在普通 PowerShell 里执行一次即可（新终端生效）：
  ```powershell
  setx LLM_API_KEY "你的-DeepSeek-API-Key"
  ```
  密钥不会被写进项目里的任何文件。
- **演示数据**：数据看板右上角有「一键播种演示数据」，会以**当天**为终点重建 14 天训练/7 天饮食账单，并确保演示伤病（`LEFT_SHOULDER` ACTIVE）存在。你自己创建的自定义动作/套餐不会被删除。
- **注意：数字孪生页的「标记为已恢复」会让整套伤病护栏演示失效**。左肩一旦被标为 `RECOVERED`：
  - 安全动作库会从 16 个放开到全部 28 个（不再过滤禁忌动作）
  - 模板排课不再跳槽位、AI 换动作不再受限制、手动选禁忌动作也不会被拦截
  想恢复演示状态：点一次「一键播种演示数据」（它会确保 `LEFT_SHOULDER` 处于 ACTIVE），
  或在数字孪生页重新登记一次左肩不适（恢复后再登记会**新增一条记录**，历史记录不会被覆盖）。
- **页面**：`/` 数据看板 · `/workout` 周课表（模板 / AI / 完全自定义三种排课）· `/nutrition` 饮食（自定义套餐、AI 营养估算）· `/twin` 数字孪生 · `/observability` 可观测性与 RAG。

---

## 六、常见问题

| 现象 | 原因与解决 |
|---|---|
| **IDEA 里所有 Java 文件都是红的 / 根本跑不起来** | 打开的是仓库根目录且 IDEA 没把它当 Maven 工程 → 见**第三节**：点 **Load Maven Project**（根目录已有聚合 `pom.xml`） |
| IDEA 满屏“找不到符号 getXxx() / log” | Lombok 注解处理没开 → `Settings → Build, Execution, Deployment → Compiler → Annotation Processors` 勾选 *Enable annotation processing*；并确认 Lombok 插件已启用 |
| IDEA 运行报 `Port 8080 was already in use` | 之前用 `start-all.cmd` 起过后端 → 先 `start-all.cmd -Stop` 释放 8080 / 5173 |
| IDEA 运行配置提示 `Module not specified` / 模块找不到 | IDEA 生成的模块名不是 `backend` → 直接右键 `FitnessAiApplication.java` → **Run**，自动生成可用配置 |
| `无法加载文件 ... start-all.ps1，因为在此系统上禁止运行脚本` | 用 `start-all.cmd`（内部已 Bypass 策略）；不要直接双击/运行 `.ps1` |
| 脚本报 `Unexpected token` 且输出中文乱码 | `start-all.ps1` 被存成了「UTF-8 无 BOM」，被 PowerShell 5.1 按 GBK 解析 → 重新以 **UTF-8 with BOM** 保存 |
| `'"node"' 不是内部或外部命令` | 前端窗口没把 Node 加进 PATH → `set PATH=D:\NodeJS;%PATH%` 后重跑，或用 `start-all.cmd` |
| 浏览器打不开 `127.0.0.1:5173` | Vite 只绑 IPv6，改用 `http://localhost:5173` |
| 后端编译报 Java 版本/BOM 错误 | `JAVA_HOME` 指向了 `D:\jdk11`，改成 `D:\jdk`（Java 21） |
| 后端起来但 AI 报鉴权失败 | 没注入 `LLM_API_KEY`（`Authentication Fails ... api key is invalid`），其余功能不受影响 |
| `docker` 报 `pipe dockerDesktopLinuxEngine not found` | Docker Desktop 没启动，先打开它并等引擎就绪 |
| AI 对话报 Redis 相关异常 | Redis 容器没起 → `docker start fitness-redis` |
| 端口 8080 / 5173 被占用 | `start-all.cmd -Stop` 后再启动；或 `netstat -ano \| findstr :8080` 找到 PID 后 `Stop-Process -Id <PID> -Force` |

---

## 七、跑测试

```powershell
cd D:\jsgl\backend
$env:JAVA_HOME = 'D:\jdk'
$env:LLM_API_KEY = '你的-DeepSeek-Key'   # Agent / RAG / 结构化排课用例需要真实大模型
.\mvnw.cmd test
```

前端构建与类型检查：

```powershell
cd D:\jsgl\frontend
npm run build      # vue-tsc --noEmit && vite build
```

> 测试使用**一次性用户**并在结束时清理自己产生的数据；`AdvancedModulesTest` 里有一个用例会以 `userId=1` 重新播种演示数据（属预期行为）。