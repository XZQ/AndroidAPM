check(JavaVersion.current() == JavaVersion.VERSION_17) {
    "build-logic requires JDK 17 to run Gradle; current runtime is ${JavaVersion.current()}."
}

// build-logic 是仓库内共享构建约定的 included build，只承载 convention plugin，不发布产物。
pluginManagement {
    // kotlin-dsl 等构建插件的解析仓库
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // convention plugin 编译期依赖（AGP/KGP）的解析仓库
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        // 复用主构建的版本目录，保证 AGP/Kotlin 版本单一来源
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
