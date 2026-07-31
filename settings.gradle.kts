/** Minimum supported JVM for running Gradle and the Android Gradle Plugin. */
val minimumGradleRuntime = JavaVersion.VERSION_17

if (!JavaVersion.current().isCompatibleWith(minimumGradleRuntime)) {
    throw GradleException(
        "AndroidAPM requires JDK 17 or newer to run Gradle, but the current runtime is " +
            "${System.getProperty("java.version")}. Set JAVA_HOME to a JDK 17+ installation " +
            "before invoking gradlew.",
    )
}

pluginManagement {
    // 共享构建约定（convention plugin）所在的 included build
    includeBuild("build-logic")
    includeBuild("apm-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidAPM"

include(
    ":apm-model",
    ":apm-storage",
    ":apm-uploader",
    ":apm-core",
    ":apm-memory",
    ":apm-crash",
    ":apm-anr",
    ":apm-launch",
    ":apm-network",
    ":apm-fps",
    ":apm-slow-method",
    ":apm-io",
    ":apm-thread-monitor",
    ":apm-battery",
    ":apm-sqlite",
    ":apm-webview",
    ":apm-ipc",
    ":apm-gc-monitor",
    ":apm-render",
    ":apm-trace",
    ":apm-otel-exporter",
    ":apm-remote-config",
    ":apm-bundle",
    ":apm-benchmark",
    ":apm-sample-app"
)
