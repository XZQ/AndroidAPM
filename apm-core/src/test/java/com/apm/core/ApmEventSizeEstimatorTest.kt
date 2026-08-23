package com.apm.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * [ApmEventSizeEstimator] 单元测试。
 *
 * 重点回归：`constantRetentionBytes` + 变量部分重载必须与完整 [ApmEventSizeEstimator.estimate]
 * 逐字节等值，否则 emit 热路径的预算准入决策会因缓存常量而漂移。
 */
class ApmEventSizeEstimatorTest {

    /** 预计算常量 + 变量部分与完整估算在任何输入组合下都必须逐值相等。 */
    @Test
    fun `constant base plus variable parts equals full estimate`() {
        val processName = "com.example.app:sdk"
        val defaultContext = mapOf(
            "app_version" to "1.2.3",
            "channel" to "official",
            "device" to "walleye"
        )
        val constantBase = ApmEventSizeEstimator.constantRetentionBytes(processName, defaultContext)

        val cases = listOf(
            Triple("memory", "memory_snapshot", null as String?),
            Triple("network", "http_request", "MainActivity"),
            Triple("crash", "java_crash", "Fragment\$settings\$inner")
        )
        for ((module, name, scene) in cases) {
            for (fields in fieldMaps()) {
                for (bizContext in listOf(emptyMap<String, String>(), mapOf("tenant" to "acme"))) {
                    for (extras in listOf(emptyMap<String, String>(), mapOf("debug" to "1"))) {
                        val threadName = "apm-test-worker"
                        val full = ApmEventSizeEstimator.estimate(
                            module = module,
                            name = name,
                            processName = processName,
                            threadName = threadName,
                            scene = scene,
                            fields = fields,
                            primaryContext = defaultContext,
                            secondaryContext = bizContext,
                            extras = extras
                        )
                        val split = ApmEventSizeEstimator.estimate(
                            module = module,
                            name = name,
                            threadName = threadName,
                            scene = scene,
                            fields = fields,
                            constantBaseBytes = constantBase,
                            secondaryContext = bizContext,
                            extras = extras
                        )
                        assertEquals("estimate must be identical for module=$module name=$name", full, split)
                    }
                }
            }
        }
    }

    /** 空常量部分也保持等值（空 processName / 空 defaultContext）。 */
    @Test
    fun `empty constant inputs still match full estimate`() {
        val constantBase = ApmEventSizeEstimator.constantRetentionBytes("", emptyMap())
        val full = ApmEventSizeEstimator.estimate(
            module = "m", name = "n", processName = "", threadName = "t",
            scene = null, fields = emptyMap(), primaryContext = emptyMap(),
            secondaryContext = emptyMap(), extras = emptyMap()
        )
        val split = ApmEventSizeEstimator.estimate(
            module = "m", name = "n", threadName = "t", scene = null,
            fields = emptyMap(), constantBaseBytes = constantBase,
            secondaryContext = emptyMap(), extras = emptyMap()
        )
        assertEquals(full, split)
    }

    /** 覆盖所有受支持的标量类型的字段 map 集合。 */
    private fun fieldMaps(): List<Map<String, Any?>> = listOf(
        emptyMap(),
        mapOf("count" to 3),
        mapOf("ratio" to 0.5, "big" to BigInteger("123456789012345678901234567890")),
        mapOf(
            "price" to BigDecimal("19.99"),
            "label" to "metric",
            "flag" to true,
            "letter" to 'x',
            "absent" to null,
            "hostObject" to Any()
        )
    )
}
