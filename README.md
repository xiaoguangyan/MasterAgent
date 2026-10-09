# Master Agent —— 整车智能中枢 MVP

> 版权所有：xiaoguang.yan（8518960@qq.com）

Master Agent（整车智能中枢）定位为车载「Agent OS」，由三大子系统构成：

| 子系统 | 职责 | 独立工程 |
| --- | --- | --- |
| Master Agent | 决策 Decide（意图理解 / 仲裁 / 规划 / 执行） | `master-agent/`（端侧）+ `cloud/`（云侧慢道） |
| Context | 情境 Situate（实时感知 / 上下文窗口） | `context/` |
| Memory | 知识 Learn（工作 / 情景 / 长期记忆） | `memory/` |
| 契约层 | 三者间的“必要调用”（SPI + 数据结构） | `contract/` |

## 工程目录（记忆 / 上下文 / Master Agent 各自独立工程）

```
MasterAgentDemo/
├── contract/        # 契约工程（共享 SPI + 数据结构）—— 独立 Gradle 工程
├── context/         # 上下文工程（L0-L4 分层 / 环形窗口 / 实时车态）—— 独立 Gradle 工程
├── memory/          # 记忆工程（工作/情景/长期 + 敏感拦截 + 一键清除）—— 独立 Gradle 工程
├── master-agent/    # Master Agent 工程（决策主控 + 领域 Agent + 可运行入口）—— 独立 Gradle 工程
├── cloud/           # 云侧（Java 17 / Spring Boot 3.2 / Maven 多模块）—— 独立工程
├── offline-m2/      # 共享离线 Maven 依赖（JUnit 等，保证离线可复现构建）
├── build-all.sh     # 一键构建：依次独立构建全部工程
└── .github/workflows/ci.yml   # 自动化 CI
```

**工程独立性**：四个端侧工程各自拥有独立 `settings.gradle.kts` / `build.gradle.kts` / Gradle wrapper，**均可单独构建**（`cd context && ./gradlew build`）。工程之间仅通过组合构建 `includeBuild` 引用**契约层**（必要的调用），代码互不混入，便于后期维护。

依赖方向（单向、无环）：`contract` ← `context` / `memory` ← `master-agent`。

- **端侧**（车机，Qualcomm SA8295P/SA8797P）：实时感知、快道闭环、安全网关、守护续跑、KV 缓存、隐私驻留。
- **云侧**（服务器）：大模型慢道推理、长期记忆存储。
- **模型插件化**：端侧 `IModelGateway`、云侧 `SlowLaneGateway` 均为 SPI 开关，不强依赖任何模型或开源框架。

## 快速开始（一键构建）

```bash
# 前提：JDK 17、Maven 3.9+；离线构建需本地缓存 Gradle 9.6.0
./build-all.sh
```

构建完成后：
- 端侧演示：`cd master-agent && gradle run`（打印 4×EXECUTE + 1×FALLBACK）
- 云侧产物：`cloud/gateway/target/gateway-1.0.0.jar`（可执行 jar，`java -jar` 启动）

## 自动化 CI

`.github/workflows/ci.yml` 在同一 CI 环境下**并行**构建端侧四工程 + 云侧，Gradle / Maven 依赖缓存，推送任意分支即触发快速反馈。

## 详细指导

- 契约工程：见 [`contract/README.md`](contract/README.md)
- 上下文工程：见 [`context/README.md`](context/README.md)
- 记忆工程：见 [`memory/README.md`](memory/README.md)
- Master Agent 工程（安装 / 构建 / 运行 / IDE / 部署）：见 [`master-agent/README.md`](master-agent/README.md)
- 云侧工程（安装 / 构建 / 运行 / 部署 / IDE）：见 [`cloud/README.md`](cloud/README.md)
