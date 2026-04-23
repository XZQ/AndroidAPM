/**
 * apm_crash_jni.c
 *
 * Native 崩溃信号处理器 JNI 实现。
 * 拦截 SIGSEGV、SIGABRT、SIGBUS、SIGFPE、SIGPIPE、SIGSTKFLT 信号，
 * 默认只恢复原始处理器并重抛，让系统生成 tombstone 后由 Java 层下次启动解析。
 * 调试环境可显式开启不安全 Java 回调，以捕获调用栈和故障地址后上报。
 */

#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/wait.h>
#include <stdio.h>
#include <dlfcn.h>
#include <errno.h>
#include <sys/prctl.h>

/* ------------------------------------------------------------------ */
/* 常量定义                                                            */
/* ------------------------------------------------------------------ */

/** 日志 Tag。 */
#define TAG "ApmCrash"

/** 最大回溯帧数。 */
#define MAX_BACKTRACE_FRAMES 64

/** 线程名最大长度。 */
#define MAX_THREAD_NAME_LEN 64

/** 信号名称最大长度。 */
#define MAX_SIGNAL_NAME_LEN 32

/** 故障地址字符串最大长度。 */
#define MAX_FAULT_ADDR_LEN 32

/** 回溯字符串最大长度。 */
#define MAX_BACKTRACE_STR_LEN 4096

/** 需要拦截的信号数量。 */
#define NUM_HANDLED_SIGNALS 6

/** Logcat 日志宏。 */
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* 全局状态（仅信号处理函数内访问）                                       */
/* ------------------------------------------------------------------ */

/** 需要拦截的信号列表。 */
static const int kHandledSignals[NUM_HANDLED_SIGNALS] = {
    SIGSEGV,   /* 段错误 */
    SIGABRT,   /* abort() 调用 */
    SIGBUS,    /* 总线错误 */
    SIGFPE,    /* 浮点异常 */
    SIGPIPE,   /* 管道破裂 */
    SIGSTKFLT  /* 协处理器栈错误 */
};

/** 保存原始信号处理器的数组。 */
static struct sigaction s_old_handlers[NUM_HANDLED_SIGNALS];

/** 是否已安装信号处理器。 */
static int s_handlers_installed = 0;

/** 是否允许在信号处理器中执行 JNI 回调；默认关闭以降低生产风险。 */
static int s_enable_unsafe_jni_callback = 0;

/** 信号处理重入保护。 */
static volatile sig_atomic_t s_handling_signal = 0;

/** JavaVM 指针，JNI_OnLoad 时缓存。 */
static JavaVM *s_jvm = NULL;

/** NativeCrashMonitor Class 全局引用。 */
static jclass s_crash_monitor_class = NULL;

/** logNativeCrashSignal 方法 ID。 */
static jmethodID s_log_native_crash_method = NULL;

/* ------------------------------------------------------------------ */
/* 辅助函数                                                            */
/* ------------------------------------------------------------------ */

/**
 * 获取信号的可读名称。
 *
 * @param sig 信号编号
 * @return 信号名称字符串（静态缓冲区，无需释放）
 */
static const char *get_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV:   return "SIGSEGV";
        case SIGABRT:   return "SIGABRT";
        case SIGBUS:    return "SIGBUS";
        case SIGFPE:    return "SIGFPE";
        case SIGPIPE:   return "SIGPIPE";
        case SIGSTKFLT: return "SIGSTKFLT";
        default:        return "UNKNOWN";
    }
}

/**
 * 获取当前线程名。
 * 先尝试 pthread_getname_np，失败则读取 /proc/self/task/<tid>/comm。
 *
 * @param buf 输出缓冲区
 * @param buf_len 缓冲区长度
 */
static void get_current_thread_name(char *buf, size_t buf_len) {
    /* Android 使用 prctl 获取线程名 */
    if (prctl(PR_GET_NAME, buf, 0, 0, 0) == 0 && buf[0] != '\0') {
        return;
    }

    /* 降级：读取 /proc/self/task/<tid>/comm */
    char comm_path[128];
    pid_t tid = gettid();
    snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);

    FILE *fp = fopen(comm_path, "r");
    if (fp != NULL) {
        if (fgets(buf, (int)buf_len, fp) != NULL) {
            /* 去除末尾换行符 */
            size_t len = strlen(buf);
            if (len > 0 && buf[len - 1] == '\n') {
                buf[len - 1] = '\0';
            }
        }
        fclose(fp);
    } else {
        /* 文件读取也失败，使用默认名 */
        snprintf(buf, buf_len, "tid_%d", tid);
    }
}

/**
 * 捕获当前线程的回溯信息。
 * 使用 <unwind.h> 提供的 _Unwind_Backtrace 获取栈帧。
 *
 * @param buf 输出缓冲区
 * @param buf_len 缓冲区长度
 */
static void capture_backtrace(char *buf, size_t buf_len) {
    /*
     * 注意：在信号处理函数内调用 _Unwind_Backtrace 并不安全，
     * 但这是 Android/Linux 上最常用的方式。
     * 生产环境建议使用 Google Breakpad 的 ExceptionHandler。
     */

    /* 使用 dladdr 获取符号信息 */
    size_t offset = 0;
    buf[0] = '\0';

    void *frames[MAX_BACKTRACE_FRAMES];

    /*
     * 由于 _Unwind_Backtrace 在某些平台信号上下文中不一定可用，
     * 这里使用简化的方式记录 PC 寄存器信息。
     * 实际生产代码需从 ucontext_t 提取寄存器。
     */

    /* 写入占位信息，表示需要通过 tombstone 获取完整 backtrace */
    int written = snprintf(buf + offset, buf_len - offset,
        "#00 pc (see tombstone for full backtrace)");
    if (written > 0) {
        offset += (size_t)written;
    }
}

/**
 * 将故障地址格式化为十六进制字符串。
 *
 * @param addr 故障地址（来自 siginfo_t）
 * @param buf 输出缓冲区
 * @param buf_len 缓冲区长度
 */
static void format_fault_addr(void *addr, char *buf, size_t buf_len) {
    if (addr != NULL) {
        snprintf(buf, buf_len, "%p", addr);
    } else {
        snprintf(buf, buf_len, "0x0");
    }
}

/* ------------------------------------------------------------------ */
/* 信号处理函数                                                        */
/* ------------------------------------------------------------------ */

/**
 * 致命信号处理函数。
 * 在信号处理上下文中执行，必须保证异步信号安全。
 *
 * @param sig 信号编号
 * @param info 信号附加信息（含故障地址）
 * @param ucontext 上下文（未使用）
 */
static void native_crash_signal_handler(int sig, siginfo_t *info, void *ucontext) {
    if (s_handling_signal) {
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    s_handling_signal = 1;

    /* 避免重入：先恢复原始处理器 */
    int signal_index = -1;
    int i;
    for (i = 0; i < NUM_HANDLED_SIGNALS; i++) {
        if (kHandledSignals[i] == sig) {
            signal_index = i;
            break;
        }
    }

    if (!s_enable_unsafe_jni_callback) {
        goto restore_and_reraise;
    }

    /* 捕获信号名称 */
    const char *sig_name = get_signal_name(sig);
    LOGE("Received fatal signal %d (%s)", sig, sig_name);

    /* 捕获故障地址 */
    char fault_addr_str[MAX_FAULT_ADDR_LEN];
    format_fault_addr(info->si_addr, fault_addr_str, sizeof(fault_addr_str));

    /* 捕获线程名 */
    char thread_name[MAX_THREAD_NAME_LEN];
    get_current_thread_name(thread_name, sizeof(thread_name));

    /* 捕获调用栈（简化版本） */
    char backtrace_str[MAX_BACKTRACE_STR_LEN];
    capture_backtrace(backtrace_str, sizeof(backtrace_str));

    /* 通过 JNI 回调 Java 层上报信号信息 */
    JNIEnv *env = NULL;
    int need_detach = 0;

    if (s_jvm == NULL || s_crash_monitor_class == NULL || s_log_native_crash_method == NULL) {
        /* JNI 未初始化，无法回调，跳过 */
        LOGE("JNI not initialized, cannot report signal");
        goto restore_and_reraise;
    }

    /* 获取当前线程的 JNIEnv，或附加新线程 */
    int get_env_result = (*s_jvm)->GetEnv(s_jvm, (void **)&env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        /* 信号可能在 native 线程中触发，需要附加到 JVM */
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = "crash-signal-handler";
        args.group = NULL;
        if ((*s_jvm)->AttachCurrentThread(s_jvm, &env, &args) != 0) {
            LOGE("Failed to attach thread for crash reporting");
            goto restore_and_reraise;
        }
        need_detach = 1;
    } else if (get_env_result != JNI_OK) {
        LOGE("GetEnv failed: %d", get_env_result);
        goto restore_and_reraise;
    }

    /* 调用 Java 层回调方法 */
    if (env != NULL) {
        jstring j_thread_name = (*env)->NewStringUTF(env, thread_name);
        jstring j_backtrace = (*env)->NewStringUTF(env, backtrace_str);
        jstring j_fault_addr = (*env)->NewStringUTF(env, fault_addr_str);

        (*env)->CallStaticVoidMethod(env, s_crash_monitor_class,
            s_log_native_crash_method,
            sig, j_thread_name, j_backtrace, j_fault_addr);

        /* 清理 JNI 局部引用 */
        if (j_thread_name != NULL) (*env)->DeleteLocalRef(env, j_thread_name);
        if (j_backtrace != NULL) (*env)->DeleteLocalRef(env, j_backtrace);
        if (j_fault_addr != NULL) (*env)->DeleteLocalRef(env, j_fault_addr);
    }

    /* 分离临时附加的线程 */
    if (need_detach) {
        (*s_jvm)->DetachCurrentThread(s_jvm);
    }

restore_and_reraise:
    /* 恢复原始信号处理器 */
    if (signal_index >= 0) {
        sigaction(sig, &s_old_handlers[signal_index], NULL);
    } else {
        signal(sig, SIG_DFL);
    }

    /* 重新发送信号，让系统默认处理器产生 core dump / tombstone */
    raise(sig);
}

/* ------------------------------------------------------------------ */
/* JNI 接口                                                            */
/* ------------------------------------------------------------------ */

/**
 * JNI_OnLoad：缓存 JavaVM 和类/方法 ID。
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

    /* 查找 NativeCrashMonitor 类 */
    jclass crash_class = (*env)->FindClass(env,
        "com/apm/crash/NativeCrashMonitor");
    if (crash_class == NULL) {
        LOGE("JNI_OnLoad: NativeCrashMonitor class not found");
        return JNI_ERR;
    }

    /* 创建全局引用，避免类被 GC 回收 */
    s_crash_monitor_class = (*env)->NewGlobalRef(env, crash_class);
    (*env)->DeleteLocalRef(env, crash_class);

    if (s_crash_monitor_class == NULL) {
        LOGE("JNI_OnLoad: NewGlobalRef failed");
        return JNI_ERR;
    }

    /* 缓存 logNativeCrashSignal 静态方法 ID */
    s_log_native_crash_method = (*env)->GetStaticMethodID(env,
        s_crash_monitor_class,
        "logNativeCrashSignal",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (s_log_native_crash_method == NULL) {
        LOGE("JNI_OnLoad: logNativeCrashSignal method not found");
        (*env)->DeleteGlobalRef(env, s_crash_monitor_class);
        s_crash_monitor_class = NULL;
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: signal handler JNI layer initialized");
    return JNI_VERSION_1_6;
}

/**
 * 安装信号处理器。
 * 使用 sigaction 注册所有目标信号的自定义处理函数，同时保存原始处理器。
 *
 * @param env JNIEnv
 * @param thiz this 对象（NativeCrashMonitor 是 object，非实例方法）
 * @return JNI_TRUE 安装成功，JNI_FALSE 安装失败
 */
JNIEXPORT jboolean JNICALL
Java_com_apm_crash_NativeCrashMonitor_nativeInstallSignalHandlers(
    JNIEnv *env, jclass thiz, jboolean unsafeSignalCallback) {

    if (s_handlers_installed) {
        /* 已安装，避免重复注册 */
        LOGW("Signal handlers already installed");
        return JNI_TRUE;
    }

    /* 默认不在信号处理器中执行 JNI/日志/堆栈采集。 */
    s_enable_unsafe_jni_callback = unsafeSignalCallback == JNI_TRUE ? 1 : 0;
    s_handling_signal = 0;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    /* 使用 siginfo 版本的处理器，可获取故障地址等信息 */
    sa.sa_sigaction = native_crash_signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;

    int i;
    for (i = 0; i < NUM_HANDLED_SIGNALS; i++) {
        int sig = kHandledSignals[i];

        /* 保存原始处理器，用于恢复和重发信号 */
        if (sigaction(sig, NULL, &s_old_handlers[i]) != 0) {
            LOGE("Failed to save original handler for signal %d", sig);
            continue;
        }

        /* 安装自定义处理器 */
        if (sigaction(sig, &sa, NULL) != 0) {
            LOGE("Failed to install handler for signal %d (%s)", sig, strerror(errno));
            /* 回滚已安装的处理器 */
            int j;
            for (j = 0; j < i; j++) {
                sigaction(kHandledSignals[j], &s_old_handlers[j], NULL);
            }
            return JNI_FALSE;
        }
    }

    s_handlers_installed = 1;
    LOGI("Signal handlers installed for %d signals", NUM_HANDLED_SIGNALS);
    return JNI_TRUE;
}

/**
 * 卸载信号处理器。
 * 恢复所有被拦截信号的原始处理函数。
 *
 * @param env JNIEnv
 * @param thiz this 对象
 */
JNIEXPORT void JNICALL
Java_com_apm_crash_NativeCrashMonitor_nativeUninstallSignalHandlers(
    JNIEnv *env, jclass thiz) {

    if (!s_handlers_installed) {
        return;
    }

    int i;
    for (i = 0; i < NUM_HANDLED_SIGNALS; i++) {
        /* 恢复原始信号处理器 */
        sigaction(kHandledSignals[i], &s_old_handlers[i], NULL);
    }

    s_handlers_installed = 0;
    s_enable_unsafe_jni_callback = 0;
    s_handling_signal = 0;
    LOGI("Signal handlers uninstalled");
}
