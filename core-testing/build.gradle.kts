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
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core-transport"))
            // The in-memory RadioRepository is part of what a test needs, and
            // `api` because it is in this module's published signature.
            api(project(":core-domain"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Published as `dev.meshpigeon:meshpigeon-core-testing` (the module name is an internal detail).
extra["POM_ARTIFACT_ID"] = "meshpigeon-core-testing"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    pom {
        name.set("MeshPigeon core testing")
        description.set("Published fakes: a scripted radio link, a fake radio speaking the real framing and protobuf API, an in-memory radio repository, a fake clock and a recording logger.")
        url.set("https://github.com/meshpigeon/meshpigeon-core")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }
    }
}