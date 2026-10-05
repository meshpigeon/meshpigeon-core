plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)

    // `expect`/`actual` classes are used deliberately here: a driver, a key file
    // and a lock file are genuine platform boundaries. The Beta warning is noise.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    // SQLite comes from the *system* on Linux (`libsqlite3`, through SQLDelight's
    // cinterop), and Kotlin/Native links shared libraries with
    // `--no-allow-shlib-undefined`: every system library has libc references the
    // link has not resolved yet, so it stops at `pthread_join@GLIBC_2.34` before
    // reaching our code. The flag says "those belong to the dynamic linker",
    // which on a dynamically linked Linux binary is what resolves them at
    // startup anyway.
    //
    // Linux only: macOS links its system library without this, and the flag
    // would be cargo cult. Revisit when SQLDelight ships SQLite compiled in
    // (2.6+), which removes the question entirely.
    linuxX64 {
        binaries.all { linkerOpts("-Wl,--allow-shlib-undefined") }
    }
    linuxArm64 {
        binaries.all { linkerOpts("-Wl,--allow-shlib-undefined") }
    }
    macosX64()
    macosArm64()

    // No `mingwX64` yet: the Windows phase brings its own driver story, and a
    // storage module that compiles but cannot open a database is worse than one
    // that is not here yet.

    sourceSets {
        commonMain.dependencies {
            api(project(":core-domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        jvmMain.dependencies {
            // JDBC rather than SQLDelight's own bundled native: a JVM app has a
            // SQLite, and this is the driver that finds it.
            implementation(libs.sqldelight.jdbc)
            implementation(libs.sqlite.jdbc)
        }
        // The native driver, on the two platform *groups*: the metadata
        // compilations run against them too, so declaring per target would leave
        // `compileMacosMainKotlinMetadata` without the driver.
        linuxMain.dependencies { implementation(libs.sqldelight.native) }
        macosMain.dependencies { implementation(libs.sqldelight.native) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

sqldelight {
    databases {
        // One database, and its name is the file name: `meshpigeon.db`. There is
        // no second schema-version counter anywhere — `PRAGMA user_version` is
        // the truth (plan §8.2).
        create("MeshPigeonDatabase") {
            packageName.set("dev.meshpigeon.storage")
            srcDirs.setFrom("src/commonMain/sqldelight")
        }
    }
}

// Published as `dev.meshpigeon:meshpigeon-core-storage` (the module name is an internal detail).
extra["POM_ARTIFACT_ID"] = "meshpigeon-core-storage"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    pom {
        name.set("MeshPigeon core storage")
        description.set("The one SQLite database: the plan's §8.2 schema, migrations, the radio repositories and the sealed secret store.")
        url.set("https://github.com/meshpigeon/meshpigeon-core")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }
    }
}