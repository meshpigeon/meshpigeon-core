rootProject.name = "meshpigeon-core"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":proto-gen")
include(":core-aggregate")
include(":core-common")
include(":core-transport")
include(":core-link-tcp")
include(":core-link-usb")
include(":core-testing")