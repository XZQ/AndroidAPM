cmake_minimum_required(VERSION 3.18)
project(apm-io)

# 编译 apm-io 共享库
add_library(apm-io SHARED apm_io_jni.c)

# 链接 Android log 库（用于 __android_log_print）
target_link_libraries(apm-io log)
