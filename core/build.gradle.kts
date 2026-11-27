import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "com.pocketnode.server.DevMainKt"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    // Nur zum Kompilieren: Die App bringt litertlm-android mit (gleiche öffentliche API), der Desktop-Lauf litertlm-jvm.
    compileOnly(libs.litertlm.jvm)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.litertlm.jvm)
}

tasks.test {
    useJUnitPlatform()
    // Tests mit echtem Modell laufen nur, wenn die Variable gesetzt ist.
    System.getenv("POCKETNODE_TEST_MODEL")?.let { environment("POCKETNODE_TEST_MODEL", it) }
    maxHeapSize = "2g"
}
