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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.withType<ProcessResources>().configureEach {
    filteringCharset = Charsets.UTF_8.name()
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
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
