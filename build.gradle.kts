plugins {
    id("java")
}

// project information
group = "io.canvasmc.sculptor"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.canvasmc.sculptor.Main"
        )
    }
}
