/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：SliceEngine —— 声明式上下文切片引擎（L2 标准级）。
 *       按 SliceSpec 声明式产出 ContextSlice：层过滤 → 字段白名单 → 角色权限（FR-SLC-02）
 *       → 敏感脱敏（FR-SLC-03）→ token 预算裁剪（FR-SLC-04）→ 长期记忆注入（FR-SLC-05）
 *       → 回退链（FR-CTX 降级）→ 溯源（FR-SLC-07）。
 *       纯函数式（入参即状态快照），便于单测与确定性验证。
 */

package com.xiaoguang.masteragent.core.context

import com.xiaoguang.masteragent.core.bus.ContextSlice
import com.xiaoguang.masteragent.core.bus.IMemoryService
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RealtimeState
import com.xiaoguang.masteragent.core.bus.SliceField
import com.xiaoguang.masteragent.core.bus.SliceSpec

object SliceEngine {

    /** 副驾角色受限字段（FR-SLC-02）：driver 全量；passenger 过滤驾驶控制类字段 */
    private val PASSENGER_RESTRICTED = setOf("speed", "gear", "seat")

    /** 手机号 / 身份证号（FR-SLC-03 脱敏） */
    private val PHONE_RE = Regex("1[3-9]\\d{9}")
    private val ID_RE = Regex("\\d{17}[0-9Xx]")

    /** 记忆注入条数上限（FR-SLC-05 / FR-CTX-05：≤5 条） */
    private const val MAX_INJECTED = 5

    /** L2 实时车态 → 键值（字段级权限 / 白名单作用对象） */
    private fun l2Fields(state: RealtimeState): List<Pair<String, String>> = listOf(
        "speed" to state.speed.toString(),
        "temp" to state.temp.toString(),
        "seat" to state.seat,
        "gear" to state.gear,
        "window" to state.window,
        "ac" to state.ac.toString(),
    )

    /**
     * 声明式切片：入参为上下文状态快照（环形窗口 + 实时车态 + 记忆服务），产物确定、幂等。
     *
     * @param realtime 实时车态；传 null 表示 L2 源缺失 → 触发回退链（FR-CTX 降级到 L1）
     */
    suspend fun slice(
        spec: SliceSpec,
        ringWindow: List<IntentMessage>,
        realtime: RealtimeState?,
        memory: IMemoryService?,
    ): ContextSlice {
        val utterances = ringWindow.map { it.input.utterance }

        // 1) 组装各层候选字段（回退链：L2 源缺失时以 L1 环形窗口兜底，FR-CTX 降级）
        val fallbackL2 = realtime == null && "L2" in spec.layers
        val layerFields = LinkedHashMap<String, List<Pair<String, String>>>()
        layerFields["L0"] = listOf("rounds" to "1", "last" to utterances.lastOrNull().orEmpty())
        layerFields["L1"] = listOf(
            "windowN" to ringWindow.size.toString(),
            "ring" to utterances.joinToString(" | "),
        )
        layerFields["L2"] = if (realtime != null) l2Fields(realtime)
            else listOf("fallback" to "L1:${utterances.joinToString(" | ")}")
        layerFields["L3"] = listOf(
            "tokenBudget" to spec.tokenBudget.toString(),
            "used" to ringWindow.size.toString(),
        )
        layerFields["L4"] = listOf("version" to "v1", "snapshotRef" to "")

        // 2) 层过滤 + 字段白名单 + 角色权限（FR-SLC-02）
        val fields = mutableListOf<SliceField>()
        val dropped = mutableListOf<String>()   // 权限过滤掉的字段（溯源用，不计入 redacted）
        for (layer in spec.layers) {
            val candidates = layerFields[layer] ?: continue
            val whitelist = spec.fields[layer]      // null / 空 = 该层全量
            for ((key, value) in candidates) {
                if (whitelist != null && whitelist.isNotEmpty() && key !in whitelist) continue
                if (spec.role == "passenger" && layer == "L2" && key in PASSENGER_RESTRICTED) {
                    dropped += "$layer.$key"; continue
                }
                fields += SliceField(layer = layer, key = key, value = value)
            }
        }

        // 3) 敏感脱敏（FR-SLC-03）：手机 / 证件号掩码，标记 redacted
        val redacted = mutableListOf<String>()
        val deidentified = fields.map { f ->
            val masked = maskSensitive(f.value)
            if (masked != f.value) {
                redacted += "${f.layer}.${f.key}"
                f.copy(value = masked, redacted = true)
            } else f
        }

        // 4) token 预算裁剪（FR-SLC-04）：1 字符 ≈ 1 token，超限从尾部裁剪并溯源
        var used = 0
        val kept = mutableListOf<SliceField>()
        var trimming = false
        for (f in deidentified) {
            val cost = f.value.length.coerceAtLeast(1)
            if (trimming || used + cost > spec.tokenBudget) {
                trimming = true
                redacted += "${f.layer}.${f.key}"   // 裁剪字段一并溯源
                continue
            }
            used += cost
            kept += f
        }

        // 5) 长期记忆注入（FR-SLC-05 / FR-CTX-05，≤5 条）
        val injected = if (memory == null) emptyList<String>() else {
            val q = utterances.lastOrNull().orEmpty()
            memory.recall(q, MAX_INJECTED).map { it.memoryId }
        }

        // 6) 溯源（FR-SLC-07）：来源角色/层/预算 + 权限/脱敏/裁剪/注入/回退统计
        val provenance = buildString {
            append("role=${spec.role}")
            append(" layers=${spec.layers.joinToString(",")}")
            append(" budget=${spec.tokenBudget}")
            append(" kept=${kept.size}")
            append(" dropped=${dropped.size}")
            append(" redacted=${redacted.size}")
            append(" injected=${injected.size}")
            if (fallbackL2) append(" fallback=L2->L1")
        }

        return ContextSlice(
            sliceRef = "slc-" + System.nanoTime().toString(16),
            fields = kept,
            tokenCount = used,
            budget = spec.tokenBudget,
            injectedMemories = injected,
            redacted = redacted,
            provenance = provenance,
        )
    }

    /** 手机 / 证件号掩码（先证件后手机，避免 18 位证件被 11 位手机正则误截） */
    private fun maskSensitive(value: String): String =
        value.replace(ID_RE, "***").replace(PHONE_RE, "***")
}
