plugins {
    `java-library`
    `maven-publish`
    id("ru.vyarus.animalsniffer") version "2.0.1"
}

group = "com.arpabet"
version = "0.1.0"
description = "Deterministic, canonical MessagePack serialization: the Java implementation of go.arpabet.com/value"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The Android API level the library may call into, checked by the
    // animalsniffer task (part of `check`).
    signature("com.toasttab.android:gummy-bears-api-24:0.16.0@signature")
}

java {
    withSourcesJar()
    withJavadocJar()
}

// The library is Java 17 bytecode with no dependencies: the language level of
// AGP 8 and 9 and of every maintained server runtime, and one a Java 21 or 25
// application consumes as it is. On Android it calls nothing above API 24
// (java.time, which needs API 26, is confined to JavaTime and loaded only when
// an application uses it), which the animalsniffer task enforces.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
    ignore("java.time.Instant")
}

tasks.javadoc {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.test {
    useJUnitPlatform()
    // A directory of larger golden files, written by `go run . -out <dir>` in
    // conformance/, to check the implementation against in place of the
    // committed ones.
    providers.gradleProperty("goldenDir").orNull?.let { systemProperty("value.golden.dir", it) }
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "com.arpabet.value",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("value")
                description.set(project.description)
                url.set("https://github.com/arpabet/value-java")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
