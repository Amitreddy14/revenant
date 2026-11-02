rootProject.name = "revenant"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "revenant-core",
    "revenant-persistence",
    "revenant-worker-sdk",
    "revenant-server",
)
