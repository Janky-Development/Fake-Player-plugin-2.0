import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.diffplug.spotless") version "7.0.3"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

configurations.compileClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

tasks.compileJava {
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

group = "me.bill.fpp"
version = "2.1.5"

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.faststats.dev/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.lucko.me/")
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.65-stable")

    implementation("dev.faststats.metrics:bukkit:0.22.0")

    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.12") {
        exclude("com.google.code.gson", "gson")
        exclude("com.google.guava", "guava")
        exclude("it.unimi.dsi", "fastutil")
    }
    compileOnly("me.lucko:spark-api:0.1-SNAPSHOT")

    // nLogin (nickuc.com's closed-source auth plugin) is a paid plugin with no public Maven
    // artifact, so its API (com.nickuc.login.api.nLoginAPI, used by auth.BotAuthManager's
    // nLogin-specific integration) is deliberately NOT a compile-time dependency here - that would
    // make the whole plugin fail to build for anyone without a private copy of nLogin.jar. See
    // compat.nlogin.NLoginIntegration: it resolves nLogin's API entirely through reflection at
    // runtime instead, so this build has nothing to depend on and nothing to add here.

}

// -- Build ID -----------------------------------------------------------------------------------
// Every build stamps a fresh ID into the jar (fpp-build.properties), read back at runtime by
// util/BuildInfo and shown in the startup banner, /fpp and the GUI credit head - so a jar can always
// be told apart from another with the same version. Format: D<yyMMdd>-<HHmmss UTC>-<4 random hex>.
val buildInfoFile = layout.buildDirectory.file("generated/buildinfo/fpp-build.properties")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "Generates a fresh build ID (runs on every build - never up-to-date)."
    val outFile = buildInfoFile.get().asFile
    outputs.file(outFile)
    outputs.upToDateWhen { false }
    val versionString = project.version.toString()
    doLast {
        outFile.parentFile.mkdirs()
        val stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyMMdd-HHmmss"))
        val rnd = UUID.randomUUID().toString().replace("-", "").take(4)
        outFile.writeText(
            "build-id=D" + stamp + "-" + rnd + "\n" +
                "build-type=dev\n" +
                "build-time=" + Instant.now() + "\n" +
                "version=" + versionString + "\n",
            Charsets.UTF_8,
        )
        logger.lifecycle("generateBuildInfo: " + outFile.readLines().first())
    }
}

tasks.processResources {
    dependsOn(generateBuildInfo)
    from(buildInfoFile)
}

tasks.shadowJar {
    archiveBaseName.set("fake-player-plugin")
    manifest {
        attributes["Main-Class"] = "me.bill.fakePlayerPlugin.Launcher"
    }
}

tasks.runServer {
    minecraftVersion("1.21.11")
    jvmArgs("-Xms1G", "-Xmx4G")
}

runPaper.folia.registerTask {
    minecraftVersion("1.21.11")
    jvmArgs("-Xms1G", "-Xmx4G")
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.56.0")
        importOrder("java", "javax", "org", "com", "me.bill")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
