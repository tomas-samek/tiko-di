plugins {
    kotlin("jvm")
    `java-library`
}

val tikoVersion = providers.gradleProperty("tikoVersion").get()

dependencies {
    api("io.github.tomas-samek:tiko-kafka:$tikoVersion")
    implementation("com.h2database:h2:2.3.232")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}
