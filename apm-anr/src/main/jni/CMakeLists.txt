# CMakeLists.txt for apm-anr JNI library
# 构建 SIGQUIT 信号检测 native 库 libapm-anr.so
# （库名须与 Kotlin 侧 System.loadLibrary("apm-anr") 一致）

cmake_minimum_required(VERSION 3.18.1)

project(apm_anr)

# 构建共享库
add_library(
    apm-anr
    SHARED
    apm_anr_jni.c
)

# Android 15+ devices may use 16 KB pages; align load segments for compatibility.
target_link_options(apm-anr PRIVATE "-Wl,-z,max-page-size=16384")

# 链接 Android 日志库
find_library(log-lib log)
target_link_libraries(
    apm-anr
    ${log-lib}
)
