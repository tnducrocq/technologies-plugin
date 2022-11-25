/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2019-2022 Creative Data.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.saagie.technologies

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonStreamContext
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.command.PullImageResultCallback
import com.github.dockerjava.core.command.PushImageResultCallback
import com.saagie.technologies.model.ContextsMetadata
import com.saagie.technologies.model.DockerInfo
import com.saagie.technologies.model.SimpleMetadataWithContexts
import com.saagie.technologies.model.toListing
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.support.zipTo
import java.io.File
import java.util.concurrent.TimeUnit

class SaagieTechnologiesPackageGradlePlugin : Plugin<Project> {
    companion object {
        @Suppress("MayBeConst")
        @JvmField
        val TIMEOUT_PUSH_PULL_DOCKER: Long = 10
        const val metadataBaseFilename = "metadata"
        const val technologyBaseFilename = "technology"
        const val dockerInfoBaseFilename = "dockerInfo"
        const val contextBaseFilename = "context"
        const val innerContextsDirectory = "innerContexts"
        const val innerContextBaseFilename = "innerContext"
        const val dockerListing = "docker_listing"
        const val outputDirectory = "tmp-zip"
    }

    override fun apply(project: Project) {
        /**
         * PACKAGE
         */
        val constructMetadata = constructMetadata(project)
        val packageAllVersionsForPromote = packageAllVersionsForPromote(project, constructMetadata)
        packageAllVersions(project, packageAllVersionsForPromote)

        /**
         * PROMOTE
         */
        val fixMetadataVersion = fixMetadataVersion(project)
        promote(project, packageAllVersionsForPromote, fixMetadataVersion)
    }

    private fun promote(
        project: Project,
        packageAllVersionsForPromote: Task,
        fixMetadataVersion: Task
    ): Task = project.tasks.create("promote") {
        group = "technologies"
        description = "Promote the PR"

        doFirst {
            logger.info("> PROMOTE ${project.property("version")} DONE")
        }
        packageAllVersionsForPromote.mustRunAfter(fixMetadataVersion)
        dependsOn(fixMetadataVersion, packageAllVersionsForPromote)
    }

    private fun fixMetadataVersion(
        project: Project
    ): Task = project.tasks.create("fixMetadataVersion") {
        doFirst {
            val version = (project.property("version") as String)
            val dockerFormattedVersion = version.replace("+", "_")
            val newVersion = version.split("+").first()
            logger.info("PROMOTING from ($dockerFormattedVersion) to ==> [$newVersion]")
            File("technologies")
                .walk()
                .filter { it.name.endsWith("$metadataBaseFilename.yml") || it.name.endsWith("$metadataBaseFilename.yaml") }
                .forEach {
                    val metadata = getJacksonObjectMapper()
                        .readValue(it.inputStream(), ContextsMetadata::class.java)
                    val tempFile = createTempFile()
                    tempFile.printWriter().use { writer ->
                        it.forEachLine { line ->
                            writer.println(
                                when {
                                    line.startsWith("      version: ") && line.endsWith(dockerFormattedVersion)
                                    -> line.replace("-$dockerFormattedVersion", "-$newVersion")
                                    else -> line
                                }
                            )
                        }
                    }
                    metadata.contexts.forEach { context ->
                        run {
                            fun checkVersionAndPromote(dockerInfo: DockerInfo?) {
                                if (dockerInfo?.version != null && dockerInfo.version.endsWith(dockerFormattedVersion)) {
                                    logger.info(
                                        "\t\t${it.toString()
                                            .replace("technologies/job/", "")
                                            .replace("/metadata.yaml", "")} => ${dockerInfo.version}"
                                    )
                                    promoteDockerImage(dockerInfo)
                                }
                            }
                            checkVersionAndPromote(context.dockerInfo)
                            context.innerContexts?.forEach { innerContext ->
                                innerContext.innerContexts?.forEach { finalContext ->
                                    checkVersionAndPromote(finalContext.dockerInfo)
                                }
                            }
                        }
                    }
                    tempFile.copyTo(it, true)
                    logger.debug("${it.path} UPDATED")
                }
            File("technologies")
                .walk()
                .filter { it.name == "$dockerInfoBaseFilename.yml" || it.name == "$dockerInfoBaseFilename.yaml" }
                .forEach { file ->
                    file.readText().let { line ->
                        file.writeText(line.replace("-$dockerFormattedVersion", "-$newVersion"))
                    }
                }
        }
    }

    private fun promoteDockerImage(dockerInfo: DockerInfo) {
        with(
            DockerClientBuilder
                .getInstance(
                    DefaultDockerClientConfig
                        .createDefaultConfigBuilder()
                        .withRegistryUsername(System.getenv("DOCKER_USERNAME"))
                        .withRegistryPassword(System.getenv("DOCKER_PASSWORD"))
                        .build()
                )
                .build()
        ) {
            pullImageCmd(dockerInfo.generateDocker())
                .exec(PullImageResultCallback())
                .awaitCompletion(TIMEOUT_PUSH_PULL_DOCKER, TimeUnit.MINUTES)
            tagImageCmd(dockerInfo.generateDocker(), dockerInfo.image, dockerInfo.promoteVersion())
                .exec()
            pushImageCmd(dockerInfo.generateDockerPromote())
                .exec(PushImageResultCallback())
                .awaitCompletion(TIMEOUT_PUSH_PULL_DOCKER, TimeUnit.MINUTES)
        }
    }

    private fun constructMetadata(project: Project): Task = project.tasks.create("constructMetadata") {
        group = "technologies"
        description = "Construct metadata files"
        doFirst {
            logger.info("Construct metadata")
            File(project.rootDir.path + "/technologies").walkTopDown()
                .filter { !it.absolutePath.contains("/node_modules/") }.forEach {
                    if (it.isADirectoryContainingFile(technologyBaseFilename)) {
                        val targetMetadata = File("$it/$metadataBaseFilename.yaml")
                        targetMetadata.delete()
                        File("$it/$technologyBaseFilename.yaml").checkYamlExtension().copyTo(targetMetadata)
                        targetMetadata.appendText("\ncontexts:")
                        it.walkTopDown().filter { !it.absolutePath.contains("/node_modules/") }.sorted().forEach { file ->
                            run {
                                val indent = if (file.absolutePath.contains(innerContextsDirectory))
                                    if (file.isADirectoryContainingFile(innerContextBaseFilename)) "        "
                                    else "    "
                                else ""
                                buildContextMetadata(contextBaseFilename, it, file, targetMetadata, indent)
                                if (File("$file/$innerContextsDirectory").exists()) {
                                    targetMetadata.appendText("\n    innerContexts:")
                                }
                                if (file.absolutePath.contains(innerContextsDirectory)) {
                                    if (file.isADirectoryContainingFile(contextBaseFilename)) {
                                        targetMetadata.appendText("\n        innerContexts:")
                                    }
                                    buildContextMetadata(innerContextBaseFilename, it, file, targetMetadata, indent)
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun buildContextMetadata(contextName: String, root: File, file: File, targetMetadata: File, ident: String) {
        if (file.isADirectoryContainingFile(contextName)) {
            File("$file/$contextName.yaml")
                .checkYamlExtension()
                .readLines()
                .forEachIndexed { index, inputLine ->
                    val line = inputLine
                        .replace("script: ./", "script: ./${file.relativeTo(root)}/")
                        .replace("script: ../", "script: ./${file.relativeTo(root).resolve("..").normalize()}/")
                    when (index) {
                        0 -> targetMetadata.appendText("\n$ident  - $line")
                        else -> targetMetadata.appendText("\n$ident    $line")
                    }
                }
            if (file.isADirectoryContainingFile(dockerInfoBaseFilename)) {
                val dockerInfo = readDockerInfo(file)
                targetMetadata.appendText("\n$ident    dockerInfo:")
                targetMetadata.appendText("\n$ident      image: \"${dockerInfo.image}\"")
                targetMetadata.appendText("\n$ident      baseTag: \"${dockerInfo.baseTag}\"")
                targetMetadata.appendText("\n$ident      version: \"${dockerInfo.version}\"")
            }
        }
    }

    private fun packageAllVersionsForPromote(
        project: Project,
        constructMetadata: Task
    ): Task = project.tasks.create("packageAllVersionsForPromote") {
        dependsOn(constructMetadata)
        doFirst {
            with("${project.rootDir.path}/$outputDirectory/") {
                val rootZipDir = File(this)
                rootZipDir.deleteRecursively()
                rootZipDir.mkdir()
                var hasVersion = false
                File(project.rootDir.path + "/technologies").walkTopDown()
                    .filter { !it.absolutePath.contains("/node_modules/") }.forEach {
                        when {
                            it.isADirectoryContainingFile(metadataBaseFilename) -> {
                                logger.info("VERSION : ${project.relativePath(it.toPath())}")
                                hasVersion = true
                                File("${rootZipDir.absolutePath}/${project.relativePath(it.toPath())}").mkdir()
                                val metadataFile =
                                    File("${project.relativePath(it.toPath())}/$metadataBaseFilename.yaml")
                                        .checkYamlExtension()
                                metadataFile.copyTo(File("$this/${project.relativePath(it.toPath())}/$metadataBaseFilename.yaml"))
                                val metadata = yamlMapper().readTree(metadataFile)
                                (
                                    // ext techno icons
                                    listOf(metadata.path("iconPath").asText()) +
                                        // ext job contexts
                                        metadata.path("contexts").flatMap {
                                            it.path("parameters").map {
                                                it.path("dynamicValues").path("script").asText()
                                            } +
                                                it.path("actions").map {
                                                    it.path("script").asText()
                                                }
                                        } +
                                        // connection types
                                        metadata.path("parameters").map {
                                            it.path("dynamicValues").path("script").asText()
                                        } +
                                        metadata.path("actions").map {
                                            it.path("script").asText()
                                        }
                                    ).filter { it.isNotBlank() }.toSet().forEach { script ->
                                    File("${project.relativePath(it.toPath())}/$script")
                                        .copyTo(File("$this/${project.relativePath(it.toPath())}/$script"), overwrite = true)
                                }
                            }
                        }
                    }
                if (hasVersion) {
                    zipTo(File("$outputDirectory/technologies.zip"), File(rootZipDir, "technologies"))
                    generateListing(this)
                }
            }
        }
    }


    private fun generateListing(path: String) {
        val yamlObjectMapper = yamlMapper()
        val jsonObjectMapper = jsonMapper()
        val dockerImages: MutableList<String> = mutableListOf()
        val listing = File(path)
            .walk()
            .filter {
                it.name == "$metadataBaseFilename.yml" ||
                    it.name == "$metadataBaseFilename.yaml"
            }
            .map {
                yamlObjectMapper.readValue(it, SimpleMetadataWithContexts::class.java)
            }
            .map { it.toListing() }
            .map { techno ->
                when {
                    techno.docker != null -> {
                        dockerImages.add(techno.docker)
                    }
                    else -> {
                        techno.contexts?.forEach { context ->
                            if (context.docker != null) {
                                dockerImages.add(context.docker)
                            }
                            context.innerContexts?.forEach { innerContext ->
                                innerContext.innerContexts?.forEach { finalContext ->
                                    if (finalContext.docker != null) {
                                        dockerImages.add(finalContext.docker)
                                    }
                                }
                            }
                        }
                    }
                }
                techno
            }
        jsonObjectMapper.writeValue(File(path + "/$dockerListing.json"), listing)

        with(File(path + "/$dockerListing.txt")) {
            dockerImages.forEach {
                this.appendText(it + "\n")
            }
        }
    }

    private fun packageAllVersions(
        project: Project,
        packageAllVersionsForPromote: Task
    ): Task = project.tasks.create("packageAllVersions") {
        group = "technologies"
        description = "Package all versions"
        dependsOn(packageAllVersionsForPromote)
    }
}

private fun JsonStreamContext.getPath(base: String? = null): String {
    val element = toString()
    if (inRoot()) {
        return element + (base ?: "")
    }
    val ancestor = base ?: ""
    return parent.getPath("$element$ancestor")
}

private class AmbiguousStringJsonDeserializer : JsonDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String {
        if (p.currentToken == JsonToken.VALUE_NUMBER_FLOAT) {
            throw JsonParseException(p, "this float value is ambiguous : " + p.parsingContext.getPath())
        }
        return p.valueAsString
    }
}

fun yamlMapper(): ObjectMapper =
    ObjectMapper(
        YAMLFactory()
            .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false)
            .configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, false)
    )
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(
            KotlinModule()
                .addDeserializer(String::class.java, AmbiguousStringJsonDeserializer())
        )
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

fun jsonMapper(): ObjectMapper =
    ObjectMapper(JsonFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.INDENT_OUTPUT, true)
        .registerModule(
            KotlinModule()
                .addDeserializer(String::class.java, AmbiguousStringJsonDeserializer())
        )
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
