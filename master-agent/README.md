# Master Agent 工程（端侧）

> 版权所有：xiaoguang.yan（8518960@qq.com）

决策 Decide：意图合成 / 仲裁 / 规划 / 执行 / 安全网关 / 守护续跑 / KV 缓存 / 可观测，端侧运行于车机（Qualcomm SA8295P / SA8797P，Hexagon NPU + QNN）。

## 技术栈

- Kotlin 2.1.20，JVM 17，Gradle 9.6.0
- kotlinx-coroutines 1.9.0
- JUnit 5.10.2（测试）

## 源码结构（包）

```
src/main/kotlin/com/xiaoguang/masteragent/
├── feature/master/   # 主控：IntentComposer / Arbiter / Planner / Executor / SafetyGateway / MasterAgent
├── feature/domain/   # 领域 Agent：空调 / 车窗 / 座椅 / 媒体 / 导航
├── core/model/       # 快道规则解析 + 置信度融合（端侧 SLM 插件接缝）
├── core/kv/          # LRU 前缀缓存 + 量化
├── core/daemon/      # 检查点 / 看门狗 / 主备切换
├── core/observe/     # 执行轨迹采集
└── app/              # Assembly 手动装配 + Main 演示入口
```

依赖方向：主控组合引用契约 / 上下文 / 记忆三独立工程（`includeBuild`），领域 Agent 仅依赖契约。

## 安装向导（环境准备）

1. 安装 JDK 17（macOS）：
   ```bash
   brew install openjdk@17
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
   ```
2. Gradle 9.6.0：`./gradlew`（在线）或本地缓存二进制（离线）。
3. 离线依赖：JUnit 已固化在 workspace 根 `offline-m2/`，`settings.gradle.kts` 已注册为本地仓库。

## 构建指导

```bash
cd master-agent
./gradlew build            # 在线（CI）
# 离线：
JAVA_HOME=<jdk17> <gradle-9.6.0>/bin/gradle build --offline --no-daemon --console=plain
```

`build` 编译本工程并组合构建 contract/context/memory，运行全部测试（12 项）。

## 运行 / 启动指导

```bash
cd master-agent
JAVA_HOME=<jdk17> <gradle-9.6.0>/bin/gradle run --offline --no-daemon --console=plain
```

预期输出：4×EXECUTE（空调/车窗/音乐/导航）+ 1×FALLBACK（未识别指令）。

## IDE 指导（IntelliJ IDEA）

1. `File → Open` 选择本目录，IDEA 自动识别为 Gradle 工程并同步。
2. `Preferences → Build Tools → Gradle`：Gradle JVM 选 **JDK 17**；离线环境勾选 `Offline work`。
3. 运行入口：右键 `app/.../Main.kt` → `Run 'MainKt'`。
4. 运行测试：右键 `src/test` → `Run 'All Tests'`。

## 部署指导（车机端侧）

1. 构建产物（`build/distributions/*.tar` 或 `build/install/` 下的启动脚本 + lib）推送至车机 Linux 用户态目录。
2. 以 JDK 17 启动（`build/install/master-agent/bin/master-agent` 或 `java -jar`）。
3. 真实部署：将 `core/model` 规则网关替换为 QNN 量化后的 1.5B 端侧 SLM（`IModelGateway` 插件化，不改上层逻辑）。

## 发布到本地 Maven（供 Android 车机引入）

```bash
# 仓库根目录：按依赖顺序发布四工程到 ~/.m2
./publish-local.sh
```

Android 车机 App 引入：

```kotlin
// settings.gradle.kts
repositories { mavenLocal() }

// app/build.gradle.kts
dependencies {
    implementation("com.xiaoguang.masteragent:master-agent:1.0.0")
    implementation("com.xiaoguang.masteragent:contract:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

装配与调用：`Assembly(modelGateway = <QNN 端侧 SLM 实现>).master.run(msg)`（suspend，走 `Dispatchers.Default`，禁止主线程 `runBlocking`）。完整接入示例见 `../android-host/`。

## 模型配置
master agent工程下src/main/resources/model-config.json自行修改模型接入，目前模型调用均采用Open AI标准。
```bash
# 
{
      "name": "Qwen2.5-14B-Instruct",
      "endpoint": "https://localhost/v1/chat/completions",
      "apiKey": "sk-XXXXXXXXXXXXXXXXX",
}
```
