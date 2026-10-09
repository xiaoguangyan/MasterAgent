/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：AnaphoraResolver —— 指代消解（迭代 2）：多轮对话中「它 / 再 / 还 / 继续」等指代词
 *       回指上一轮已消歧的域，避免重复澄清。
 */

package com.xiaoguang.masteragent.feature.master

class AnaphoraResolver(
    private val anaphoraWords: Set<String> = DEFAULT_ANAPHORA,
) {

    /**
     * 指代消解：当前句含指代词且存在上一轮域时，回指上一轮域。
     * @return 上一轮域（命中指代）；否则 null（无指代，走正常解析）
     */
    fun resolve(current: String, previousDomain: String?): String? {
        if (previousDomain == null) return null
        return previousDomain.takeIf { anaphoraWords.any { current.contains(it) } }
    }

    companion object {
        val DEFAULT_ANAPHORA = setOf("它", "这个", "那个", "再", "还", "继续", "其")
    }
}
