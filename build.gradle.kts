import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
//    id("com.gradleup.shadow") version "9.6.1"
    id("idea")
}

version = providers.gradleProperty("version").get()
group = providers.gradleProperty("maven_group").get()
val projectName = project.name

repositories {
    mavenCentral()
    maven {
        name = "MoeMusic on Codeberg"
        url = uri("https://codeberg.org/api/packages/lolicode/maven")
        content {
            includeGroupByRegex("org\\.lolicode.*")
        }
    }

    // Optional fallback for developers who publish/test the MoeMusic API locally.
    mavenLocal()

    maven {
        name = "GitHubPackages MoeMusic"
        url = uri("https://maven.pkg.github.com/lolicode-org/MoeMusic")
        credentials {
            username = System.getenv("GITHUB_ACTOR").orEmpty()
            password = System.getenv("GITHUB_TOKEN").orEmpty()
        }
    }
}

dependencies {
    // MoeMusic API transitively provides the guaranteed runtime baseline:
    // Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization (core + json), and SLF4J API.
    compileOnly("org.lolicode.moemusic:api:${providers.gradleProperty("plugin_api_version").get()}")

    testImplementation(kotlin("test"))
    testImplementation("org.lolicode.moemusic:api:${providers.gradleProperty("plugin_api_version").get()}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

tasks.jar {
    inputs.property("projectName", projectName)

    from("LICENSE") {
        rename { "${it}_${projectName}" }
    }
}

//tasks.shadowJar {
//    archiveClassifier.set("full")
//
//    /*
//     * Add third-party implementation dependencies above when your real plugin needs them.
//     * The shadow jar is the artifact users should put in config/moemusic/plugins/.
//     *
//     * Host-provided dependencies such as MoeMusic API, Kotlin runtime, slf4j, and serialization
//     * stay outside this jar because the MoeMusic standalone plugin classloader delegates those
//     * packages to the parent runtime first.
//     */
//    dependencies {
//        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
//        exclude(dependency("org.jetbrains.kotlinx:.*:.*"))
//        exclude(dependency("org.jetbrains:annotations:.*"))
//    }
//}
