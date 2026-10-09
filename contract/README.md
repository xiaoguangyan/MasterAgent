# 契约工程 —— Contract

> 版权所有：xiaoguang.yan（8518960@qq.com）

共享契约层：Master Agent / Context / Memory 三个独立工程之间「必要的调用」接口与数据结构。

## 内容

| 文件 | 职责 |
| --- | --- |
| `Spi.kt` | 全部跨工程接口（`IModelGateway` / `IMemoryService` / `IContextManager` / `ICheckpointStore` / `ITraceCollector` 等） |
| `IntentMessage.kt` | 意图消息契约（Header / Input / Result / 检查点记录） |
| `Blocks.kt` | 置信度 / 意图 / 仲裁 / 规划 / 执行等数据块 |
| `Contract.kt` | 生命周期 7 态 / 四态裁定 / 记忆项 / L0-L4 分层 / KV / 检查点 |
| `Enums.kt` | 阶段 / 通道 / 关系 / 路由等枚举 |
| `AiBBus.kt` | 事件总线（sessionId:stage 订阅） |

## 构建

```bash
cd contract
./gradlew build            # 在线（CI）
# 离线：
JAVA_HOME=<jdk17> <gradle-9.6.0>/bin/gradle build --offline --no-daemon --console=plain
```

## 依赖

仅 Kotlin 标准库 + kotlinx-serialization + kotlinx-coroutines，**无任何其他工程依赖**（被 context / memory / master-agent 组合引用）。

## IDE

IntelliJ IDEA：`File → Open` 选择本目录，Gradle JVM 选 JDK 17。
