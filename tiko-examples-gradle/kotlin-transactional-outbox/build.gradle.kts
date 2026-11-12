plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}

subprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
