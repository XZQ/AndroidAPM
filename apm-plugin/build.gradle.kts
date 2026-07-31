import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
    alias(libs.plugins.binary.compatibility.validator)
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.apm"
version = "0.1.0"

val projectUrl = "https://github.com/XZQ/AndroidAPM"
val projectScmConnection = "scm:git:git://github.com/XZQ/AndroidAPM.git"
val releaseRepositoryPath = providers.gradleProperty("apmReleaseRepository")
    .orElse(
        layout.buildDirectory.dir("release-candidate/repository")
            .map { it.asFile.absolutePath },
    )
val requireReleaseSigning = providers.gradleProperty("apmRequireSigning")
    .map(String::toBoolean)
    .orElse(false)
val hasSigningKey = providers.gradleProperty("signing.keyId").isPresent ||
    !System.getenv("SIGNING_KEY").isNullOrBlank()
val externalReleaseRepositoryUrl = providers.gradleProperty("apmExternalRepositoryUrl")
    .orElse(providers.environmentVariable("APM_RELEASE_REPOSITORY_URL"))
    .orNull
val externalReleaseRepositoryUsername = providers.gradleProperty("apmExternalRepositoryUsername")
    .orElse(providers.environmentVariable("APM_RELEASE_REPOSITORY_USERNAME"))
    .orNull
val externalReleaseRepositoryPassword = providers.environmentVariable(
    "APM_RELEASE_REPOSITORY_PASSWORD",
).orNull

if (requireReleaseSigning.get() && !hasSigningKey) {
    throw GradleException(
        "apmRequireSigning=true requires signing.keyId Gradle properties or " +
            "SIGNING_KEY/SIGNING_PASSWORD environment variables.",
    )
}
if (externalReleaseRepositoryUrl != null) {
    require(externalReleaseRepositoryUrl.startsWith("https://")) {
        "External release repository URLs must use HTTPS."
    }
    require(
        !externalReleaseRepositoryUsername.isNullOrBlank() &&
            !externalReleaseRepositoryPassword.isNullOrBlank(),
    ) {
        "External release publishing requires repository username and password."
    }
    require(hasSigningKey) {
        "External release publishing requires PGP signing configuration."
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(kotlin("stdlib"))
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("apmSlowMethod") {
            id = "com.apm.slow-method"
            implementationClass = "com.apm.plugin.ApmSlowMethodPlugin"
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        val publicationDisplayName = when (name) {
            "pluginMaven" -> "apm-plugin"
            else -> "com.apm.slow-method Gradle plugin marker"
        }
        pom {
            name.set(publicationDisplayName)
            description.set(
                "AndroidAPM $publicationDisplayName publication, part of a modular Android application " +
                    "performance monitoring SDK",
            )
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
    repositories {
        maven {
            name = "releaseCandidate"
            url = uri(releaseRepositoryPath.get())
        }
        if (externalReleaseRepositoryUrl != null) {
            maven {
                name = "externalRelease"
                url = uri(externalReleaseRepositoryUrl)
                credentials {
                    username = externalReleaseRepositoryUsername
                    password = externalReleaseRepositoryPassword
                }
            }
        }
    }
}

if (hasSigningKey) {
    pluginManager.apply("signing")
    extensions.configure<SigningExtension>("signing") {
        val inMemoryKey = System.getenv("SIGNING_KEY")
        if (inMemoryKey != null) {
            useInMemoryPgpKeys(inMemoryKey, System.getenv("SIGNING_PASSWORD"))
        }
        sign(
            extensions.getByType(
                org.gradle.api.publish.PublishingExtension::class.java,
            ).publications,
        )
    }
}
