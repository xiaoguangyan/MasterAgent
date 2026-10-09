/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：core:bus 枚举契约 —— 阶段状态机、快慢通道、归属关系、执行链路步骤与状态、仲裁路由。
 */

package com.xiaoguang.masteragent.core.bus

/** 任务阶段状态机：INPUT → INTENT → ARBITRATE → PLAN → EXECUTE → RESULT */
enum class Stage { INPUT, INTENT, ARBITRATE, PLAN, EXECUTE, RESULT }

/** 快慢双通道：快道端侧本地闭环，慢道上云大模型 */
enum class Channel { FAST, SLOW }

/** 域 Agent 归属关系：命令 / 协商 / 上报 */
enum class RelationType { COMMAND, NEGOTIATE, REPORT }

/** 执行链路步骤（供 ExecutionTrace 旁路采集打点） */
enum class Step { RUN, COMPOSE, ARBITRATE, PLAN, EXECUTE, GUARD, RESUME }

/** 执行状态 */
enum class Status { OK, ERROR, DEGRADED }

/** 仲裁路由结果：执行 / 澄清确认 / 兜底降级 */
enum class Route { EXECUTE, CONFIRM, FALLBACK }
