plugins {
    `java-library`
    idea
}

// project information
group = "io.canvasmc.sculptor"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jspecify:jspecify:1.0.0")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.canvasmc.sculptor.Main"
        )
    }
}

tasks.register<JavaExec>("runProject") {
    group = "run"
    description = "Builds and runs a test Sculptor version"

    dependsOn("build")

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.canvasmc.sculptor.Main")

    workingDir = file("run")
    if (!workingDir.exists()) {
        workingDir.mkdirs()
    }
    standardInput = System.`in`
}
