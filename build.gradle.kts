plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

val isJitPackBuild = providers.environmentVariable("JITPACK").orNull == "true"

group = if (isJitPackBuild) {
    providers.environmentVariable("GROUP").getOrElse("com.github.TekFive")
} else {
    "org.tekfive"
}
version = if (isJitPackBuild) {
    providers.environmentVariable("VERSION").getOrElse("1.0.0")
} else {
    "1.0.0"
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = false
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.slf4j.api)
    implementation(libs.okhttp)
    implementation(libs.jakarta.mail)

    // The TekFive foundation libraries are part of RelayKt's public API (Data tables, Ack
    // properties, JsonObject configuration), so consumers get them transitively.
    api(libs.tekfive.keep)
    api(libs.tekfive.ack)
    api(libs.tekfive.jfk)
    constraints {
        // keep's POM requests a commit-hash JFK build that Gradle would otherwise prefer; pin the
        // catalog version so standalone, CI, and local-project builds all compile against the same JFK.
        api(libs.tekfive.jfk) {
            version { strictly(libs.versions.tekfive.jfk.get()) }
        }
    }

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Integration tests need Docker (Testcontainers); they skip themselves when it is unavailable.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

allprojects {
    // Workaround: some container overlay filesystems corrupt jars and Gradle caches during Kotlin
    // compilation. Redirect build output when explicitly enabled.
    System.getenv("SANDBOX_BUILD_DIR")?.let { sandboxDir ->
        layout.buildDirectory = file("$sandboxDir/${project.name}")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "relaykt"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(
                System.getenv("GITHUB_REPOSITORY")?.let { "https://maven.pkg.github.com/$it" }
                    ?: "https://maven.pkg.github.com/TekFive/relaykt",
            )
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
}
