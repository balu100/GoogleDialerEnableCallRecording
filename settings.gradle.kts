pluginManagement {
    // Optional credential-free development build from the exact upstream tags.
    if (providers.gradleProperty("localMorpheSources").orNull == "true") {
        includeBuild(".tooling/morphe-patches-gradle-plugin-1.3.4")
    }
    repositories {
        gradlePluginPortal()
        google()
        maven {
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.4"
}

rootProject.name = "google-phone-call-recording-patches"
settings {
    extensions { projectsPath = null }
}

if (providers.gradleProperty("localMorpheSources").orNull == "true") {
    includeBuild(".tooling/morphe-patcher-1.15.1")
    // The upstream settings plugin creates this repository even for source builds.
    dependencyResolutionManagement.repositories.removeAll {
        it is MavenArtifactRepository && it.url.host == "maven.pkg.github.com"
    }
}
