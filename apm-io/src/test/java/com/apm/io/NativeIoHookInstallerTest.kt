package com.apm.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native IO Hook 安装器测试。
 */
class NativeIoHookInstallerTest {

    /** JNI 库缺失时应返回 false 让调用方降级到 Java 代理。 */
    @Test
    fun `install returns false when native library is missing`() {
        val installer = NativeIoHookInstaller(
            loadLibrary = { throw UnsatisfiedLinkError("missing") },
            installHooks = { error("install should not run") },
            uninstallHooks = { error("uninstall should not run") }
        )

        assertFalse(installer.install())
    }

    /** 安装成功后卸载也应执行。 */
    @Test
    fun `uninstall runs only after successful install`() {
        var uninstalled = false
        val installer = NativeIoHookInstaller(
            loadLibrary = { },
            installHooks = { },
            uninstallHooks = { uninstalled = true }
        )

        assertTrue(installer.install())
        assertTrue(installer.uninstall())
        assertTrue(uninstalled)
    }
}
