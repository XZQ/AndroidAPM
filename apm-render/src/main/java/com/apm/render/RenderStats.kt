package com.apm.render

/**
 * 渲染统计数据。
 * 一个 Activity 页面的 View 层级和绘制信息。
 */
data class RenderStats(
    /** View 总数。 */
    val viewCount: Int = 0,
    /** 最大层级深度。 */
    val maxDepth: Int = 0,
    /** 页面名。 */
    val activityName: String = "",
    /** 采样时间戳。 */
    val timestamp: Long = System.currentTimeMillis()
)
