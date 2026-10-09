/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Main —— 端侧可运行演示入口：演示快道闭环 / 澄清 / 兜底三条路径。
 */

package com.xiaoguang.masteragent.app

import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.Input
import com.xiaoguang.masteragent.core.bus.IntentMessage
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val a = Assembly()
    println("==== 整车智能中枢（Master Agent）端侧 MVP 演示 ====")

    // 演示指令集：覆盖执行 / 兜底 两类路径
    val demos = listOf(
        //"导航到天安门，打开空调，左车窗打开一半，帮我点杯咖啡",
        "打开空调",
        "把车窗关起来",
        "播放音乐",
        "帮我导航回家",
        "还有多少续航", // 能源生态域（迭代 2）
        "帮我升空", // 未命中任何域 → 兜底降级
    )

    demos.forEach { u ->
        val msg = IntentMessage(
            header = Header(messageId = "m-${u.hashCode()}", traceId = "trace-1", sessionId = "session-1"),
            input = Input(utterance = u),
        )
        val out = a.master.run(msg)
        val route = out.arbitration?.route ?: "-"
        val reply = out.result?.reply ?: "-"
        println("%-14s -> %-9s | %s".format(u, route, reply))
    }

    println("==== 演示结束 ====")
}
