/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：SafetyGateway —— 安全网关：模型提案 / 网关执行分离，非白名单即拦。
 *       判定：危险工具 BLOCK / 高风险 CONFIRM / 白名单 PASS。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.ToolCall

/** 网关判定 */
enum class GateVerdict { PASS, CONFIRM, BLOCK }

data class GateDecision(val verdict: GateVerdict, val reason: String = "")

class SafetyGateway(private val whitelist: Set<String> = DEFAULT_WHITELIST) {

    fun check(toolCalls: List<ToolCall>): GateDecision {
        for (tc in toolCalls) {
            when {
                tc.tool in DANGEROUS_TOOLS -> return GateDecision(GateVerdict.BLOCK, "危险工具: ${tc.tool}")
                tc.tool in HIGH_RISK_TOOLS -> return GateDecision(GateVerdict.CONFIRM, "高风险工具需确认: ${tc.tool}")
                tc.tool !in whitelist -> return GateDecision(GateVerdict.BLOCK, "非白名单工具: ${tc.tool}")
            }
        }
        return GateDecision(GateVerdict.PASS)
    }

    companion object {
        /** 白名单（座舱 6 域工具，含能源只读） */
        val DEFAULT_WHITELIST = setOf("set_ac", "set_window", "set_seat", "play_media", "start_nav", "check_energy")

        /** 危险工具（直接 BLOCK，红线） */
        val DANGEROUS_TOOLS = setOf("set_gear", "set_brake", "unlock_door")

        /** 高风险工具（需二次确认，MVP 暂无） */
        val HIGH_RISK_TOOLS = emptySet<String>()
    }
}
