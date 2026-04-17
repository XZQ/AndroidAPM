# CMakeLists.txt for apm_crash JNI library
# 构建信号处理器 native 库 libapm_crash.so

cmake_minimum_required(VERSION 3.18.1)

project(apm_crash)

# 构建共享库
add_library(
    apm_crash
    SHARED
    apm_crash_jni.c
)

# 链接 Android 日志库和动态链接库（用于 dladdr）
find_library(log-lib log)
target_link_libraries(
    apm_crash
    ${log-lib}
    dl
)
