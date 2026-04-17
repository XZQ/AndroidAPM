package com.didi.apm.memory.leak

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel

/**
 * ViewModel 泄漏检测器。
 * 通过反射检查 ViewModel 的字段是否持有 Context 或 View 引用。
 * 这些引用会导致 Activity/Fragment 无法被回收。
 */
internal class ViewModelLeakDetector {

    /**
     * 检查单个 ViewModel 是否存在泄漏风险。
     * 遍历 ViewModel 的所有声明字段，检查是否持有 Context 或 View。
     *
     * @param viewModel 要检查的 ViewModel
     * @return 泄漏检测结果，无风险返回 null
     */
    fun checkViewModel(viewModel: ViewModel): LeakResult? {
        val suspectFields = mutableListOf<String>()
        viewModel.javaClass.declaredFields.forEach { field ->
            // 设置可访问以读取私有字段
            field.isAccessible = true
            val value = runCatching { field.get(viewModel) }.getOrNull() ?: return@forEach
            // 检查是否持有 Context 或 View 引用（泄漏风险）
            if (value is Context || value is View) {
                suspectFields += "${field.name}: ${value.javaClass.name}"
            }
        }
        return if (suspectFields.isNotEmpty()) {
            LeakResult(leakClass = viewModel.javaClass.name, type = LeakType.VIEW_MODEL, suspectFields = suspectFields)
        } else null
    }
}
