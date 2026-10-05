import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.wire) apply false
    alias(libs.plugins.mavenPublish) apply false
}

// The module dependency rule (docs/architecture.md): knowledge flows one way,
// and the check is mechanical rather than a code-review habit.
//
//   common -> crypto -> transport -> domain -> protocols -> storage -> service
//   with core-testing (fakes) standing in for any of them.
//
// `scripts/check-module-deps.sh` parses the project dependencies out of each
// module's build file and fails on an edge that is not on that list, so a new
// module cannot quietly reach sideways. Run it alone with `--print` to see the
// graph this build declares.
val checkModuleDependencies by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails if a module depends on a module the architecture forbids"
    commandLine("sh", "scripts/check-module-deps.sh")
    workingDir = rootDir
}

tasks.named("check") { dependsOn(checkModuleDependencies) }

// The published coordinate is the library name, never the module directory's
// (`dev.meshpigeon:meshpigeon-core-common`, not `dev.meshpigeon:core-common`).
// Each module states its own coordinate as `POM_ARTIFACT_ID`; this renames the
// whole publication set to match — the per-target ones included.
//
// It has to run after the Kotlin plugin has named them, which is why it is not
// simply `artifactId = …` in a module: that loses the race and leaves
// `core-common-linuxx64` shipping under the directory name.
gradle.projectsEvaluated {
    subprojects.filter { it.extensions.findByType<PublishingExtension>() != null }
        .forEach { project ->
            val coordinate = project.findProperty("POM_ARTIFACT_ID") as String? ?: return@forEach
            project.extensions.getByType<PublishingExtension>().publications
                .withType(MavenPublication::class.java)
                .configureEach {
                    val declared = artifactId
                    if (declared.startsWith(project.name)) {
                        artifactId = coordinate + declared.removePrefix(project.name)
                    }
                }
        }
}