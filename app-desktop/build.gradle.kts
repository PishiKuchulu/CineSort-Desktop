import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

// This module is KMP with a single jvm() target for the same reason as
// core-data-desktop: as a plain kotlin("jvm") consumer it re-resolved
// Room's root module directly and found no JVM variant (CI run #3,
// packageExe). As a KMP consumer it receives core-data-desktop's JVM
// variant with Room/DataStore resolved through their KMP metadata.
// The existing src/main layout is kept via the srcDir wiring below.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                implementation(project(":core-data-desktop"))
                // The UI names domain/parser types directly (PlannedItem,
                // ParseResult, reports); core-data-desktop exposes them
                // only as implementation deps, so the app declares its own.
                implementation(project(":shared:core-domain"))
                implementation(project(":shared:core-parser"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(libs.kotlinx.coroutines.core)
                // AppState calls Room-generated DAO methods directly, so
                // Room's runtime must be on the app's compile classpath too.
                implementation(libs.room.runtime)
                implementation(libs.sqlite.bundled)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.cinesort.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "CineSort"
            // macOS DMG validation requires MAJOR > 0, so the desktop
            // line starts at 1.0.0 (a 0.x packageVersion fails the
            // whole build at configuration time — CI run #1).
            packageVersion = "1.1.1"
            description = "Organize movie and series files into a clean library"
            vendor = "PishiKuchulu"
        }
    }
}
