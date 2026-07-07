/*
 * apm_anr_jni.c — SIGQUIT 信号检测 JNI 层。
 *
 * 设计要点（异步信号安全优先）：
 * 1. ART 的 "Signal Catcher" 线程通过 sigwait 消费进程级 SIGQUIT 并 dump
 *    trace；普通线程默认阻塞 SIGQUIT。本库创建一个专用接收线程并解除其
 *    SIGQUIT 阻塞，使 sigaction 安装的处理器有机会先于 sigwait 收到信号。
 * 2. 信号处理器内只做两件异步信号安全的事：
 *    - 以原子交换记录时间戳（供 Java 侧轮询消费）
 *    - 通过 tgkill 把 SIGQUIT 定向转发给 Signal Catcher 线程，
 *      保证系统的 ANR trace dump 行为不受影响
 * 3. 不做任何 JNI 回调、不分配内存、不加锁 —— Java 侧通过
 *    nativeConsumeSigquitTimestamp 轮询获取信号标志。
 */

#include <jni.h>

#include <android/log.h>
#include <dirent.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

/** Logcat tag。 */
#define LOG_TAG "ApmAnrNative"
/** 错误日志宏。 */
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
/** 信息日志宏。 */
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/** Signal Catcher 线程在 /proc/<pid>/task/<tid>/comm 中的名称。 */
#define SIGNAL_CATCHER_THREAD_NAME "Signal Catcher"
/** comm 读取缓冲区大小。 */
#define COMM_BUFFER_SIZE 32
/** comm 路径缓冲区大小。 */
#define COMM_PATH_BUFFER_SIZE 64
/** 接收线程空转睡眠间隔（毫秒）。 */
#define RECEIVER_SLEEP_MS 500
/** 每秒毫秒数。 */
#define MILLIS_PER_SECOND 1000LL
/** 每毫秒纳秒数。 */
#define NANOS_PER_MILLI 1000000LL

/** 最近一次 SIGQUIT 的 epoch 毫秒时间戳；0 表示无未消费信号。 */
static atomic_llong s_sigquit_timestamp_ms = 0;

/** ART Signal Catcher 线程 tid（注册时缓存，处理器内直接使用）。 */
static pid_t s_signal_catcher_tid = -1;

/** 注册前的原始 SIGQUIT 处理配置，注销时恢复。 */
static struct sigaction s_old_sigquit_action;

/** 处理器是否已安装（注册幂等保护）。 */
static volatile sig_atomic_t s_handler_installed = 0;

/** SIGQUIT 接收线程句柄。 */
static pthread_t s_receiver_thread;

/** 接收线程运行标志。 */
static atomic_int s_receiver_running = 0;

/**
 * 读取当前 epoch 毫秒时间戳。
 * clock_gettime 是异步信号安全函数，可在处理器内调用。
 *
 * @return epoch 毫秒
 */
static long long current_time_ms(void) {
    struct timespec ts;
    /* CLOCK_REALTIME 与 Java 的 System.currentTimeMillis 同源 */
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) {
        return 0;
    }
    return (long long)ts.tv_sec * MILLIS_PER_SECOND + ts.tv_nsec / NANOS_PER_MILLI;
}

/**
 * SIGQUIT 信号处理器。
 * 仅执行异步信号安全操作：记录时间戳 + 定向转发给 Signal Catcher。
 *
 * @param sig 信号编号
 * @param info 信号详情（未使用）
 * @param context 上下文（未使用）
 */
static void sigquit_handler(int sig, siginfo_t *info, void *context) {
    (void)info;
    (void)context;

    /* 时间戳为 0 时兜底写 1，保证 Java 侧能感知到信号 */
    long long now = current_time_ms();
    atomic_store(&s_sigquit_timestamp_ms, now > 0 ? now : 1);

    /* 把信号定向转发给 Signal Catcher，保留系统 ANR trace dump 行为；
     * 该线程通过 sigwait 消费信号，不会再次触发本处理器（无递归风险） */
    if (sig == SIGQUIT && s_signal_catcher_tid > 0) {
        syscall(SYS_tgkill, getpid(), s_signal_catcher_tid, SIGQUIT);
    }
}

/**
 * 查找 ART Signal Catcher 线程的 tid。
 * 遍历 /proc/self/task 下各线程的 comm 名称匹配。
 * 仅在注册阶段调用（opendir/fopen 非异步信号安全，不可在处理器内使用）。
 *
 * @return Signal Catcher tid；未找到返回 -1
 */
static pid_t find_signal_catcher_tid(void) {
    DIR *task_dir = opendir("/proc/self/task");
    if (task_dir == NULL) {
        return -1;
    }

    pid_t found_tid = -1;
    struct dirent *entry;
    /* 遍历所有线程目录项 */
    while ((entry = readdir(task_dir)) != NULL) {
        /* 跳过 . 和 .. */
        if (entry->d_name[0] == '.') {
            continue;
        }
        char comm_path[COMM_PATH_BUFFER_SIZE];
        snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%s/comm", entry->d_name);
        FILE *comm_file = fopen(comm_path, "r");
        if (comm_file == NULL) {
            continue;
        }
        char comm[COMM_BUFFER_SIZE] = {0};
        /* 读取线程名并匹配 Signal Catcher */
        if (fgets(comm, sizeof(comm), comm_file) != NULL) {
            /* 去掉换行符 */
            comm[strcspn(comm, "\n")] = '\0';
            if (strcmp(comm, SIGNAL_CATCHER_THREAD_NAME) == 0) {
                found_tid = (pid_t)atoi(entry->d_name);
            }
        }
        fclose(comm_file);
        if (found_tid > 0) {
            break;
        }
    }
    closedir(task_dir);
    return found_tid;
}

/**
 * SIGQUIT 接收线程主循环。
 * 解除本线程的 SIGQUIT 阻塞，使进程级 SIGQUIT 投递到本线程并触发处理器；
 * 线程自身仅低频空转，等待注销标志。
 *
 * @param arg 未使用
 * @return NULL
 */
static void *sigquit_receiver_loop(void *arg) {
    (void)arg;
    pthread_setname_np(pthread_self(), "apm-anr-sigquit");

    /* 解除 SIGQUIT 阻塞：ART 默认所有线程阻塞 SIGQUIT，
     * 只有本线程解除后，内核才会把进程级信号投递到这里 */
    sigset_t unblock_set;
    sigemptyset(&unblock_set);
    sigaddset(&unblock_set, SIGQUIT);
    pthread_sigmask(SIG_UNBLOCK, &unblock_set, NULL);

    /* 低频空转等待注销；信号处理器由内核异步在本线程上调用 */
    struct timespec sleep_spec = {
        .tv_sec = 0,
        .tv_nsec = RECEIVER_SLEEP_MS * NANOS_PER_MILLI
    };
    while (atomic_load(&s_receiver_running)) {
        nanosleep(&sleep_spec, NULL);
    }
    return NULL;
}

/**
 * 注册 SIGQUIT 信号处理器。
 * 缓存 Signal Catcher tid → 安装 sigaction → 启动接收线程。
 *
 * @param env JNIEnv
 * @param thiz AnrModule 实例
 */
JNIEXPORT void JNICALL
Java_com_apm_anr_AnrModule_nativeRegisterSigquitHandler(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    /* 幂等保护：重复注册直接返回 */
    if (s_handler_installed) {
        return;
    }

    /* 注册阶段缓存 Signal Catcher tid，处理器内禁止做目录遍历 */
    s_signal_catcher_tid = find_signal_catcher_tid();
    if (s_signal_catcher_tid <= 0) {
        LOGE("Signal Catcher thread not found; system trace dump will not be forwarded");
    }

    /* 安装 SIGQUIT 处理器，保存原始配置供注销恢复 */
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = sigquit_handler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
    if (sigaction(SIGQUIT, &action, &s_old_sigquit_action) != 0) {
        LOGE("sigaction(SIGQUIT) failed");
        return;
    }

    /* 启动专用接收线程（唯一解除 SIGQUIT 阻塞的线程） */
    atomic_store(&s_receiver_running, 1);
    if (pthread_create(&s_receiver_thread, NULL, sigquit_receiver_loop, NULL) != 0) {
        LOGE("failed to start sigquit receiver thread");
        /* 回滚 sigaction，保持未安装状态 */
        sigaction(SIGQUIT, &s_old_sigquit_action, NULL);
        atomic_store(&s_receiver_running, 0);
        return;
    }

    s_handler_installed = 1;
    LOGI("SIGQUIT handler installed, signal catcher tid=%d", (int)s_signal_catcher_tid);
}

/**
 * 注销 SIGQUIT 信号处理器。
 * 恢复原始 sigaction 并停止接收线程。
 *
 * @param env JNIEnv
 * @param thiz AnrModule 实例
 */
JNIEXPORT void JNICALL
Java_com_apm_anr_AnrModule_nativeUnregisterSigquitHandler(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    /* 未安装时无需注销 */
    if (!s_handler_installed) {
        return;
    }

    /* 恢复原始信号处理配置 */
    sigaction(SIGQUIT, &s_old_sigquit_action, NULL);

    /* 停止并回收接收线程 */
    atomic_store(&s_receiver_running, 0);
    pthread_join(s_receiver_thread, NULL);

    s_handler_installed = 0;
    LOGI("SIGQUIT handler uninstalled");
}

/**
 * 消费最近一次 SIGQUIT 时间戳。
 * 原子交换读取并清零，Java 侧轮询调用。
 *
 * @param env JNIEnv
 * @param thiz AnrModule 实例
 * @return epoch 毫秒时间戳；0 表示无未消费信号
 */
JNIEXPORT jlong JNICALL
Java_com_apm_anr_AnrModule_nativeConsumeSigquitTimestamp(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jlong)atomic_exchange(&s_sigquit_timestamp_ms, 0);
}
