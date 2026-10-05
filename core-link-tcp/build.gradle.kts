plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)

    // `expect`/`actual` classes are used deliberately here: a socket, a clock and
    // a log are genuine platform boundaries. The Beta warning is noise.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()

    // No `mingwX64` yet, and the socket has one source file per native platform
    // rather than one shared: the C types around a socket do not agree across
    // them (a size argument is a ULong on POSIX and a UInt on Winsock, the
    // address struct is a plain field on POSIX and a union on Winsock) and the
    // compiler will not accept a generic numeric conversion it cannot prove
    // equal across a group of platforms. Windows arrives with its own file,
    // alongside the rest of the Windows phase.

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

// Published as `dev.meshpigeon:meshpigeon-core-link-tcp` (the module name is an internal detail).
extra["POM_ARTIFACT_ID"] = "meshpigeon-core-link-tcp"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    pom {
        name.set("MeshPigeon core link tcp")
        description.set("The Wi-Fi TCP transport: the primary link for a radio on a network, and the one the simulator speaks.")
        url.set("https://github.com/meshpigeon/meshpigeon-core")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }
    }
}