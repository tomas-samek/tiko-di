plugins {
    kotlin("jvm") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.13"
    application
}

group = "com.example"

val tikoVersion = providers.gradleProperty("tikoVersion").get()

repositories {
    // Only needed to build against a Tiko snapshot installed with `mvn install`; a release
    // resolves from Maven Central.
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.tomas-samek:tiko-api:$tikoVersion")
    implementation("io.github.tomas-samek:tiko-runtime:$tikoVersion")
    // Tiko's annotation processor runs on KSP; the generated container is Java.
    ksp("io.github.tomas-samek:tiko-processor:$tikoVersion")
    // The processor runs on the test compile too, so @TestComponent overrides apply.
    kspTest("io.github.tomas-samek:tiko-processor:$tikoVersion")

    testImplementation("io.github.tomas-samek:tiko-test:$tikoVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
