import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.jmh)
}

group = "com.digitalbluebird"
version = "0.3.0-SNAPSHOT"

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
    compilerOptions {
        // Emit method parameter names so Spring's SpEL can resolve #paramName in @Idempotent keys.
        javaParameters = true
    }
}

dependencies {
    // Jedis is compileOnly: it backs the optional JedisRedisCommands adapter, but callers who use a
    // different store (in-memory, JDBC) never pull it in. Add it yourself to use RedisStore with Jedis.
    compileOnly(libs.jedis)

    // Spring + AspectJ back the optional @Idempotent aspect; a Spring app already provides them, and a
    // non-Spring caller pulls in nothing. Add them yourself to use the aspect.
    compileOnly(libs.spring.context)
    compileOnly(libs.aspectjweaver)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.assertk)
    testImplementation(libs.h2) // an in-memory JDBC database for the JdbcStore tests
    testImplementation(libs.spring.context) // a real Spring context for the @Idempotent aspect test
    testImplementation(libs.spring.test)
    testImplementation(libs.aspectjweaver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// JMH microbenchmarks live in src/jmh; run them with: ./gradlew jmh
jmh {
    jmhVersion.set("1.37")
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(2)
    warmup.set("1s")
    timeOnIteration.set("1s")
}
