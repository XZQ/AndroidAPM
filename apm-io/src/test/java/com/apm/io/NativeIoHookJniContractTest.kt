package com.apm.io

import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NativeIoHook 与 JNI 层（apm_io_jni.c）之间的方法契约测试。
 *
 * JNI 侧通过 GetStaticMethodID 查找回调方法，任何把回调改回实例方法、
 * 改名或改签名的重构都会让 JNI_OnLoad 失败并使 Native Hook 静默降级，
 * 因此用反射在 JVM 测试中锁定该契约。
 */
class NativeIoHookJniContractTest {

    /** 验证 onNativeIoEvent 以 (String, String, long, long, boolean) 的静态方法形式存在。 */
    @Test
    fun onNativeIoEventIsStaticWithExpectedSignature() {
        // 与 apm_io_jni.c 中 CALLBACK_METHOD_SIG "(Ljava/lang/String;Ljava/lang/String;JJZ)V" 对应
        val method = NativeIoHook::class.java.getDeclaredMethod(
            JNI_CALLBACK_NAME,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )

        // GetStaticMethodID 只接受静态方法
        assertTrue(
            "onNativeIoEvent must be static for JNI GetStaticMethodID lookup",
            Modifier.isStatic(method.modifiers)
        )
    }

    companion object {
        /** JNI 层查找的回调方法名，须与 apm_io_jni.c 保持一致。 */
        private const val JNI_CALLBACK_NAME = "onNativeIoEvent"
    }
}
