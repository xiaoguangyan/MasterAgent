# 记忆工程 —— Memory

> 版权所有：xiaoguang.yan（8518960@qq.com）

知识 Learn：工作记忆 / 情景记忆 / 长期记忆 + 四态裁定 + 敏感拦截 + 一键清除。

## 内容

| 文件 | 职责 |
| --- | --- |
| `WorkingMemory.kt` | 工作记忆（本轮会话，会话结束随会话清理） |
| `EpisodicStore.kt` | 情景记忆（近期事件序列，TTL 过期淘汰） |
| `LongTermStore.kt` | 长期记忆（KV 固化，7 态生命周期，MVP 内存版） |
| `MemoryService.kt` | 记忆编排：记 / 理（四态裁定）/ 忆 / 清 + 敏感拦截红线 |

## 构建

```bash
cd memory
./gradlew build            # 在线（CI，组合引用 contract）
# 离线：
JAVA_HOME=<jdk17> <gradle-9.6.0>/bin/gradle build --offline --no-daemon --console=plain
```

## 依赖（必要的调用）

仅依赖契约工程 `contract`（通过 `includeBuild` 组合引用）。不依赖 context / master-agent。

## 测试

`MemoryServiceTest`（5 项）：记/忆、同内容 NOOP、变更覆盖、**敏感拦截**、一键清除。

## IDE

IntelliJ IDEA：`File → Open` 选择本目录，Gradle JVM 选 JDK 17。
