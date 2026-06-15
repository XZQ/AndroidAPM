package com.apm.consumer.smoke

import com.apm.core.ApmModule
import com.apm.memory.MemoryModule
import com.apm.model.ApmEvent
import com.apm.network.NetworkModule
import com.apm.otel.OtelConfig
import com.apm.otel.OtelEventBridge

/**
 * Compile-only consumer that verifies published public API dependencies.
 */
object ConsumerSmoke {
    /**
     * References representative APIs from multiple published artifacts.
     *
     * @return modules and mapped telemetry data
     */
    fun verify(): Pair<List<ApmModule>, OtelEventBridge.ExportResult> {
        val modules: List<ApmModule> = listOf(MemoryModule(), NetworkModule())
        val bridge = OtelEventBridge(OtelConfig(serviceName = "consumer-smoke"))
        return modules to bridge.export(ApmEvent(module = "smoke", name = "compile"))
    }
}
