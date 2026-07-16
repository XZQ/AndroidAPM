check(JavaVersion.current() == JavaVersion.VERSION_17) {
    "apm-plugin requires JDK 17 to run Gradle; current runtime is ${JavaVersion.current()}."
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "apm-plugin"
