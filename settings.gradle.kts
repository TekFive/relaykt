pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download a JDK 25 toolchain when the build host (CI, JitPack) does not have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.TekFive")
            }
        }
    }
}

rootProject.name = "relaykt"

// Standalone builds resolve the sibling TekFive libraries from JitPack. Full-source development can
// substitute sibling checkouts (../keep, ../ack, ../jfk) with -Prelaykt.useLocalProjects=true or
// RELAYKT_USE_LOCAL_PROJECTS=true.
val useLocalProjects =
    providers.gradleProperty("relaykt.useLocalProjects").orNull?.toBooleanStrictOrNull()
        ?: System.getenv("RELAYKT_USE_LOCAL_PROJECTS")?.toBooleanStrictOrNull()
        ?: false

if (useLocalProjects) {
    mapOf(
        "ack" to "com.github.TekFive:ack",
        "jfk" to "com.github.TekFive:jfk",
        "keep" to "com.github.TekFive:keep",
    ).forEach { (projectName, moduleCoordinates) ->
        includeBuild("../$projectName") {
            dependencySubstitution {
                substitute(module(moduleCoordinates)).using(project(":"))
            }
        }
    }
}
