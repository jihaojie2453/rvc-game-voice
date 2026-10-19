pluginManagement {
    repositories {
        // Mainland China mirrors first; gradle falls back to the official repositories.
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}
rootProject.name = "VoiceStudioPhone"
include(":app")
// Opt-in, isolated numerical experiment app; never part of the normal release.
if (providers.gradleProperty("decoderProbe").orNull == "true") include(":decoderprobe")
