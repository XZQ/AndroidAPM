pluginManagement {
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
    ":apm-sample-app",
    ":apm-plugin"
)
