import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":protocol"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
}

// Release builds pass -PappVersion=1.2.3 (taken from the git tag).
val appVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")

compose.desktop {
    application {
        mainClass = "earcam.desktop.MainKt"
        // Skia loads a native library. JDK 24+ warns about that unless it is allowed.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // `./gradlew :desktop:run --args=127.0.0.2` to use a fixed camera address.
        nativeDistributions {
            // Build the package for the OS you are on: packageDeb / packageRpm (Linux),
            // packageMsi (Windows), packageDmg (macOS). jpackage can't cross-build.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "hnd-earcam"
            packageVersion = appVersion
            description = "Viewer for HND Wi-Fi earcams"
            vendor = "hnd-earcam"
            // Only the JDK modules the app needs, for a smaller bundled runtime.
            modules("java.prefs", "jdk.unsupported")
            linux {
                packageName = "hnd-earcam"
                menuGroup = "Graphics"
                iconFile = project.file("icons/earcam.png")
            }
            windows {
                menuGroup = "HND earcam"
                perUserInstall = true
                upgradeUuid = "5d3c1a3e-8f7b-4c62-9b0e-6a2f1c9d4e71"
            }
            macOS {
                packageName = "HND earcam"
                bundleID = "earcam.desktop"
            }
        }
    }
}
