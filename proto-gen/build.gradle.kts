plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.wire)
    alias(libs.plugins.mavenPublish)
}

// The radio API is meshpigeon-firmware's protobuf schema, consumed from the
// `proto` git submodule (pinned to a firmware commit) — never copied, so the
// copy cannot rot. Wire generates Kotlin straight into commonMain; the
// generated package stays `meshpigeon`, exactly as the schema declares it.
wire {
    // The schema root is the submodule's protobufs directory: the imports in the
    // .proto files are `meshpigeon/<file>.proto`.
    sourcePath(rootProject.file("proto/protobufs").absolutePath)
    kotlin {
        // The plugin registers this directory with commonMain and wires the task
        // into compilation, so nothing else has to know about it.
        out = layout.buildDirectory.dir("generated/source/wire").get().asFile.path
    }
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
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Published as `dev.meshpigeon:meshpigeon-core-proto` (the module name is an internal detail).
extra["POM_ARTIFACT_ID"] = "meshpigeon-core-proto"

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    pom {
        name.set("MeshPigeon radio protocol")
        description.set("Wire-generated Kotlin for the meshpigeon-firmware radio API.")
        url.set("https://github.com/meshpigeon/meshpigeon-core")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }
    }
}