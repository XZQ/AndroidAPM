cmake_minimum_required(VERSION 3.18)
project(apm-io)

# 编译 apm-io 共享库。xhook 通过 dlopen/dlsym 动态解析，未集成时 Java 层自动降级。
add_library(apm-io SHARED apm_io_jni.c)

# 链接 Android log/dl 库（用于 __android_log_print 与动态符号解析）
target_link_libraries(apm-io log dl)
