/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ConfidenceFusion —— 置信度融合：score = 0.30·base + 0.40·llm + 0.15·ctx + 0.15·slot。
 *       llm 缺省（端侧无慢道）时按剩余权重 0.60 归一化，避免置信度被系统性拉低。
 */

package com.xiaoguang.masteragent.core.model

import com.xiaoguang.masteragent.core.bus.ConfidenceBreakdown

/** 融合结果：综合分 + 四项分解（用于审计与可视化） */
data class FusionResult(val score: Double, val breakdown: ConfidenceBreakdown)

class ConfidenceFusion {

    /**
     * 融合四项信号：
     * @param base 快道基础置信度 0–1
     * @param llm  慢道大模型置信度 0–1（null 表示端侧无慢道）
     * @param ctx  上下文相关度 0–1
     * @param slot 槽位完整度 0–1
     */
    fun fuse(base: Double, llm: Double?, ctx: Double, slot: Double): FusionResult {
        val breakdown = ConfidenceBreakdown(base = base, llm = llm ?: 0.0, ctx = ctx, slot = slot)
        val score = if (llm == null) {
            (0.30 * base + 0.15 * ctx + 0.15 * slot) / 0.60
        } else {
            0.30 * base + 0.40 * llm + 0.15 * ctx + 0.15 * slot
        }
        return FusionResult(score, breakdown)
    }
}
