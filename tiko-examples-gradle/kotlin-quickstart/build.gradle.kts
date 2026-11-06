plugins {
    kotlin("jvm") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.13"
    application
}

val tikoVersion: String by project

repositories {
    // The reactor's artifacts (`mvn install -DskipTests` at the repo root) land here.
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.tomas-samek:tiko-api:$tikoVersion")
    implementation("io.github.tomas-samek:tiko-runtime:$tikoVersion")
    ksp("io.github.tomas-samek:tiko-processor:$tikoVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.tiko.examples.kotlin.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
