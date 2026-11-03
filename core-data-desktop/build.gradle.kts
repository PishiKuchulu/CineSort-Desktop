plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.ksp)
}

// Room 2.6.1 and DataStore 1.1.1 publish their JVM variants only through
// Kotlin Multiplatform metadata — a plain kotlin("jvm") consumer cannot
// resolve them (CI run #2: "No matching variant of room-runtime"). This
// module is therefore a KMP module with a single jvm() target, pinned to
// the same dependency versions as the Android app. The existing
// src/main + src/test layout is kept via srcDir wiring below (no file
// moves), so the module tree stays identical in shape to the others.
kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                implementation(project(":shared:core-domain"))
                implementation(project(":shared:core-parser"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.room.runtime)
                implementation(libs.sqlite.bundled)
                implementation(libs.datastore.preferences.core)
            }
        }
        val jvmTest by getting {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(libs.junit.jupiter)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

dependencies {
    add("kspJvm", libs.room.compiler)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
