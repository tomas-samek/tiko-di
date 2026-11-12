plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":common"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.testcontainers:kafka:1.20.4")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.9")
}

tasks.test {
    dependsOn(":orders:installDist", ":warehouse:installDist")
    for (service in listOf("orders", "warehouse")) {
        val lib = project(":$service").layout.buildDirectory.dir("install/$service/lib")
        inputs.dir(lib)
        systemProperty("$service.lib", lib.get().asFile.absolutePath)
    }
    systemProperty("api.version", "1.43")
    systemProperty("outbox.requireDocker", providers.gradleProperty("requireDocker").isPresent.toString())
}
