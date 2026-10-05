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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Published as `dev.meshpigeon:meshpigeon-core-common` (the module name is an internal detail).
extra["POM_ARTIFACT_ID"] = "meshpigeon-core-common"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    pom {
        name.set("MeshPigeon core common")
        description.set("Ids, encodings, clock, logging and the radio framing primitives shared by every MeshPigeon module.")
        url.set("https://github.com/meshpigeon/meshpigeon-core")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }
    }
}