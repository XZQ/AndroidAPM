plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.bundle"
}

dependencies {
    // Foundation APIs remain explicit so the bundle exposes the complete supported client surface.
    api(project(":apm-model"))
    api(project(":apm-storage"))
    api(project(":apm-uploader"))
    api(project(":apm-core"))

    // Monitoring modules are transitively available from one consumer dependency.
    api(project(":apm-memory"))
    api(project(":apm-crash"))
    api(project(":apm-anr"))
    api(project(":apm-launch"))
    api(project(":apm-network"))
    api(project(":apm-fps"))
    api(project(":apm-slow-method"))
    api(project(":apm-io"))
    api(project(":apm-thread-monitor"))
    api(project(":apm-battery"))
    api(project(":apm-sqlite"))
    api(project(":apm-webview"))
    api(project(":apm-ipc"))
    api(project(":apm-gc-monitor"))
    api(project(":apm-render"))

    // Extensions and the signed control-plane client are intentionally included in the full bundle.
    api(project(":apm-trace"))
    api(project(":apm-otel-exporter"))
    api(project(":apm-remote-config"))
}
