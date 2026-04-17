# CMakeLists.txt for apm_dumper JNI library
# 构建子进程 fork dump 库 libapm_dumper.so

cmake_minimum_required(VERSION 3.18.1)

project(apm_dumper)

# 构建共享库
add_library(
    apm_dumper
    SHARED
    apm_dumper_jni.c
)

# 链接 Android 日志库
find_library(log-lib log)
target_link_libraries(
    apm_dumper
    ${log-lib}
)
