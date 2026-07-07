package com.apm.crash

import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NativeCrashMonitor 与 JNI 层（apm_crash_jni.c）之间的方法契约测试。
 *
 * JNI 侧通过 GetStaticMethodID 查找 logNativeCrashSignal，
 * 若 @JvmStatic 被移除，JNI_OnLoad 会失败并使 Native 崩溃采集
 * 静默降级为 tombstone-only，因此用反射锁定该契约。
 */
class NativeCrashMonitorJniContractTest {

    /** 验证 logNativeCrashSignal 以 (int, String, String, String) 的静态方法形式存在。 */
    @Test
    fun logNativeCrashSignalIsStaticWithExpectedSignature() {
        // 与 apm_crash_jni.c 中签名 "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V" 对应
        val method = NativeCrashMonitor::class.java.getDeclaredMethod(
            JNI_CALLBACK_NAME,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java
        )

        // GetStaticMethodID 只接受静态方法
        assertTrue(
            "logNativeCrashSignal must be static for JNI GetStaticMethodID lookup",
            Modifier.isStatic(method.modifiers)
        )
    }

    companion object {
        /** JNI 层查找的回调方法名，须与 apm_crash_jni.c 保持一致。 */
        private const val JNI_CALLBACK_NAME = "logNativeCrashSignal"
    }
}
