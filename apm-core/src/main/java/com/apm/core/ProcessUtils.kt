package com.apm.core

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 获取当前进程名。API 28+ 使用系统 API，低版本遍历进程列表。
 * @receiver 任意 Context
 * @return 当前进程名；低版本查询不到时回退为包名（主进程名），绝不返回空字符串，
 *   避免主进程被误判为非主进程而在 MAIN_PROCESS_ONLY 策略下静默跳过初始化。
 */
fun Context.currentProcessNameCompat(): String {
    // API 28+ 直接获取，无需遍历进程列表
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return Application.getProcessName()
    }
    // 低版本：通过 ActivityManager 查询当前 PID 对应的进程名。
    // 部分 OEM/低内存场景会返回 null 或空列表；此时回退包名而非空串，
    // 否则 isMainProcessCompat() 在主进程返回 false，导致整个 SDK 被跳过。
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val currentPid = Process.myPid()
    val queried = activityManager?.runningAppProcesses
        ?.firstOrNull { it.pid == currentPid }
        ?.processName
    return if (queried.isNullOrEmpty()) packageName else queried
}

/**
 * 判断当前是否为主进程。
 * 主进程名 = 包名，子进程名格式为 "包名:xxx"。
 */
fun Context.isMainProcessCompat(): Boolean {
    return packageName == currentProcessNameCompat()
}
