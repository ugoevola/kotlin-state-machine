plugins {
    `java-library`
    kotlin("jvm") version "2.3.10"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
    id("org.jetbrains.dokka") version "2.1.0"
    jacoco
}

group = "io.github.ugoevola"
version = "1.1.2"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}


kotlin {
    jvmToolchain(25)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), rootProject.name, version.toString())

    pom {
        name.set("kotlin state machine")
        description.set("A simple stateless state machine library for kotlin")
        url.set("https://github.com/ugoevola/kotlin-state-machine")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("ugoevola")
                name.set("Ugo Evola")
                email.set("ugoevola@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git:github.com/ugoevola/kotlin-state-machine.git")
            developerConnection.set("scm:git:ssh://github.com/ugoevola/kotlin-state-machine.git")
            url.set("https://github.com/ugoevola/kotlin-state-machine")
        }
    }
}
