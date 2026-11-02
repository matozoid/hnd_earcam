import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Java 17 bytecode, so the same library runs on Android.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":simulator"))
}

tasks.test {
    useJUnitPlatform()
}

