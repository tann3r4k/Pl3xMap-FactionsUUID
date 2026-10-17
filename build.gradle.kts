plugins {
    java
}

group = "dev.tanner.pl3xmap"
version = "1.0.0"

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        }
        filter {
            includeGroup("io.papermc.paper")
            includeGroup("net.md-5")
        }
    }
    exclusiveContent {
        forRepository {
            maven("https://libraries.minecraft.net/") { name = "minecraft" }
        }
        filter { includeGroup("com.mojang") }
    }
    exclusiveContent {
        forRepository {
            maven("https://api.modrinth.com/maven") { name = "modrinth" }
        }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository {
            maven("https://dependency.download/releases") { name = "kitteh" }
        }
        filter { includeGroup("dev.kitteh") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
    compileOnly("maven.modrinth:pl3xmap:26.2-556") { isTransitive = false }
    compileOnly("dev.kitteh:factions:4.6.0") { isTransitive = false }

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val tokens = mapOf("version" to project.version)
    inputs.properties(tokens)
    filesMatching("paper-plugin.yml") {
        expand(tokens)
    }
}

tasks.jar {
    archiveFileName.set("Pl3xMap-FactionsUUID-${project.version}.jar")
}
