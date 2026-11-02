import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "earcam.desktop.MainKt"
        // `./gradlew :desktop:run --args=127.0.0.2` to use a fixed camera address.
        nativeDistributions {
            // Build the package for the OS you are on: packageDeb / packageRpm (Linux),
            // packageMsi (Windows), packageDmg (macOS). jpackage can't cross-build.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "earcam"
            packageVersion = "1.0.0"
            description = "Viewer for Wi-Fi in-ear cameras"
            vendor = "earcam"
            // Only the JDK modules the app needs, for a smaller bundled runtime.
            modules("java.prefs", "jdk.unsupported")
            linux {
                packageName = "earcam"
                menuGroup = "Graphics"
                iconFile = project.file("icons/earcam.png")
            }
            windows {
                menuGroup = "EarCam"
                perUserInstall = true
                upgradeUuid = "5d3c1a3e-8f7b-4c62-9b0e-6a2f1c9d4e71"
            }
            macOS {
                bundleID = "earcam.desktop"
            }
        }
    }
}
