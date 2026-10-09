/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：RelationAudit —— 归属关系 + 权限审计（迭代 2）：按域 Agent 归属关系约束可用工具。
 *       REPORT（上报）域仅允许只读工具；COMMAND / NEGOTIATE 域允许命令类工具。
 *       与 SafetyGateway（白名单/危险工具）互补：本组件约束「归属语义」，网关约束「安全边界」。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.RelationType

/** 审计判定 */
enum class AuditVerdict { ALLOW, BLOCK }

class RelationAudit(
    private val reportTools: Set<String> = DEFAULT_REPORT_TOOLS,
    private val commandTools: Set<String> = DEFAULT_COMMAND_TOOLS,
) {

    /** 按归属关系审计某工具调用：越权即 BLOCK */
    fun audit(relation: RelationType, tool: String): AuditVerdict = when (relation) {
        // 上报域只读：不得下发控制类命令（越权）
        RelationType.REPORT -> if (tool in reportTools) AuditVerdict.ALLOW else AuditVerdict.BLOCK
        // 命令 / 协商域：允许命令类 + 只读工具
        RelationType.COMMAND, RelationType.NEGOTIATE ->
            if (tool in commandTools || tool in reportTools) AuditVerdict.ALLOW else AuditVerdict.BLOCK
    }

    companion object {
        /** 只读（上报）工具 */
        val DEFAULT_REPORT_TOOLS = setOf("check_energy", "read_state", "query_nav")

        /** 命令类工具（座舱 6 域，含能源） */
        val DEFAULT_COMMAND_TOOLS = setOf(
            "set_ac", "set_window", "set_seat", "play_media", "start_nav", "set_eco_mode",
        )
    }
}
