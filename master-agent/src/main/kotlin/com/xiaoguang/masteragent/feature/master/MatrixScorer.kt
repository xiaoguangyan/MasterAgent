/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：MatrixScorer —— 场景矩阵评分：按域先验偏好打分（0–100），未知域给低分兜底。
 */

package com.xiaoguang.masteragent.feature.master

class MatrixScorer(private val matrix: Map<String, Double> = DEFAULT_MATRIX) {

    fun score(domain: String?): Double = matrix[domain] ?: UNKNOWN_SCORE

    companion object {
        const val UNKNOWN_SCORE = 20.0

        /** 场景先验矩阵（HLD §9.3）：座舱 5 域默认分 */
        val DEFAULT_MATRIX = mapOf(
            "climate" to 85.0,
            "window" to 82.0,
            "seat" to 80.0,
            "media" to 78.0,
            "navigation" to 80.0,
            "energy" to 72.0,
        )
    }
}
