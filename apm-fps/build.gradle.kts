plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.fps"
}

dependencies {
    api(project(":apm-core"))
    implementation(project(":apm-model"))
    testImplementation(libs.junit)
}
