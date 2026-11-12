plugins {
    kotlin("jvm")
    `java-library`
}

val tikoVersion = providers.gradleProperty("tikoVersion").get()

dependencies {
    api("io.github.tomas-samek:tiko-kafka:$tikoVersion")
    implementation("com.h2database:h2:2.3.232")
}
