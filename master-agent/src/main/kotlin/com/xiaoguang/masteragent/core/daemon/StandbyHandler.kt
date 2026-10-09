/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：StandbyHandler —— 主备切换：PRIMARY 故障时 STANDBY 升级 TAKEOVER，保证快道闭环不中断。
 */

package com.xiaoguang.masteragent.core.daemon

enum class Role { PRIMARY, STANDBY, TAKEOVER }

class StandbyHandler(initial: Role = Role.PRIMARY) {

    @Volatile
    private var role: Role = initial

    fun current(): Role = role

    /** 主节点故障 → 备节点接管 */
    fun takeover(): Role {
        role = Role.TAKEOVER
        return role
    }

    /** 降级为备机 */
    fun standby(): Role {
        role = Role.STANDBY
        return role
    }
}
