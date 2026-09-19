import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = "com.digitalbluebird"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Maven Central (Central Portal) publishing. Ready to publish; the publish itself is user-driven and
// needs a verified com.digitalbluebird namespace, a GPG key, and credentials. Central Portal takes
// releases only, so bump off -SNAPSHOT first. See PUBLISHING.md. Signing runs only on publish tasks.
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), "once4k", version.toString())
    pom {
        name.set("once4k")
        description.set("At-most-once execution for JVM services, keyed by an idempotency key.")
        url.set("https://github.com/raby/once4k")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("raby")
                name.set("Raby Whyte")
                url.set("https://digitalbluebird.com")
            }
        }
        scm {
            url.set("https://github.com/raby/once4k")
            connection.set("scm:git:https://github.com/raby/once4k.git")
            developerConnection.set("scm:git:ssh://git@github.com/raby/once4k.git")
        }
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.assertk)
    testImplementation(libs.h2) // an in-memory JDBC database for the JdbcStore tests
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Run the indicative microbenchmark: ./gradlew benchmark
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Run the indicative once4k microbenchmark."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.digitalbluebird.once4k.benchmark.BenchmarkKt")
}
