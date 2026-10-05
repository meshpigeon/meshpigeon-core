plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)

    // `expect`/`actual` classes are used deliberately here: a serial port is a
    // genuine platform boundary. The Beta warning is noise.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()

    // No `mingwX64` yet: a Windows serial port is `CreateFile`/`SetCommState`,
    // which is a different API rather than a different constant, and it arrives
    // with the rest of the Windows phase.

    sourceSets {
        commonMain.dependencies {
            api(project(":core-transport"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Published as `dev.meshpigeon:meshpigeon-core-link-usb` (the module name is an internal detail).
extra["POM_ARTIFACT_ID"] = "meshpigeon-core-link-usb"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    pom {
        name.set("MeshPigeon core link usb")
        description.set("The USB-CDC transport: termios on Linux and macOS, streams on the JVM, and the seam an Android USB host plugs its own streams into.")
        url.set("https://github.com/meshpigeon/meshpigeon-core")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }
    }
}