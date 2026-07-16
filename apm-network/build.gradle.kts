plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.network"
}

dependencies {
    api(project(":apm-core"))
    implementation(project(":apm-model"))
    compileOnly(libs.okhttp)
    testImplementation(libs.junit)
}
