import com.android.build.gradle.LibraryExtension
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    // 统一走 version catalog 的插件别名，激活 libs.versions.toml 的 [plugins] 配置
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.androidx.benchmark) apply false
}

group = "com.apm"
version = "0.1.0"

/** 项目主页地址，写入所有发布产物的 POM。 */
val projectUrl = "https://github.com/XZQ/AndroidAPM"

/** Git SCM 连接串，写入 POM 的 scm 段。 */
val projectScmConnection = "scm:git:git://github.com/XZQ/AndroidAPM.git"

/**
 * 为一个 Maven 发布产物填充 Maven Central 要求的 POM 元数据
 * （name/description/url/license/developer/scm）。
 */
fun MavenPublication.configureApmPom(moduleName: String) {
    pom {
        name.set(moduleName)
        description.set("AndroidAPM $moduleName module, part of a modular Android application performance monitoring SDK")
        url.set(projectUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("YSHEN53")
                name.set("YSHEN53")
            }
        }
        scm {
            url.set(projectUrl)
            connection.set(projectScmConnection)
            developerConnection.set(projectScmConnection)
        }
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    // 对所有应用了 maven-publish 的模块统一补 POM 元数据与可选签名
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            publications.withType<MavenPublication>().configureEach {
                // 每个发布产物都带上完整 POM 元数据，满足 Maven Central 校验
                configureApmPom(this@subprojects.name)
            }
        }

        // 仅当存在签名配置（gradle 属性或环境变量）时才启用签名，
        // 本地开发与无密钥 CI 的 publishToMavenLocal 不受影响
        val hasSigningKey = providers.gradleProperty("signing.keyId").isPresent ||
            System.getenv("SIGNING_KEY") != null
        if (hasSigningKey) {
            pluginManager.apply("signing")
            extensions.configure<SigningExtension>("signing") {
                val inMemoryKey = System.getenv("SIGNING_KEY")
                if (inMemoryKey != null) {
                    // CI 场景：从环境变量注入内存中的 PGP 私钥
                    useInMemoryPgpKeys(inMemoryKey, System.getenv("SIGNING_PASSWORD"))
                }
                sign(extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    if (name != "apm-benchmark") pluginManager.withPlugin("com.android.library") {
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

        extensions.configure<PublishingExtension>("publishing") {
            publications {
                register<MavenPublication>("release") {
                    groupId = project.group.toString()
                    artifactId = project.name
                    version = project.version.toString()
                }
            }
        }

        components.configureEach {
            if (name == "release") {
                val releaseComponent: SoftwareComponent = this
                extensions.configure<PublishingExtension>("publishing") {
                    publications.named<MavenPublication>("release") {
                        from(releaseComponent)
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
        extensions.configure<PublishingExtension>("publishing") {
            publications {
                register<MavenPublication>("release") {
                    groupId = project.group.toString()
                    artifactId = project.name
                    version = project.version.toString()
                }
            }
        }
        components.configureEach {
            if (name == "java") {
                val javaComponent: SoftwareComponent = this
                extensions.configure<PublishingExtension>("publishing") {
                    publications.named<MavenPublication>("release") {
                        from(javaComponent)
                    }
                }
            }
        }
    }
}
