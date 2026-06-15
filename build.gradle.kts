import com.android.build.gradle.LibraryExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.application") version libs.versions.agp apply false
    id("com.android.library") version libs.versions.agp apply false
    id("org.jetbrains.kotlin.android") version libs.versions.kotlin apply false
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin apply false
}

group = "com.apm"
version = "0.1.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension>("android") {
            defaultConfig {
                consumerProguardFiles(rootProject.file("consumer-rules.pro"))
            }
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        pluginManager.apply("maven-publish")

        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    if (findByName("release") == null) {
                        create<MavenPublication>("release") {
                            from(components["release"])
                            groupId = project.group.toString()
                            artifactId = project.name
                            version = project.version.toString()
                        }
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension>("java") {
            withSourcesJar()
        }
        pluginManager.apply("maven-publish")
        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    if (findByName("release") == null) {
                        create<MavenPublication>("release") {
                            from(components["java"])
                            groupId = project.group.toString()
                            artifactId = project.name
                            version = project.version.toString()
                        }
                    }
                }
            }
        }
    }
}
