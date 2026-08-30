import java.io.FileInputStream
import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\.android.*")
                includeGroupByRegex("com\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties().apply {
    val file = File(rootDir, "local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}

// The DAT SDK lives on GitHub Packages, which requires auth even for public read access.
// A classic PAT with read:packages, via $GITHUB_TOKEN or local.properties' github_token.
val githubPackagesToken: String? =
    System.getenv("GITHUB_TOKEN") ?: localProperties.getProperty("github_token")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "MetaWearablesDAT"
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "" // not used by GitHub Packages
                password = githubPackagesToken.orEmpty()
            }
            // Only the DAT artifacts come from here, so a missing token can't silently
            // break resolution of anything else.
            content { includeGroup("com.meta.wearable") }
        }
    }
}

rootProject.name = "GlassesGate"

// Pure-JVM protocol module. Deliberately has no Android or DAT dependency, so its tests
// run in CI without an Android SDK or a GitHub Packages token.
include(":core")
include(":app")
