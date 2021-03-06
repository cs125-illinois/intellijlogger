import java.util.Properties
import java.io.StringWriter
import java.io.File

val majorIntelliJVersion = "192"
group = "edu.illinois.cs.cs125"
version = "2021.4.0.$majorIntelliJVersion"

plugins {
    kotlin("jvm")
    kotlin("kapt")
    idea
    id("org.jetbrains.intellij") version "0.7.2"
    id("org.jmailen.kotlinter")
}
intellij {
    pluginName = "CS 125 IntelliJ Activity Logger"
    version = "2020.1"
    sandboxDirectory = File(projectDir, "sandbox").absolutePath
    setPlugins("java")
}
tasks.patchPluginXml {
    sinceBuild(majorIntelliJVersion)
    untilBuild("211.*")
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")

    implementation(kotlin("stdlib"))
    implementation("org.yaml:snakeyaml:1.28")
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    implementation("com.squareup.moshi:moshi:1.12.0")
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
