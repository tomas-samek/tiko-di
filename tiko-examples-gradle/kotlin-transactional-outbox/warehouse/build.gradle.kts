plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    application
}

val tikoVersion = providers.gradleProperty("tikoVersion").get()

dependencies {
    implementation(project(":common"))
    implementation("io.github.tomas-samek:tiko-runtime:$tikoVersion")
    implementation("io.github.tomas-samek:tiko-config:$tikoVersion")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
    ksp("io.github.tomas-samek:tiko-processor:$tikoVersion")
    ksp("io.github.tomas-samek:tiko-kafka-processor:$tikoVersion")
}

application {
    mainClass.set("io.tiko.examples.kotlin.outbox.warehouse.MainKt")
}
