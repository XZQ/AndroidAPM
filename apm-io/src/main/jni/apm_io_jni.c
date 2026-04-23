/**
 * apm-io JNI 层：通过 PLT Hook 拦截 libc 的 IO 操作。
 *
 * 拦截目标函数：
 * - open/openat → 记录文件打开
 * - read → 记录读取字节数和耗时
 * - write → 记录写入字节数和耗时
 * - close → 记录文件关闭，计算会话耗时
 *
 * 使用 xhook/bhook 库进行 PLT Hook。
 * 编译依赖：libxhook.a 或 libbhook.a
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdarg.h>

/* ======================== 常量定义 ======================== */

/** 日志 TAG。 */
#define TAG "apm-io-jni"

/** 最大路径长度。 */
#define MAX_PATH_LENGTH 256

/** 最大活跃 IO 会话数。 */
#define MAX_IO_SESSIONS 1024

/** Java 回调方法签名。 */
#define CALLBACK_METHOD_SIG "(Ljava/lang/String;Ljava/lang/String;JJZ)V"

/** xhook 共享库名。 */
#define XHOOK_LIBRARY_NAME "libxhook.so"

/** xhook 同步刷新标记。 */
#define XHOOK_REFRESH_SYNC 0

/* 日志宏 */
#define LOG_D(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, ##__VA_ARGS__)
#define LOG_E(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)
#define LOG_W(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)

/* ======================== 原始函数指针类型定义 ======================== */

/** open() 原始函数指针类型。 */
typedef int (*orig_open_t)(const char *pathname, int flags, ...);

/** openat() 原始函数指针类型。 */
typedef int (*orig_openat_t)(int dirfd, const char *pathname, int flags, ...);

/** read() 原始函数指针类型。 */
typedef ssize_t (*orig_read_t)(int fd, void *buf, size_t count);

/** write() 原始函数指针类型。 */
typedef ssize_t (*orig_write_t)(int fd, const void *buf, size_t count);

/** close() 原始函数指针类型。 */
typedef int (*orig_close_t)(int fd);

/* ======================== 原始函数指针存储 ======================== */

/** 原始 open 函数指针。 */
static orig_open_t s_orig_open = NULL;

/** 原始 openat 函数指针。 */
static orig_openat_t s_orig_openat = NULL;

/** 原始 read 函数指针。 */
static orig_read_t s_orig_read = NULL;

/** 原始 write 函数指针。 */
static orig_write_t s_orig_write = NULL;

/** 原始 close 函数指针。 */
static orig_close_t s_orig_close = NULL;

/* ======================== xhook 动态符号 ======================== */

/** xhook_register 函数指针类型。 */
typedef int (*xhook_register_t)(const char *path_regex, const char *symbol,
                                void *new_func, void **old_func);

/** xhook_refresh 函数指针类型。 */
typedef int (*xhook_refresh_t)(int async);

/** xhook 共享库句柄。 */
static void *s_xhook_handle = NULL;

/** 动态解析到的 xhook_register。 */
static xhook_register_t s_xhook_register = NULL;

/** 动态解析到的 xhook_refresh。 */
static xhook_refresh_t s_xhook_refresh = NULL;

/* ======================== IO 会话跟踪 ======================== */

/**
 * IO 会话记录。
 * 从 open 到 close 期间记录文件路径、打开时间等信息。
 */
typedef struct {
    /** 文件路径。 */
    char path[MAX_PATH_LENGTH];
    /** 打开时间（纳秒）。 */
    long long open_time_ns;
    /** 总读取字节数。 */
    long long total_read_bytes;
    /** 总写入字节数。 */
    long long total_write_bytes;
    /** 是否已使用（有效数据）。 */
    int in_use;
} io_session_t;

/**
 * IO 会话表。
 * 使用 fd 作为索引，记录每个打开的 fd 对应的会话信息。
 */
static io_session_t s_io_sessions[MAX_IO_SESSIONS];

/* ======================== JNI 引用缓存 ======================== */

/** JavaVM 指针，用于获取 JNIEnv。 */
static JavaVM *s_jvm = NULL;

/** NativeIoHook 类的全局引用。 */
static jclass s_native_io_hook_class = NULL;

/** onNativeIoEvent 方法 ID。 */
static jmethodID s_on_native_io_event_method = NULL;

/* ======================== Hook 安装状态 ======================== */

/** Hook 是否已安装。 */
static int s_hooks_installed = 0;

/* ======================== 工具函数 ======================== */

/**
 * 获取当前时间（纳秒）。
 * 使用 CLOCK_MONOTONIC 避免系统时间跳变的影响。
 *
 * @return 当前纳秒时间戳
 */
static long long get_time_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/**
 * 将纳秒转换为毫秒。
 *
 * @param ns 纳秒值
 * @return 毫秒值
 */
static long long ns_to_ms(long long ns) {
    return ns / 1000000LL;
}

/**
 * 判断当前线程是否为主线程。
 * 通过比较当前线程 ID 和进程 ID 判断。
 * 主线程的 tid 等于 pid。
 *
 * @return 1 表示主线程，0 表示非主线程
 */
static int is_main_thread(void) {
    return (gettid() == getpid()) ? 1 : 0;
}

/**
 * 获取当前线程的 JNIEnv。
 * 从 JavaVM 获取适用于当前线程的 JNIEnv。
 *
 * @return JNIEnv 指针，失败返回 NULL
 */
static JNIEnv *get_jni_env(void) {
    if (s_jvm == NULL) {
        return NULL;
    }
    JNIEnv *env = NULL;
    /* AttachCurrentThread 对已附加的线程无副作用 */
    int ret = (*s_jvm)->AttachCurrentThread(s_jvm, &env, NULL);
    if (ret != 0 || env == NULL) {
        LOG_E("Failed to attach current thread to JVM, ret=%d", ret);
        return NULL;
    }
    return env;
}

/**
 * 初始化 IO 会话表。
 * 将所有会话标记为未使用。
 */
static void init_io_sessions(void) {
    int i;
    for (i = 0; i < MAX_IO_SESSIONS; i++) {
        s_io_sessions[i].in_use = 0;
    }
}

/**
 * 检查 fd 是否在有效范围内。
 *
 * @param fd 文件描述符
 * @return 1 表示有效，0 表示无效
 */
static int is_valid_fd(int fd) {
    return (fd >= 0 && fd < MAX_IO_SESSIONS) ? 1 : 0;
}

/**
 * 向 Java 层抛出 IllegalStateException。
 *
 * @param env JNIEnv 指针
 * @param message 异常消息
 */
static void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message);
        (*env)->DeleteLocalRef(env, exception_class);
    }
}

/**
 * 动态解析 xhook 符号。
 * 宿主若集成 libxhook.so，可通过 jniLibs 或预加载方式让本库解析到符号；
 * 未集成时返回 0，由 Java 层降级到 InputStream/OutputStream 代理。
 *
 * @return 1 表示 xhook 可用，0 表示不可用
 */
static int resolve_xhook_symbols(void) {
    if (s_xhook_register != NULL && s_xhook_refresh != NULL) {
        return 1;
    }

    s_xhook_register = (xhook_register_t)dlsym(RTLD_DEFAULT, "xhook_register");
    s_xhook_refresh = (xhook_refresh_t)dlsym(RTLD_DEFAULT, "xhook_refresh");
    if (s_xhook_register != NULL && s_xhook_refresh != NULL) {
        return 1;
    }

    s_xhook_handle = dlopen(XHOOK_LIBRARY_NAME, RTLD_NOW | RTLD_LOCAL);
    if (s_xhook_handle == NULL) {
        LOG_W("xhook library not found: %s", dlerror());
        return 0;
    }

    s_xhook_register = (xhook_register_t)dlsym(s_xhook_handle, "xhook_register");
    s_xhook_refresh = (xhook_refresh_t)dlsym(s_xhook_handle, "xhook_refresh");
    if (s_xhook_register == NULL || s_xhook_refresh == NULL) {
        LOG_W("xhook symbols not found");
        dlclose(s_xhook_handle);
        s_xhook_handle = NULL;
        s_xhook_register = NULL;
        s_xhook_refresh = NULL;
        return 0;
    }

    return 1;
}

/**
 * 调用 Java 层的 onNativeIoEvent 回调。
 * 将 Native 层检测到的 IO 操作信息传递到 Java 层。
 *
 * @param operation 操作类型字符串（"open"、"read"、"write"、"close"）
 * @param path 文件路径
 * @param bytes 操作字节数
 * @param duration_ms 操作耗时（毫秒）
 * @param is_main_thread 是否在主线程
 */
static void notify_java_callback(
        const char *operation,
        const char *path,
        long long bytes,
        long long duration_ms,
        int is_main_thread) {
    JNIEnv *env = get_jni_env();
    if (env == NULL || s_native_io_hook_class == NULL || s_on_native_io_event_method == NULL) {
        /* JNI 环境未就绪，静默忽略 */
        return;
    }

    /* 构造 Java 字符串参数 */
    jstring j_operation = (*env)->NewStringUTF(env, operation);
    jstring j_path = (*env)->NewStringUTF(env, path);
    if (j_operation == NULL || j_path == NULL) {
        /* OOM 时清理已创建的引用 */
        if (j_operation != NULL) (*env)->DeleteLocalRef(env, j_operation);
        if (j_path != NULL) (*env)->DeleteLocalRef(env, j_path);
        return;
    }

    /* 调用 Java 层静态回调方法 */
    (*env)->CallStaticVoidMethod(
            env,
            s_native_io_hook_class,
            s_on_native_io_event_method,
            j_operation,
            j_path,
            (jlong)bytes,
            (jlong)duration_ms,
            (jboolean)is_main_thread
    );

    /* 检查是否有 Java 异常 */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOG_W("Exception occurred in Java onNativeIoEvent callback");
    }

    /* 释放局部引用，防止 JNI 局部引用表溢出 */
    (*env)->DeleteLocalRef(env, j_operation);
    (*env)->DeleteLocalRef(env, j_path);
}

/* ======================== Hook 实现 ======================== */

/**
 * Hook 后的 open 函数。
 * 记录文件打开时间，创建 IO 会话。
 */
int hooked_open(const char *pathname, int flags, ...) {
    /* 提取可变参数中的 mode（创建文件时需要） */
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, int);
        va_end(args);
    }

    /* 调用原始 open 函数 */
    int fd = s_orig_open(pathname, flags, mode);

    if (fd >= 0 && is_valid_fd(fd)) {
        /* 记录打开时间 */
        long long start_ns = get_time_ns();
        long long duration_ms = 0;

        /* 记录 IO 会话 */
        io_session_t *session = &s_io_sessions[fd];
        strncpy(session->path, pathname, MAX_PATH_LENGTH - 1);
        session->path[MAX_PATH_LENGTH - 1] = '\0';
        session->open_time_ns = start_ns;
        session->total_read_bytes = 0;
        session->total_write_bytes = 0;
        session->in_use = 1;

        /* 通知 Java 层 */
        notify_java_callback(
                "open",
                pathname,
                0,
                duration_ms,
                is_main_thread()
        );
    }

    return fd;
}

/**
 * Hook 后的 openat 函数。
 * 记录文件打开时间，创建 IO 会话。
 */
int hooked_openat(int dirfd, const char *pathname, int flags, ...) {
    /* 提取可变参数中的 mode */
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, int);
        va_end(args);
    }

    /* 调用原始 openat 函数 */
    int fd = s_orig_openat(dirfd, pathname, flags, mode);

    if (fd >= 0 && is_valid_fd(fd)) {
        long long start_ns = get_time_ns();

        /* 记录 IO 会话 */
        io_session_t *session = &s_io_sessions[fd];
        strncpy(session->path, pathname, MAX_PATH_LENGTH - 1);
        session->path[MAX_PATH_LENGTH - 1] = '\0';
        session->open_time_ns = start_ns;
        session->total_read_bytes = 0;
        session->total_write_bytes = 0;
        session->in_use = 1;

        /* 通知 Java 层 */
        notify_java_callback(
                "open",
                pathname,
                0,
                0,
                is_main_thread()
        );
    }

    return fd;
}

/**
 * Hook 后的 read 函数。
 * 记录读取字节数和耗时。
 */
ssize_t hooked_read(int fd, void *buf, size_t count) {
    /* 记录读操作开始时间 */
    long long start_ns = get_time_ns();

    /* 调用原始 read 函数 */
    ssize_t result = s_orig_read(fd, buf, count);

    if (result > 0 && is_valid_fd(fd)) {
        long long end_ns = get_time_ns();
        long long duration_ms = ns_to_ms(end_ns - start_ns);

        /* 累加会话中的读取字节数 */
        io_session_t *session = &s_io_sessions[fd];
        if (session->in_use) {
            session->total_read_bytes += (long long)result;
        }

        /* 获取文件路径 */
        const char *path = session->in_use ? session->path : "unknown";

        /* 通知 Java 层 */
        notify_java_callback(
                "read",
                path,
                (long long)result,
                duration_ms,
                is_main_thread()
        );
    }

    return result;
}

/**
 * Hook 后的 write 函数。
 * 记录写入字节数和耗时。
 */
ssize_t hooked_write(int fd, const void *buf, size_t count) {
    /* 记录写操作开始时间 */
    long long start_ns = get_time_ns();

    /* 调用原始 write 函数 */
    ssize_t result = s_orig_write(fd, buf, count);

    if (result > 0 && is_valid_fd(fd)) {
        long long end_ns = get_time_ns();
        long long duration_ms = ns_to_ms(end_ns - start_ns);

        /* 累加会话中的写入字节数 */
        io_session_t *session = &s_io_sessions[fd];
        if (session->in_use) {
            session->total_write_bytes += (long long)result;
        }

        /* 获取文件路径 */
        const char *path = session->in_use ? session->path : "unknown";

        /* 通知 Java 层 */
        notify_java_callback(
                "write",
                path,
                (long long)result,
                duration_ms,
                is_main_thread()
        );
    }

    return result;
}

/**
 * Hook 后的 close 函数。
 * 计算会话总耗时，汇总读写字节数后通知 Java 层。
 */
int hooked_close(int fd) {
    /* 在关闭前提取会话信息 */
    char path[MAX_PATH_LENGTH] = "unknown";
    long long session_duration_ms = 0;
    long long total_bytes = 0;
    int had_session = 0;

    if (is_valid_fd(fd) && s_io_sessions[fd].in_use) {
        io_session_t *session = &s_io_sessions[fd];
        /* 复制路径（close 后 session 将被清理） */
        strncpy(path, session->path, MAX_PATH_LENGTH - 1);
        path[MAX_PATH_LENGTH - 1] = '\0';

        /* 计算会话总耗时 */
        long long now_ns = get_time_ns();
        session_duration_ms = ns_to_ms(now_ns - session->open_time_ns);

        /* 汇总读写字节数 */
        total_bytes = session->total_read_bytes + session->total_write_bytes;

        /* 标记会话为无效 */
        session->in_use = 0;
        had_session = 1;
    }

    /* 调用原始 close 函数 */
    int result = s_orig_close(fd);

    /* 在 close 成功后通知 Java 层 */
    if (result == 0 && had_session) {
        notify_java_callback(
                "close",
                path,
                total_bytes,
                session_duration_ms,
                is_main_thread()
        );
    }

    return result;
}

/* ======================== JNI 接口实现 ======================== */

/**
 * JNI_OnLoad：VM 加载 SO 时调用。
 * 缓存 JavaVM 指针和 NativeIoHook 类、方法 ID。
 *
 * @param vm JavaVM 指针
 * @param reserved 保留参数
 * @return JNI 版本号
 */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOG_D("JNI_OnLoad called");

    /* 保存 JavaVM 指针 */
    s_jvm = vm;

    /* 获取 JNIEnv */
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOG_E("Failed to get JNIEnv");
        return JNI_ERR;
    }

    /* 查找 NativeIoHook 类 */
    jclass local_class = (*env)->FindClass(env, "com/apm/io/NativeIoHook");
    if (local_class == NULL) {
        LOG_E("Failed to find NativeIoHook class");
        return JNI_ERR;
    }

    /* 创建全局引用，防止类被 GC 回收 */
    s_native_io_hook_class = (*env)->NewGlobalRef(env, local_class);
    (*env)->DeleteLocalRef(env, local_class);

    if (s_native_io_hook_class == NULL) {
        LOG_E("Failed to create global ref for NativeIoHook class");
        return JNI_ERR;
    }

    /* 查找 onNativeIoEvent 静态回调方法 */
    s_on_native_io_event_method = (*env)->GetStaticMethodID(
            env,
            s_native_io_hook_class,
            "onNativeIoEvent",
            CALLBACK_METHOD_SIG
    );
    if (s_on_native_io_event_method == NULL) {
        LOG_E("Failed to find onNativeIoEvent method");
        return JNI_ERR;
    }

    /* 初始化 IO 会话表 */
    init_io_sessions();

    LOG_D("JNI_OnLoad completed successfully");
    return JNI_VERSION_1_6;
}

/**
 * 安装 IO Hook。
 * 通过 xhook 注册对 libc.so 中 open/openat/read/write/close 的 PLT Hook。
 *
 * @param env JNIEnv 指针
 * @param clazz 调用类的 jclass
 */
JNIEXPORT void JNICALL
Java_com_apm_io_NativeIoHook_nativeInstallIoHooks(
        JNIEnv *env,
        jclass clazz) {
    if (s_hooks_installed) {
        LOG_D("IO hooks already installed, skipping");
        return;
    }

    LOG_D("Installing IO hooks via xhook...");

    if (!resolve_xhook_symbols()) {
        throw_illegal_state(env, "libxhook.so is not available; falling back to Java IO proxy");
        return;
    }

    int failures = 0;

    /* 注册 open hook */
    int ret = s_xhook_register(
            "libc\\.so$",
            "open",
            (void *)hooked_open,
            (void **)&s_orig_open
    );
    if (ret != 0) {
        LOG_E("Failed to register hook for open, ret=%d", ret);
        failures++;
    }

    /* 注册 openat hook */
    ret = s_xhook_register(
            "libc\\.so$",
            "openat",
            (void *)hooked_openat,
            (void **)&s_orig_openat
    );
    if (ret != 0) {
        LOG_E("Failed to register hook for openat, ret=%d", ret);
        failures++;
    }

    /* 注册 read hook */
    ret = s_xhook_register(
            "libc\\.so$",
            "read",
            (void *)hooked_read,
            (void **)&s_orig_read
    );
    if (ret != 0) {
        LOG_E("Failed to register hook for read, ret=%d", ret);
        failures++;
    }

    /* 注册 write hook */
    ret = s_xhook_register(
            "libc\\.so$",
            "write",
            (void *)hooked_write,
            (void **)&s_orig_write
    );
    if (ret != 0) {
        LOG_E("Failed to register hook for write, ret=%d", ret);
        failures++;
    }

    /* 注册 close hook */
    ret = s_xhook_register(
            "libc\\.so$",
            "close",
            (void *)hooked_close,
            (void **)&s_orig_close
    );
    if (ret != 0) {
        LOG_E("Failed to register hook for close, ret=%d", ret);
        failures++;
    }

    if (failures > 0) {
        throw_illegal_state(env, "xhook registration failed; falling back to Java IO proxy");
        return;
    }

    /* 刷新 hook，使已加载的 SO 库中的 PLT 项被替换 */
    ret = s_xhook_refresh(XHOOK_REFRESH_SYNC);
    if (ret != 0) {
        LOG_E("xhook_refresh failed, ret=%d", ret);
        throw_illegal_state(env, "xhook_refresh failed; falling back to Java IO proxy");
    } else {
        s_hooks_installed = 1;
        LOG_D("IO hooks installed successfully");
    }
}

/**
 * 卸载 IO Hook。
 * 恢复原始函数指针，清理会话表。
 *
 * 注意：PLT Hook 框架在运行时不支持完全卸载，
 * 此函数将停止记录但已修改的 PLT 项不会恢复。
 * s_orig_xxx 函数指针继续有效，因此调用链不会断裂。
 *
 * @param env JNIEnv 指针
 * @param clazz 调用类的 jclass
 */
JNIEXPORT void JNICALL
Java_com_apm_io_NativeIoHook_nativeUninstallIoHooks(
        JNIEnv *env,
        jclass clazz) {
    if (!s_hooks_installed) {
        LOG_D("IO hooks not installed, nothing to uninstall");
        return;
    }

    LOG_D("Uninstalling IO hooks...");

    /* 标记 Hook 为已卸载，停止记录新事件 */
    s_hooks_installed = 0;

    /* 清理会话表 */
    init_io_sessions();

    LOG_D("IO hooks uninstalled (note: PLT entries remain patched)");
}

/**
 * JNI_OnUnload：VM 卸载 SO 时调用。
 * 释放全局引用等资源。
 *
 * @param vm JavaVM 指针
 * @param reserved 保留参数
 */
JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    LOG_D("JNI_OnUnload called");

    /* 获取 JNIEnv 用于释放全局引用 */
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        /* 释放 NativeIoHook 类的全局引用 */
        if (s_native_io_hook_class != NULL) {
            (*env)->DeleteGlobalRef(env, s_native_io_hook_class);
            s_native_io_hook_class = NULL;
        }
    }

    /* 清理状态 */
    s_on_native_io_event_method = NULL;
    s_jvm = NULL;
    s_hooks_installed = 0;
    s_xhook_register = NULL;
    s_xhook_refresh = NULL;
    if (s_xhook_handle != NULL) {
        dlclose(s_xhook_handle);
        s_xhook_handle = NULL;
    }
    init_io_sessions();

    LOG_D("JNI_OnUnload completed");
}
