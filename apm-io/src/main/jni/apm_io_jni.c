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
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>

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

/** All loaded shared-object callers whose PLT imports libc IO symbols. */
#define XHOOK_TARGET_REGEX ".*\\.so$"

/** Never rewrite this hook library's own PLT entries. */
#define XHOOK_SELF_REGEX ".*/libapm-io\\.so$"

/** Never rewrite xhook's own PLT entries. */
#define XHOOK_LIBRARY_REGEX ".*/libxhook\\.so$"

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

/** xhook_ignore function pointer type. */
typedef int (*xhook_ignore_t)(const char *path_regex, const char *symbol);

/** xhook 共享库句柄。 */
static void *s_xhook_handle = NULL;

/** 动态解析到的 xhook_register。 */
static xhook_register_t s_xhook_register = NULL;

/** 动态解析到的 xhook_refresh。 */
static xhook_refresh_t s_xhook_refresh = NULL;

/** Dynamically resolved xhook_ignore. */
static xhook_ignore_t s_xhook_ignore = NULL;

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
    /** Monotonic session generation protecting fd reuse races. */
    unsigned long generation;
} io_session_t;

/**
 * IO 会话表。
 * 使用 fd 作为索引，记录每个打开的 fd 对应的会话信息。
 */
static io_session_t s_io_sessions[MAX_IO_SESSIONS];

/** Serializes session mutation across concurrent read/write/close and fd reuse. */
static pthread_mutex_t s_sessions_mutex = PTHREAD_MUTEX_INITIALIZER;

/** Next non-zero fd-session generation, protected by s_sessions_mutex. */
static unsigned long s_next_session_generation = 1;

/* ======================== JNI 引用缓存 ======================== */

/** JavaVM 指针，用于获取 JNIEnv。 */
static JavaVM *s_jvm = NULL;

/** TLS key marking native threads attached by this library. */
static pthread_key_t s_jni_detach_key;

/** One-time initializer for the JNI detach TLS key. */
static pthread_once_t s_jni_detach_key_once = PTHREAD_ONCE_INIT;

/** Whether pthread TLS key creation succeeded. */
static int s_jni_detach_key_ready = 0;

/** NativeIoHook 类的全局引用。 */
static jclass s_native_io_hook_class = NULL;

/** onNativeIoEvent 方法 ID。 */
static jmethodID s_on_native_io_event_method = NULL;

/* ======================== Hook 安装状态 ======================== */

/** Hook 是否已安装。 */
static atomic_int s_hooks_installed = 0;

/** Recording can be stopped while the process PLT remains patched. */
static atomic_int s_recording_enabled = 0;

/** Reentrancy guard covering Java callbacks and any APM-owned IO they trigger. */
static __thread int s_callback_depth = 0;

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

/** Detaches only native threads that this library attached. */
static void detach_jni_thread(void *value) {
    if (value != NULL && s_jvm != NULL) {
        (*s_jvm)->DetachCurrentThread(s_jvm);
    }
}

/** Creates the pthread TLS key used for automatic JNI detach on thread exit. */
static void create_jni_detach_key(void) {
    s_jni_detach_key_ready = pthread_key_create(&s_jni_detach_key, detach_jni_thread) == 0;
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
    int status = (*s_jvm)->GetEnv(s_jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status != JNI_EDETACHED) {
        return NULL;
    }
    pthread_once(&s_jni_detach_key_once, create_jni_detach_key);
    if (!s_jni_detach_key_ready) {
        return NULL;
    }
    int ret = (*s_jvm)->AttachCurrentThread(s_jvm, &env, NULL);
    if (ret != JNI_OK || env == NULL) {
        LOG_E("Failed to attach current thread to JVM, ret=%d", ret);
        return NULL;
    }
    if (pthread_setspecific(s_jni_detach_key, (void *)1) != 0) {
        (*s_jvm)->DetachCurrentThread(s_jvm);
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
    pthread_mutex_lock(&s_sessions_mutex);
    for (i = 0; i < MAX_IO_SESSIONS; i++) {
        s_io_sessions[i].in_use = 0;
        s_io_sessions[i].generation = 0;
    }
    pthread_mutex_unlock(&s_sessions_mutex);
}

/** Returns the next non-zero session generation while the session mutex is held. */
static unsigned long next_session_generation_locked(void) {
    unsigned long generation = s_next_session_generation++;
    if (generation == 0) {
        generation = s_next_session_generation++;
    }
    return generation;
}

/** Returns whether this hook invocation may perform monitoring work. */
static int should_record_io(void) {
    return atomic_load(&s_recording_enabled) && s_callback_depth == 0;
}

/** Returns whether open/openat carries a mode vararg without misreading O_DIRECTORY. */
static int flags_require_mode(int flags) {
    return (flags & O_CREAT) != 0 || (flags & O_TMPFILE) == O_TMPFILE;
}

/** FNV-1a offset used to distinguish paths that share the same bounded prefix. */
#define PATH_HASH_OFFSET UINT64_C(14695981039346656037)

/** FNV-1a prime used to distinguish paths that share the same bounded prefix. */
#define PATH_HASH_PRIME UINT64_C(1099511628211)

/** Number of hexadecimal characters in a 64-bit path hash suffix. */
#define PATH_HASH_HEX_LENGTH 16

/** Total characters in the `~0123456789abcdef` truncation suffix. */
#define PATH_HASH_SUFFIX_LENGTH (1 + PATH_HASH_HEX_LENGTH)

/** Returns a stable hash for arbitrary filesystem bytes. */
static uint64_t hash_path_bytes(const char *input) {
    uint64_t hash = PATH_HASH_OFFSET;
    const unsigned char *cursor = (const unsigned char *)input;
    while (*cursor != '\0') {
        hash ^= *cursor++;
        hash *= PATH_HASH_PRIME;
    }
    return hash;
}

/** Appends a bounded hash suffix and terminates the output. */
static void append_path_hash_suffix(
        char output[MAX_PATH_LENGTH],
        size_t prefix_length,
        uint64_t hash) {
    static const char HEX_DIGITS[] = "0123456789abcdef";
    size_t index;
    output[prefix_length] = '~';
    for (index = 0; index < PATH_HASH_HEX_LENGTH; index++) {
        unsigned int shift = (unsigned int)((PATH_HASH_HEX_LENGTH - index - 1) * 4);
        output[prefix_length + 1 + index] = HEX_DIGITS[(hash >> shift) & 0x0fU];
    }
    output[prefix_length + PATH_HASH_SUFFIX_LENGTH] = '\0';
}

/** Copies a path into a session while distinguishing paths with the same long prefix. */
static void copy_path_for_session(const char *input, char output[MAX_PATH_LENGTH]) {
    if (input == NULL) {
        strncpy(output, "unknown", MAX_PATH_LENGTH);
        output[MAX_PATH_LENGTH - 1] = '\0';
        return;
    }
    size_t input_length = strlen(input);
    if (input_length < MAX_PATH_LENGTH) {
        memcpy(output, input, input_length + 1);
        return;
    }
    size_t prefix_length = MAX_PATH_LENGTH - 1 - PATH_HASH_SUFFIX_LENGTH;
    memcpy(output, input, prefix_length);
    append_path_hash_suffix(output, prefix_length, hash_path_bytes(input));
}

/** Converts arbitrary filesystem bytes into collision-resistant ASCII safe for NewStringUTF. */
static void sanitize_path_for_jni(const char *input, char output[MAX_PATH_LENGTH]) {
    if (input == NULL) {
        strncpy(output, "unknown", MAX_PATH_LENGTH);
        output[MAX_PATH_LENGTH - 1] = '\0';
        return;
    }

    static const char HEX_DIGITS[] = "0123456789abcdef";
    size_t encoded_length = 0;
    const unsigned char *cursor = (const unsigned char *)input;
    while (*cursor != '\0') {
        unsigned char value = *cursor++;
        encoded_length += value >= 0x20 && value <= 0x7e && value != '%' ? 1 : 3;
    }

    size_t output_limit = MAX_PATH_LENGTH - 1;
    int truncated = encoded_length > output_limit;
    if (truncated) {
        output_limit -= PATH_HASH_SUFFIX_LENGTH;
    }

    size_t output_index = 0;
    cursor = (const unsigned char *)input;
    while (*cursor != '\0') {
        unsigned char value = *cursor;
        size_t required = value >= 0x20 && value <= 0x7e && value != '%' ? 1 : 3;
        if (output_index + required > output_limit) {
            break;
        }
        if (required == 1) {
            output[output_index++] = (char)value;
        } else {
            output[output_index++] = '%';
            output[output_index++] = HEX_DIGITS[value >> 4];
            output[output_index++] = HEX_DIGITS[value & 0x0fU];
        }
        cursor++;
    }
    if (truncated) {
        append_path_hash_suffix(output, output_index, hash_path_bytes(input));
    } else {
        output[output_index] = '\0';
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
    if (s_xhook_register != NULL && s_xhook_refresh != NULL && s_xhook_ignore != NULL) {
        return 1;
    }

    s_xhook_register = (xhook_register_t)dlsym(RTLD_DEFAULT, "xhook_register");
    s_xhook_refresh = (xhook_refresh_t)dlsym(RTLD_DEFAULT, "xhook_refresh");
    s_xhook_ignore = (xhook_ignore_t)dlsym(RTLD_DEFAULT, "xhook_ignore");
    if (s_xhook_register != NULL && s_xhook_refresh != NULL && s_xhook_ignore != NULL) {
        return 1;
    }

    s_xhook_handle = dlopen(XHOOK_LIBRARY_NAME, RTLD_NOW | RTLD_LOCAL);
    if (s_xhook_handle == NULL) {
        LOG_W("xhook library not found: %s", dlerror());
        return 0;
    }

    s_xhook_register = (xhook_register_t)dlsym(s_xhook_handle, "xhook_register");
    s_xhook_refresh = (xhook_refresh_t)dlsym(s_xhook_handle, "xhook_refresh");
    s_xhook_ignore = (xhook_ignore_t)dlsym(s_xhook_handle, "xhook_ignore");
    if (s_xhook_register == NULL || s_xhook_refresh == NULL || s_xhook_ignore == NULL) {
        LOG_W("xhook symbols not found");
        dlclose(s_xhook_handle);
        s_xhook_handle = NULL;
        s_xhook_register = NULL;
        s_xhook_refresh = NULL;
        s_xhook_ignore = NULL;
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
    if (s_callback_depth > 0) {
        return;
    }
    s_callback_depth++;
    JNIEnv *env = get_jni_env();
    if (env == NULL || s_native_io_hook_class == NULL || s_on_native_io_event_method == NULL) {
        /* JNI 环境未就绪，静默忽略 */
        s_callback_depth--;
        return;
    }

    /* Convert arbitrary filesystem bytes before Modified UTF-8 JNI conversion. */
    char safe_path[MAX_PATH_LENGTH];
    sanitize_path_for_jni(path, safe_path);

    /* 构造 Java 字符串参数 */
    jstring j_operation = (*env)->NewStringUTF(env, operation);
    jstring j_path = (*env)->NewStringUTF(env, safe_path);
    if (j_operation == NULL || j_path == NULL) {
        /* OOM 时清理已创建的引用 */
        if (j_operation != NULL) (*env)->DeleteLocalRef(env, j_operation);
        if (j_path != NULL) (*env)->DeleteLocalRef(env, j_path);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        s_callback_depth--;
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
    s_callback_depth--;
}

/* ======================== Hook 实现 ======================== */

/**
 * Hook 后的 open 函数。
 * 记录文件打开时间，创建 IO 会话。
 */
int hooked_open(const char *pathname, int flags, ...) {
    /* 提取可变参数中的 mode（创建文件时需要） */
    mode_t mode = 0;
    if (flags_require_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, int);
        va_end(args);
    }

    /* 调用原始 open 函数 */
    int fd = s_orig_open(pathname, flags, mode);

    if (should_record_io() && fd >= 0 && is_valid_fd(fd)) {
        long long start_ns = get_time_ns();

        /* 记录 IO 会话 */
        pthread_mutex_lock(&s_sessions_mutex);
        io_session_t *session = &s_io_sessions[fd];
        copy_path_for_session(pathname, session->path);
        session->open_time_ns = start_ns;
        session->total_read_bytes = 0;
        session->total_write_bytes = 0;
        session->in_use = 1;
        session->generation = next_session_generation_locked();
        pthread_mutex_unlock(&s_sessions_mutex);
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
    if (flags_require_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, int);
        va_end(args);
    }

    /* 调用原始 openat 函数 */
    int fd = s_orig_openat(dirfd, pathname, flags, mode);

    if (should_record_io() && fd >= 0 && is_valid_fd(fd)) {
        long long start_ns = get_time_ns();

        /* 记录 IO 会话 */
        pthread_mutex_lock(&s_sessions_mutex);
        io_session_t *session = &s_io_sessions[fd];
        copy_path_for_session(pathname, session->path);
        session->open_time_ns = start_ns;
        session->total_read_bytes = 0;
        session->total_write_bytes = 0;
        session->in_use = 1;
        session->generation = next_session_generation_locked();
        pthread_mutex_unlock(&s_sessions_mutex);
    }

    return fd;
}

/**
 * Hook 后的 read 函数。
 * 记录读取字节数和耗时。
 */
ssize_t hooked_read(int fd, void *buf, size_t count) {
    int record = should_record_io();
    unsigned long generation = 0;
    if (record && is_valid_fd(fd)) {
        pthread_mutex_lock(&s_sessions_mutex);
        if (s_io_sessions[fd].in_use) {
            generation = s_io_sessions[fd].generation;
        }
        pthread_mutex_unlock(&s_sessions_mutex);
    }
    long long start_ns = record ? get_time_ns() : 0;

    /* 调用原始 read 函数 */
    ssize_t result = s_orig_read(fd, buf, count);

    if (record && result > 0 && is_valid_fd(fd)) {
        long long end_ns = get_time_ns();
        long long duration_ms = ns_to_ms(end_ns - start_ns);
        char path[MAX_PATH_LENGTH] = "unknown";
        int session_matches = generation == 0;
        pthread_mutex_lock(&s_sessions_mutex);
        io_session_t *session = &s_io_sessions[fd];
        if (generation != 0 && session->in_use && session->generation == generation) {
            session->total_read_bytes += (long long)result;
            strncpy(path, session->path, MAX_PATH_LENGTH - 1);
            path[MAX_PATH_LENGTH - 1] = '\0';
            session_matches = 1;
        }
        pthread_mutex_unlock(&s_sessions_mutex);
        if (session_matches) {
            notify_java_callback(
                    "read",
                    path,
                    (long long)result,
                    duration_ms,
                    is_main_thread()
            );
        }
    }

    return result;
}

/**
 * Hook 后的 write 函数。
 * 记录写入字节数和耗时。
 */
ssize_t hooked_write(int fd, const void *buf, size_t count) {
    int record = should_record_io();
    unsigned long generation = 0;
    if (record && is_valid_fd(fd)) {
        pthread_mutex_lock(&s_sessions_mutex);
        if (s_io_sessions[fd].in_use) {
            generation = s_io_sessions[fd].generation;
        }
        pthread_mutex_unlock(&s_sessions_mutex);
    }
    long long start_ns = record ? get_time_ns() : 0;

    /* 调用原始 write 函数 */
    ssize_t result = s_orig_write(fd, buf, count);

    if (record && result > 0 && is_valid_fd(fd)) {
        long long end_ns = get_time_ns();
        long long duration_ms = ns_to_ms(end_ns - start_ns);
        char path[MAX_PATH_LENGTH] = "unknown";
        int session_matches = generation == 0;
        pthread_mutex_lock(&s_sessions_mutex);
        io_session_t *session = &s_io_sessions[fd];
        if (generation != 0 && session->in_use && session->generation == generation) {
            session->total_write_bytes += (long long)result;
            strncpy(path, session->path, MAX_PATH_LENGTH - 1);
            path[MAX_PATH_LENGTH - 1] = '\0';
            session_matches = 1;
        }
        pthread_mutex_unlock(&s_sessions_mutex);
        if (session_matches) {
            notify_java_callback(
                    "write",
                    path,
                    (long long)result,
                    duration_ms,
                    is_main_thread()
            );
        }
    }

    return result;
}

/**
 * Hook 后的 close 函数。
 * 计算会话总耗时，汇总读写字节数后通知 Java 层。
 */
int hooked_close(int fd) {
    int record = should_record_io();
    int had_session = 0;

    unsigned long generation = 0;
    if (record && is_valid_fd(fd)) {
        pthread_mutex_lock(&s_sessions_mutex);
        io_session_t *session = &s_io_sessions[fd];
        if (session->in_use) {
            generation = session->generation;
            had_session = 1;
        }
        pthread_mutex_unlock(&s_sessions_mutex);
    }

    /* 调用原始 close 函数 */
    int result = s_orig_close(fd);

    /* Clear only the generation closed by this call; close lifetime is not syscall latency. */
    if (record && result == 0 && had_session) {
        pthread_mutex_lock(&s_sessions_mutex);
        if (s_io_sessions[fd].in_use && s_io_sessions[fd].generation == generation) {
            s_io_sessions[fd].in_use = 0;
            s_io_sessions[fd].generation = 0;
        }
        pthread_mutex_unlock(&s_sessions_mutex);
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
    if (atomic_load(&s_hooks_installed)) {
        atomic_store(&s_recording_enabled, 1);
        LOG_D("IO hooks already patched, recording re-enabled");
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
            XHOOK_TARGET_REGEX,
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
            XHOOK_TARGET_REGEX,
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
            XHOOK_TARGET_REGEX,
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
            XHOOK_TARGET_REGEX,
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
            XHOOK_TARGET_REGEX,
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

    /* xhook matches caller ELF pathnames, not libc itself; exclude both hook engines. */
    if (s_xhook_ignore(XHOOK_SELF_REGEX, NULL) != 0 ||
        s_xhook_ignore(XHOOK_LIBRARY_REGEX, NULL) != 0) {
        throw_illegal_state(env, "xhook self-ignore registration failed; falling back to Java IO proxy");
        return;
    }

    /* 刷新 hook，使已加载的 SO 库中的 PLT 项被替换 */
    ret = s_xhook_refresh(XHOOK_REFRESH_SYNC);
    if (ret != 0) {
        LOG_E("xhook_refresh failed, ret=%d", ret);
        throw_illegal_state(env, "xhook_refresh failed; falling back to Java IO proxy");
    } else {
        atomic_store(&s_hooks_installed, 1);
        atomic_store(&s_recording_enabled, 1);
        LOG_D("IO hooks installed successfully");
    }
}

/**
 * 停止 IO 记录并清理会话表。
 *
 * 注意：PLT Hook 框架在运行时不支持完全卸载，
 * 此函数通过 recording flag 停止 hook 快路径记录，但已修改的 PLT 项不会恢复。
 * s_orig_xxx 函数指针继续有效，因此调用链不会断裂。
 *
 * @param env JNIEnv 指针
 * @param clazz 调用类的 jclass
 */
JNIEXPORT void JNICALL
Java_com_apm_io_NativeIoHook_nativeUninstallIoHooks(
        JNIEnv *env,
        jclass clazz) {
    if (!atomic_load(&s_hooks_installed) || !atomic_load(&s_recording_enabled)) {
        LOG_D("IO hooks not installed, nothing to uninstall");
        return;
    }

    LOG_D("Uninstalling IO hooks...");

    /* PLT remains patched; disable the hook fast-path without re-registering later. */
    atomic_store(&s_recording_enabled, 0);

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
    atomic_store(&s_hooks_installed, 0);
    atomic_store(&s_recording_enabled, 0);
    s_xhook_register = NULL;
    s_xhook_refresh = NULL;
    s_xhook_ignore = NULL;
    if (s_xhook_handle != NULL) {
        dlclose(s_xhook_handle);
        s_xhook_handle = NULL;
    }
    init_io_sessions();

    LOG_D("JNI_OnUnload completed");
}
