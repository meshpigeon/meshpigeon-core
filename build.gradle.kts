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