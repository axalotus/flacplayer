import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Audio decoding for MP3, FLAC, WAV, OGG
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")
    implementation("org.jflac:jflac-codec:1.5.2")

    // Wi-Fi Sync Server (Ktor embedded server for pairing with mobile)
    implementation("io.ktor:ktor-server-core-jvm:3.0.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.3")
    implementation("io.ktor:ktor-server-cors-jvm:3.0.3")
}

compose.desktop {
    application {
        mainClass = "com.antigravity.audioplayer.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
            packageName = "FlacPlayer"
            packageVersion = "1.0.0"
            description = "FlacPlayer Desktop Hi-Res Audio Player"
            copyright = "© 2026 Antigravity Team"
            vendor = "Antigravity"

            windows {
                menuGroup = "FlacPlayer"
                upgradeUuid = "6f56e07a-d021-4f9e-a864-1c8812c3f8f1"
            }
        }
    }
}
