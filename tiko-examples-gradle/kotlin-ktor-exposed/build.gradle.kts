plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.13"
    application
}

val tikoVersion = providers.gradleProperty("tikoVersion").get()
val ktorVersion = "3.6.1"
val exposedVersion = "1.6.1"

repositories {
    // The reactor's artifacts (`mvn install -DskipTests` at the repo root) land here.
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.tomas-samek:tiko-api:$tikoVersion")
    implementation("io.github.tomas-samek:tiko-runtime:$tikoVersion")
    // application.yaml binding (@Configuration); optional in tiko-runtime, so declared here.
    implementation("io.github.tomas-samek:tiko-config:$tikoVersion")
    ksp("io.github.tomas-samek:tiko-processor:$tikoVersion")
    kspTest("io.github.tomas-samek:tiko-processor:$tikoVersion")
    // tiko-processor needs tiko-config on the processor path to compile @Configuration records.
    ksp("io.github.tomas-samek:tiko-config:$tikoVersion")
    kspTest("io.github.tomas-samek:tiko-config:$tikoVersion")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.zaxxer:HikariCP:7.1.0")
    runtimeOnly("com.h2database:h2:2.5.252")

    testImplementation("io.github.tomas-samek:tiko-test:$tikoVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.tiko.examples.ktor.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
