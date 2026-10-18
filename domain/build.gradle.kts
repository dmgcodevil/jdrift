plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.kotlinx.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
