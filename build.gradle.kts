import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    `java-library`
    signing
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("org.cyclonedx.bom") version "2.3.1"
}

group = "com.thalovant"
version = "0.1.3"

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    // JVM 17 bytecode keeps the SDK usable from Android and older JVMs.
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.cyclonedxBom {
    includeConfigs.set(listOf("runtimeClasspath"))
}

// Release signing uses an in-memory ASCII-armored PGP key supplied by CI.
// Local builds without SIGNING_KEY skip signing entirely.
val signingKey: String? = System.getenv("SIGNING_KEY")

mavenPublishing {
    // Sonatype Central Portal (central.sonatype.com); credentials come from the
    // mavenCentralUsername / mavenCentralPassword Gradle properties, which CI
    // provides as ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password.
    publishToMavenCentral()

    coordinates("com.thalovant", "thalovant-sdk", version.toString())

    configure(
        KotlinJvm(
            // Maven Central requires a javadoc jar; an empty one is the
            // accepted convention for Kotlin libraries without Dokka output.
            javadocJar = JavadocJar.Empty(),
            sourcesJar = true,
        )
    )

    pom {
        name.set("Thalovant Kotlin SDK")
        description.set(
            "Kotlin SDK for connecting JVM and Android apps, services, and agents " +
                "to Thalovant hubs over the control plane and HiveMind data plane"
        )
        url.set("https://github.com/thalovant/thalovant-kotlin-sdk")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/thalovant/thalovant-kotlin-sdk/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("thalovant")
                name.set("Thalovant")
                url.set("https://github.com/thalovant")
            }
        }
        scm {
            url.set("https://github.com/thalovant/thalovant-kotlin-sdk")
            connection.set("scm:git:git://github.com/thalovant/thalovant-kotlin-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/thalovant/thalovant-kotlin-sdk.git")
        }
    }

    if (!signingKey.isNullOrBlank()) {
        signAllPublications()
    }
}

if (!signingKey.isNullOrBlank()) {
    signing {
        useInMemoryPgpKeys(signingKey, System.getenv("SIGNING_PASSWORD"))
    }
}
