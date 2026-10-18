plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":adapter-sqlite"))
    implementation(libs.kotlinx.json)
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":adapter-jmc"))
}
application { mainClass.set("dev.jdrift.app.MainKt") }
