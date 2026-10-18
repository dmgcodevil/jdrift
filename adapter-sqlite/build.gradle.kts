plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(libs.kotlinx.json)
    implementation(libs.sqlite)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
