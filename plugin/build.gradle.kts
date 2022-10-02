import java.util.Properties
import java.io.StringWriter
import java.io.File

val majorIntelliJVersion = "211"
group = "edu.illinois.cs.cs125"
version = "2022.9.0.$majorIntelliJVersion"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    idea
    id("org.jetbrains.intellij") version "1.9.0"
    id("org.jmailen.kotlinter")
    id("io.gitlab.arturbosch.detekt")
}
intellij {
    pluginName.set("CS 124 IntelliJ Activity Logger")
    version.set("2021.1")
    // sandboxDirectory.set(File(projectDir, "sandbox").absolutePath)
    plugins.set(listOf("java"))
}
tasks.patchPluginXml {
    sinceBuild.set(majorIntelliJVersion)
    untilBuild.set("222.*")
}
dependencies {
    implementation("org.yaml:snakeyaml:1.32")
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0")
}
task("createProperties") {
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(
            projectDir,
            "src/main/resources/edu.illinois.cs.cs125.intellijlogger.version"
        )
            .printWriter().use { printWriter ->
                printWriter.print(
                    StringWriter().also { properties.store(it, null) }.buffer.toString()
                        .lines().drop(1).joinToString(separator = "\n").trim()
                )
            }
    }
}
tasks.processResources {
    dependsOn("createProperties")
}