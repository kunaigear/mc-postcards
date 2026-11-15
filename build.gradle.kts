plugins {
    id("net.fabricmc.fabric-loom") version "1.19.4"
}

val p = { key: String -> property(key).toString() }

version = "${p("mod_version")}+${p("minecraft_version")}"
group = p("maven_group")

base {
    archivesName = "daily-cams"
}

repositories {
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
    maven("https://maven.nucleoid.xyz/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${p("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${p("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${p("fabric_api_version")}")

    // The renderer. Required at runtime, not bundled.
    implementation("maven.modrinth:camera-obscura:${p("camera_obscura_version")}")

    // Camera Obscura's own runtime dependencies (only needed for `runServer` in dev).
    for (module in listOf("polymer-core", "polymer-resource-pack", "polymer-resource-pack-extras",
                          "polymer-virtual-entity", "polymer-autohost")) {
        runtimeOnly("eu.pb4:$module:${p("polymer_version")}")
    }

}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(p("java_version").toInt())
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = p("java_version").toInt()
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "minecraft_version" to p("minecraft_version"),
        "loader_version" to p("loader_version"),
        "camera_obscura_version" to p("camera_obscura_version"),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.jar {
    from("LICENSE")
}

// ---------------------------------------------------------------------------------------------
// Dev-only test mod: `./gradlew runDevtestServer`, then run `dctest` in the console. It drives the
// real commands as fake players with different permission levels. Never part of the release jar.
val devtest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}

// ---------------------------------------------------------------------------------------------
// Optional client helper (its own jar): fills in the player's FOV and freecam view for /dailycam set.
// It doesn't use any of the server mod's code.
val client: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}

tasks.named<ProcessResources>("processClientResources") {
    val props = mapOf("version" to project.version, "minecraft_version" to p("minecraft_version"))
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

val clientJar = tasks.register<Jar>("clientJar") {
    archiveBaseName = "daily-cams-client"
    from(client.output)
    from("LICENSE")
}

tasks.assemble { dependsOn(clientJar) }

loom {
    mods {
        register("dailycams") { sourceSet(sourceSets.main.get()) }
        register("dailycams-devtest") { sourceSet(devtest) }
        register("dailycams-client") { sourceSet(client) }
    }
    runs {
        register("devtestServer") {
            server()
            source(devtest)
            runDir("run")
            // Pass -PsmallServer to emulate a 2-core host with a 3 GB heap and log GC pauses.
            if (project.hasProperty("smallServer")) {
                vmArgs("-XX:ActiveProcessorCount=2", "-Xmx3G", "-Xlog:gc:file=gc.log:uptime,level,tags")
            }
        }
    }
}

// The dev test mod as a standalone jar, for trying it on a production server next to other mods.
tasks.register<Jar>("devtestJar") {
    archiveBaseName = "daily-cams-devtest"
    from(devtest.output)
}
