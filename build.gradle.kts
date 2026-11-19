plugins {
    `java-library`
    `maven-publish`
    signing
    id("ru.vyarus.animalsniffer") version "2.0.1"
}

group = "com.arpabet"
// Between releases, the next version with -SNAPSHOT. A release takes the
// suffix off and is tagged `v` + the version (.github/workflows/release.yaml).
version = "0.2.0-SNAPSHOT"
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

// The same sources give the same jars, byte for byte, whenever and wherever
// they are built: a release can be checked against its tag.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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
                developers {
                    developer {
                        name.set("Karagatan LLC")
                        organization.set("Karagatan LLC")
                        organizationUrl.set("https://github.com/arpabet")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/arpabet/value-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com/arpabet/value-java.git")
                    url.set("https://github.com/arpabet/value-java")
                }
            }
        }
    }
    repositories {
        // Where a release is laid out before it goes to Maven Central: a Maven
        // repository in the build directory, which `centralBundle` zips.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging"))
        }
    }
}

// A release is signed with the key the release workflow passes in
// (ORG_GRADLE_PROJECT_signingKey, an ASCII-armored private key, and
// ORG_GRADLE_PROJECT_signingPassword). Without a key, as on a developer's
// machine, nothing is signed and `publishToMavenLocal` works as it is.
signing {
    val key = providers.gradleProperty("signingKey").orNull
    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull)
        sign(publishing.publications["maven"])
    }
}

val cleanStaging by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("staging"))
}

tasks.named("publishMavenPublicationToStagingRepository") {
    dependsOn(cleanStaging)
}

// The bundle the Central Portal takes (central.sonatype.org/publish/publish-portal-upload):
// the files of the release in the layout of a Maven repository, each with its
// signature and its checksums. Maven Central writes the maven-metadata.xml
// itself, and a signature needs no checksum.
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the signed release for upload to the Central Portal."
    dependsOn("publishMavenPublicationToStagingRepository")
    from(layout.buildDirectory.dir("staging"))
    exclude("**/maven-metadata.xml*")
    exclude("**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha256", "**/*.asc.sha512")
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    archiveFileName.set("${project.name}-${project.version}-bundle.zip")
    doFirst {
        val staged = layout.buildDirectory.dir("staging").get().asFileTree.files
        check(staged.any { it.name.endsWith(".jar.asc") }) {
            "the release is not signed: pass -PsigningKey and -PsigningPassword"
        }
    }
}
