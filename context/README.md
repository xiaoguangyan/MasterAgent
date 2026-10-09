# 上下文工程 —— Context

> 版权所有：xiaoguang.yan（8518960@qq.com）

情境 Situate：实时感知与上下文窗口，负责 L0-L4 分层路由、环形滑动窗口、实时车态。

## 内容

| 文件 | 职责 |
| --- | --- |
| `RingBuffer.kt` | L1 环形滑动窗口（定长，满则覆盖最旧，O(1)） |
| `RealtimeStore.kt` | L0/L2 实时车态（StateFlow 订阅即推、原子更新） |
| `ContextManager.kt` | 分层编排：`push()` 写入 / `snapshot(budget)` 组装 / 感知字段合并 |

## 构建

```bash
cd context
./gradlew build            # 在线（CI，组合引用 contract）
# 离线：
JAVA_HOME=<jdk17> <gradle-9.6.0>/bin/gradle build --offline --no-daemon --console=plain
```

## 依赖（必要的调用）

仅依赖契约工程 `contract`（通过 `includeBuild` 组合引用）。不依赖 memory / master-agent，代码与它们分离。

## 测试

`RingBufferTest`：环形覆盖 O(1)。

## IDE

IntelliJ IDEA：`File → Open` 选择本目录，Gradle JVM 选 JDK 17。
