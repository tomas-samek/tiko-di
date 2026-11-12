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
    implementation("io.javalin:javalin:7.2.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
    ksp("io.github.tomas-samek:tiko-processor:$tikoVersion")
    ksp("io.github.tomas-samek:tiko-kafka-processor:$tikoVersion")
}

application {
    mainClass.set("io.tiko.examples.kotlin.outbox.orders.MainKt")
}
