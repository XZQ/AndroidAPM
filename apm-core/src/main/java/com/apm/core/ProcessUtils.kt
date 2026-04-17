package com.apm.core

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 获取当前进程名。API 28+ 使用系统 API，低版本遍历进程列表。
 * @receiver 任意 Context
 * @return 当前进程名，获取失败返回空字符串
 */
fun Context.currentProcessNameCompat(): String {
    // API 28+ 直接获取，无需遍历进程列表
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return Application.getProcessName()
    }
    // 低版本：通过 ActivityManager 查询当前 PID 对应的进程名
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val currentPid = Process.myPid()
    return activityManager.runningAppProcesses
        ?.firstOrNull { it.pid == currentPid }
        ?.processName
        .orEmpty()
}

/**
 * 判断当前是否为主进程。
 * 主进程名 = 包名，子进程名格式为 "包名:xxx"。
 */
fun Context.isMainProcessCompat(): Boolean {
    return packageName == currentProcessNameCompat()
}
