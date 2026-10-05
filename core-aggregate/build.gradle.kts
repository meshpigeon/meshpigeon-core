plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.mavenPublish)
}

/**
 * The aggregating artifact: `dev.meshpigeon:meshpigeon-core`.
 *
 * Clients depend on this one coordinate and get every module they are allowed
 * to see through `api` dependencies. What is *not* here is as important as what
 * is — `core-testing` is deliberately absent, so a published client cannot ship
 * the fakes by accident (tests depend on it directly instead).
 *
 * No `mingwX64` yet, because `core-link-tcp` has no Winsock source set; the
 * Windows target returns when it does (the P2 phase).
 */

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

    sourceSets {
        commonMain.dependencies {
            api(project(":proto-gen"))
            api(project(":core-common"))
            api(project(":core-transport"))
            api(project(":core-link-tcp"))
            api(project(":core-link-usb"))
            api(project(":core-domain"))
            api(project(":core-storage"))
        }
    }
}

// Published as `dev.meshpigeon:meshpigeon-core` (the module name is an internal detail).
extra["POM_ARTIFACT_ID"] = "meshpigeon-core"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    pom {
        name.set("MeshPigeon core")
        description.set("Everything MeshPigeon that is not a user interface: the radio API, transports, mesh protocols, the packet pipeline, the SQLite data model and the service clients call.")
        url.set("https://github.com/meshpigeon/meshpigeon-core")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }
    }
}