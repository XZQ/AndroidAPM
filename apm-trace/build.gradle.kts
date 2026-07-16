plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.trace"
}

dependencies {
    api(project(":apm-core"))
    implementation(project(":apm-model"))
    testImplementation(libs.junit)
}
