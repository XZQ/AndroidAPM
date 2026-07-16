plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.core"
}

dependencies {
    api(project(":apm-model"))
    implementation(project(":apm-storage"))
    api(project(":apm-uploader"))
    implementation(libs.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
