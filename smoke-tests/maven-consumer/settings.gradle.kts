val apmRepositoryPath = providers.gradleProperty("apmRepositoryPath").orNull

pluginManagement {
    val apmPluginRepositoryPath = providers.gradleProperty("apmRepositoryPath").orNull
    repositories {
        if (apmPluginRepositoryPath != null) {
            maven {
                name = "androidApmReleaseCandidate"
                url = uri(file(apmPluginRepositoryPath))
                content {
                    includeGroup("com.apm.slow-method")
                    includeGroup("com.apm")
                }
            }
        } else {
            mavenLocal()
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (apmRepositoryPath != null) {
            exclusiveContent {
                forRepository {
                    maven {
                        name = "androidApmReleaseCandidate"
                        url = uri(file(apmRepositoryPath))
                    }
                }
                filter {
                    includeGroup("com.apm")
                }
            }
        } else {
            mavenLocal()
        }
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
