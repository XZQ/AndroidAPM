check(JavaVersion.current() == JavaVersion.VERSION_17) {
    "Maven consumer smoke build requires JDK 17; current runtime is ${JavaVersion.current()}."
}

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
        mavenLocal()
        google()
        mavenCentral()
    }
    versionCatalogs {
        // 复用主仓库的版本目录，消除 AGP/Kotlin 版本的硬编码重复
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "android-apm-maven-consumer"
