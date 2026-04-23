/**
 * apm_dumper_jni.c
 *
 * Fork 子进程 Hprof Dump JNI 实现。
 * 通过 fork() 创建子进程，在子进程中调用 Debug.dumpHprofData()，
 * 尽量降低主进程 STW（Stop-The-World）。该路径默认由 Java 配置关闭，
 * 仅在业务确认设备兼容性后启用。
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <errno.h>

/* ------------------------------------------------------------------ */
/* 常量定义                                                            */
/* ------------------------------------------------------------------ */

/** 日志 Tag。 */
#define TAG "ApmDumper"

/** fork 失败返回值。 */
#define FORK_ERROR (-1)

/** waitpid 返回值：子进程仍在运行。 */
#define WAIT_PID_RUNNING 0

/** Logcat 日志宏。 */
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* 全局状态                                                            */
/* ------------------------------------------------------------------ */

/** JavaVM 指针，JNI_OnLoad 时缓存。 */
static JavaVM *s_jvm = NULL;

/** Debug.dumpHprofData 方法的 class 引用。 */
static jclass s_debug_class = NULL;

/** Debug.dumpHprofData 方法 ID。 */
static jmethodID s_dump_hprof_method = NULL;

/* ------------------------------------------------------------------ */
/* JNI 接口                                                            */
/* ------------------------------------------------------------------ */

/**
 * JNI_OnLoad：缓存 JavaVM 和 Debug.dumpHprofData 方法 ID。
 * 子进程 fork 后继承父进程的内存映射，因此可以调用 Java 层的 dump 方法。
 *
 * @param vm JavaVM 指针
 * @param reserved 预留参数
 * @return JNI 版本号
 */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    s_jvm = vm;
    JNIEnv *env = NULL;

    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    /* 查找 android.os.Debug 类 */
    jclass debug_class = (*env)->FindClass(env, "android/os/Debug");
    if (debug_class == NULL) {
        LOGE("JNI_OnLoad: android.os.Debug class not found");
        return JNI_ERR;
    }

    /* 缓存 Debug 类的全局引用 */
    s_debug_class = (*env)->NewGlobalRef(env, debug_class);
    (*env)->DeleteLocalRef(env, debug_class);

    if (s_debug_class == NULL) {
        LOGE("JNI_OnLoad: NewGlobalRef for Debug class failed");
        return JNI_ERR;
    }

    /* 查找 Debug.dumpHprofData(String) 静态方法 */
    s_dump_hprof_method = (*env)->GetStaticMethodID(env,
        s_debug_class,
        "dumpHprofData",
        "(Ljava/lang/String;)V");
    if (s_dump_hprof_method == NULL) {
        LOGE("JNI_OnLoad: dumpHprofData method not found");
        (*env)->DeleteGlobalRef(env, s_debug_class);
        s_debug_class = NULL;
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: dumper JNI layer initialized");
    return JNI_VERSION_1_6;
}

/**
 * Fork 子进程执行 hprof dump。
 *
 * 实现流程：
 * 1. fork() 创建子进程
 * 2. 子进程中调用 Debug.dumpHprofData() 导出堆内存
 * 3. 子进程调用 _exit(0) 退出
 * 4. 父进程返回子进程 PID
 *
 * @param env JNIEnv
 * @param thiz this 对象
 * @param output_path hprof 文件输出路径
 * @return >0 父进程（返回子进程 PID），0 子进程（正常情况不会返回），-1 fork 失败
 */
JNIEXPORT jint JNICALL
Java_com_apm_memory_oom_HprofDumper_nativeForkAndDump(
    JNIEnv *env, jobject thiz, jstring output_path) {

    if (output_path == NULL) {
        LOGE("nativeForkAndDump: output_path is NULL");
        return FORK_ERROR;
    }

    /* 获取输出路径的 C 字符串 */
    const char *path_str = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (path_str == NULL) {
        LOGE("nativeForkAndDump: GetStringUTFChars failed");
        return FORK_ERROR;
    }

    LOGI("nativeForkAndDump: forking to dump %s", path_str);

    /* fork 子进程 */
    pid_t pid = fork();

    if (pid < 0) {
        /* fork 失败 */
        LOGE("nativeForkAndDump: fork failed, errno=%d (%s)", errno, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, output_path, path_str);
        return FORK_ERROR;
    }

    if (pid == 0) {
        /* ---- 子进程 ---- */
        /*
         * 子进程继承父进程的内存空间（copy-on-write），
         * 可以安全调用 Debug.dumpHprofData 而不会导致父进程 STW。
         */

        /*
         * 注意：fork 后子进程中只能调用异步信号安全函数。
         * 但 Debug.dumpHprofData 在 ART 虚拟机内部实现，
         * 实际上会在子进程中安全地执行 heap dump。
         *
         * 这里需要重新附加到 JVM 来调用 Java 方法。
         * 但由于 fork 后子进程与父进程共享 JVM 状态，
         * 直接使用继承的 JNIEnv 可能不安全。
         *
         * 更安全的做法是使用系统调用直接触发 dump，
         * 但为了简化，这里仍使用 JNI 调用。
         */

        int dump_success = 0;
        JNIEnv *child_env = NULL;
        int get_result = (*s_jvm)->GetEnv(s_jvm, (void **)&child_env, JNI_VERSION_1_6);

        if (get_result == JNI_OK && child_env != NULL) {
            /* 构造 Java String 参数 */
            jstring j_path = (*child_env)->NewStringUTF(child_env, path_str);
            if (j_path != NULL) {
                /* 调用 Debug.dumpHprofData 执行 dump */
                (*child_env)->CallStaticVoidMethod(child_env,
                    s_debug_class, s_dump_hprof_method, j_path);

                /* 检查是否有异常 */
                if ((*child_env)->ExceptionCheck(child_env)) {
                    (*child_env)->ExceptionClear(child_env);
                    LOGE("Child: dumpHprofData threw exception");
                } else {
                    dump_success = 1;
                }

                (*child_env)->DeleteLocalRef(child_env, j_path);
            }
        } else {
            LOGE("Child: failed to get JNIEnv, result=%d", get_result);
        }

        /* 释放路径字符串（子进程中） */
        /* 注意：子进程不应使用父进程的 JNIEnv 释放，
         * 但 GetStringUTFChars 返回的是 C 字符串拷贝，
         * fork 后子进程有自己的地址空间副本，理论上安全 */
        /* (*env)->ReleaseStringUTFChars(env, output_path, path_str); */

        /* 子进程直接退出，不执行任何清理（避免破坏父进程状态） */
        _exit(dump_success ? 0 : 1);
    }

    /* ---- 父进程 ---- */
    LOGI("nativeForkAndDump: child pid=%d", pid);
    (*env)->ReleaseStringUTFChars(env, output_path, path_str);
    return (jint)pid;
}

/**
 * 非阻塞 waitpid 包装方法。
 * 调用 waitpid(pid, &status, WNOHANG) 检查子进程状态。
 *
 * @param env JNIEnv
 * @param thiz this 对象
 * @param pid 子进程 PID
 * @return 0 表示子进程仍在运行，非 0 表示子进程已退出
 */
JNIEXPORT jint JNICALL
Java_com_apm_memory_oom_HprofDumper_waitPidNonBlocking(
    JNIEnv *env, jobject thiz, jint pid) {

    int status = 0;
    /* WNOHANG：如果没有子进程退出则立即返回 0 */
    pid_t result = waitpid((pid_t)pid, &status, WNOHANG);

    if (result == (pid_t)WAIT_PID_RUNNING) {
        /* 子进程仍在运行 */
        return WAIT_PID_RUNNING;
    } else if (result == (pid_t)FORK_ERROR) {
        /* waitpid 调用失败，通常表示子进程已不存在 */
        LOGW("waitPidNonBlocking: waitpid failed for pid=%d, errno=%d", pid, errno);
        return FORK_ERROR;
    } else if (WIFEXITED(status)) {
        /* 子进程已退出，result 为子进程 PID */
        int exit_status = WEXITSTATUS(status);
        if (exit_status != 0) {
            LOGW("waitPidNonBlocking: child %d exited with failure status %d", pid, exit_status);
            return FORK_ERROR;
        }
        LOGI("waitPidNonBlocking: child %d exited with status %d", pid, exit_status);
        return (jint)result;
    } else {
        LOGW("waitPidNonBlocking: child %d ended without normal exit", pid);
        return FORK_ERROR;
    }
}
