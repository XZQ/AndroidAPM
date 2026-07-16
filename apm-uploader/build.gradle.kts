plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.uploader"
}

dependencies {
    api(project(":apm-model"))
    testImplementation(libs.junit)
}
