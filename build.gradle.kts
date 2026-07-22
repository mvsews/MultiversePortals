plugins {
    java
}

group = "io.multiverseportals"
version = "1.1.14"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.processResources {
    val props = mapOf("version" to version)
    // Without this, a version-only change leaves the task UP-TO-DATE and plugin.yml keeps the old version
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("MultiversePortals")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}
