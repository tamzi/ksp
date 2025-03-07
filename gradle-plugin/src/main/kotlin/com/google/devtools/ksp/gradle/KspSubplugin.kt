/*
 * Copyright 2020 Google LLC
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.ksp.gradle

import com.google.devtools.ksp.gradle.model.builder.KspModelBuilder
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion.Companion.LATEST_STABLE
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.CLASS_STRUCTURE_ARTIFACT_TYPE
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.ClasspathSnapshot
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.KaptClasspathChanges
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.StructureTransformAction
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.StructureTransformLegacyAction
import org.jetbrains.kotlin.gradle.plugin.CompilerPluginConfig
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.InternalSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilationWithResources
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinCommonCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmAndroidCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinWithJavaCompilation
import org.jetbrains.kotlin.gradle.tasks.*
import org.jetbrains.kotlin.incremental.ChangedFiles
import org.jetbrains.kotlin.incremental.isJavaFile
import org.jetbrains.kotlin.incremental.isKotlinFile
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import java.io.File
import java.util.concurrent.Callable
import javax.inject.Inject

class KspGradleSubplugin @Inject internal constructor(private val registry: ToolingModelBuilderRegistry) :
    KotlinCompilerPluginSupportPlugin {
    companion object {
        const val KSP_PLUGIN_ID = "com.google.devtools.ksp.symbol-processing"
        const val KSP_API_ID = "symbol-processing-api"
        const val KSP_COMPILER_PLUGIN_ID = "symbol-processing"
        const val KSP_COMPILER_PLUGIN_ID_NON_EMBEDDABLE = "symbol-processing-cmdline"
        const val KSP_GROUP_ID = "com.google.devtools.ksp"
        const val KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME = "kspPluginClasspath"
        const val KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME_NON_EMBEDDABLE = "kspPluginClasspathNonEmbeddable"
        val LANGUAGE_VERSION = KotlinVersion.fromVersion(LATEST_STABLE.toString())

        @JvmStatic
        fun getKspOutputDir(project: Project, sourceSetName: String, target: String) =
            File(project.project.buildDir, "generated/ksp/$target/$sourceSetName")

        @JvmStatic
        fun getKspClassOutputDir(project: Project, sourceSetName: String, target: String) =
            File(getKspOutputDir(project, sourceSetName, target), "classes")

        @JvmStatic
        fun getKspJavaOutputDir(project: Project, sourceSetName: String, target: String) =
            File(getKspOutputDir(project, sourceSetName, target), "java")

        @JvmStatic
        fun getKspKotlinOutputDir(project: Project, sourceSetName: String, target: String) =
            File(getKspOutputDir(project, sourceSetName, target), "kotlin")

        @JvmStatic
        fun getKspResourceOutputDir(project: Project, sourceSetName: String, target: String) =
            File(getKspOutputDir(project, sourceSetName, target), "resources")

        @JvmStatic
        fun getKspCachesDir(project: Project, sourceSetName: String, target: String) =
            File(project.project.buildDir, "kspCaches/$target/$sourceSetName")

        @JvmStatic
        private fun getSubpluginOptions(
            project: Project,
            kspExtension: KspExtension,
            sourceSetName: String,
            target: String,
            isIncremental: Boolean,
            allWarningsAsErrors: Boolean,
            commandLineArgumentProviders: ListProperty<CommandLineArgumentProvider>,
            commonSources: List<File>,
        ): List<SubpluginOption> {
            val options = mutableListOf<SubpluginOption>()
            options +=
                InternalSubpluginOption("classOutputDir", getKspClassOutputDir(project, sourceSetName, target).path)
            options +=
                InternalSubpluginOption("javaOutputDir", getKspJavaOutputDir(project, sourceSetName, target).path)
            options +=
                InternalSubpluginOption("kotlinOutputDir", getKspKotlinOutputDir(project, sourceSetName, target).path)
            options += InternalSubpluginOption(
                "resourceOutputDir",
                getKspResourceOutputDir(project, sourceSetName, target).path
            )
            options += InternalSubpluginOption("cachesDir", getKspCachesDir(project, sourceSetName, target).path)
            options += InternalSubpluginOption("kspOutputDir", getKspOutputDir(project, sourceSetName, target).path)
            options += SubpluginOption("incremental", isIncremental.toString())
            options += SubpluginOption(
                "incrementalLog",
                project.findProperty("ksp.incremental.log")?.toString() ?: "false"
            )
            options += InternalSubpluginOption("projectBaseDir", project.project.projectDir.canonicalPath)
            options += SubpluginOption("allWarningsAsErrors", allWarningsAsErrors.toString())
            // Turn this on by default to work KT-30172 around. It is off by default in the compiler plugin.
            options += SubpluginOption(
                "returnOkOnError",
                project.findProperty("ksp.return.ok.on.error")?.toString() ?: "true"
            )
            commonSources.ifNotEmpty {
                options += FilesSubpluginOption("commonSources", this)
            }

            kspExtension.apOptions.forEach {
                options += SubpluginOption("apoption", "${it.key}=${it.value}")
            }
            options += SubpluginOption(
                "excludedProcessors",
                kspExtension.excludedProcessors.joinToString(":")
            )
            options += SubpluginOption(
                "mapAnnotationArgumentsInJava",
                project.findProperty("ksp.map.annotation.arguments.in.java")?.toString() ?: "false"
            )
            commandLineArgumentProviders.get().forEach {
                it.asArguments().forEach { argument ->
                    if (!argument.matches(Regex("\\S+=\\S+"))) {
                        throw IllegalArgumentException("KSP apoption does not match \\S+=\\S+: $argument")
                    }
                    options += SubpluginOption("apoption", argument)
                }
            }
            return options
        }
    }

    private lateinit var kspConfigurations: KspConfigurations

    override fun apply(target: Project) {
        target.extensions.create("ksp", KspExtension::class.java)
        kspConfigurations = KspConfigurations(target)
        registry.register(KspModelBuilder())
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.target.project
        val kspVersion = ApiVersion.parse(KSP_KOTLIN_BASE_VERSION)!!
        val kotlinVersion = ApiVersion.parse(project.getKotlinPluginVersion())!!

        // Check version and show warning by default.
        val noVersionCheck = project.findProperty("ksp.version.check")?.toString()?.toBoolean() == false
        if (!noVersionCheck) {
            if (kspVersion < kotlinVersion) {
                project.logger.warn(
                    "ksp-$KSP_VERSION is too old for kotlin-$kotlinVersion. " +
                        "Please upgrade ksp or downgrade kotlin-gradle-plugin to $KSP_KOTLIN_BASE_VERSION."
                )
            }
            if (kspVersion > kotlinVersion) {
                project.logger.warn(
                    "ksp-$KSP_VERSION is too new for kotlin-$kotlinVersion. " +
                        "Please upgrade kotlin-gradle-plugin to $KSP_KOTLIN_BASE_VERSION."
                )
            }
        }

        return true
    }

    // TODO: to be future proof, protect with `synchronized`
    // Map from default input source set to output source set
    private val sourceSetMap: MutableMap<KotlinSourceSet, KotlinSourceSet> = mutableMapOf()

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val kotlinCompileProvider: TaskProvider<AbstractKotlinCompileTool<*>> =
            project.locateTask(kotlinCompilation.compileKotlinTaskName) ?: return project.provider { emptyList() }
        val kspExtension = project.extensions.getByType(KspExtension::class.java)
        val kspConfigurations = kspConfigurations.find(kotlinCompilation)
        val nonEmptyKspConfigurations = kspConfigurations.filter { it.allDependencies.isNotEmpty() }
        if (nonEmptyKspConfigurations.isEmpty()) {
            return project.provider { emptyList() }
        }
        if (kotlinCompileProvider.name == "compileKotlinMetadata") {
            return project.provider { emptyList() }
        }

        val target = kotlinCompilation.target.name
        val sourceSetName = kotlinCompilation.defaultSourceSet.name
        val classOutputDir = getKspClassOutputDir(project, sourceSetName, target)
        val javaOutputDir = getKspJavaOutputDir(project, sourceSetName, target)
        val kotlinOutputDir = getKspKotlinOutputDir(project, sourceSetName, target)
        val resourceOutputDir = getKspResourceOutputDir(project, sourceSetName, target)
        val kspOutputDir = getKspOutputDir(project, sourceSetName, target)

        val kspClasspathCfg = project.configurations.maybeCreate(
            KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME
        ).markResolvable()
        project.dependencies.add(
            KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME,
            "$KSP_GROUP_ID:$KSP_API_ID:$KSP_VERSION"
        )
        project.dependencies.add(
            KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME,
            "$KSP_GROUP_ID:$KSP_COMPILER_PLUGIN_ID:$KSP_VERSION"
        )

        val kspClasspathCfgNonEmbeddable = project.configurations.maybeCreate(
            KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME_NON_EMBEDDABLE
        ).markResolvable()
        project.dependencies.add(
            KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME_NON_EMBEDDABLE,
            "$KSP_GROUP_ID:$KSP_API_ID:$KSP_VERSION"
        )
        project.dependencies.add(
            KSP_PLUGIN_CLASSPATH_CONFIGURATION_NAME_NON_EMBEDDABLE,
            "$KSP_GROUP_ID:$KSP_COMPILER_PLUGIN_ID_NON_EMBEDDABLE:$KSP_VERSION"
        )

        findJavaTaskForKotlinCompilation(kotlinCompilation)?.configure { javaCompile ->
            val generatedJavaSources = javaCompile.project.fileTree(javaOutputDir)
            generatedJavaSources.include("**/*.java")
            javaCompile.source(generatedJavaSources)
            javaCompile.classpath += project.files(classOutputDir)
        }

        val processingModel = project.findProperty("ksp.experimental.processing.model")?.toString() ?: "traditional"

        assert(kotlinCompileProvider.name.startsWith("compile"))
        val kspTaskName = kotlinCompileProvider.name.replaceFirst("compile", "ksp")

        val kspGeneratedSourceSet =
            project.kotlinExtension.sourceSets.create("generatedBy" + kspTaskName.capitalizeAsciiOnly())
        sourceSetMap.put(kotlinCompilation.defaultSourceSet, kspGeneratedSourceSet)

        val processorClasspath = project.configurations.maybeCreate("${kspTaskName}ProcessorClasspath")
            .extendsFrom(*nonEmptyKspConfigurations.toTypedArray()).markResolvable()
        fun configureAsKspTask(kspTask: KspTask, isIncremental: Boolean) {
            // depends on the processor; if the processor changes, it needs to be reprocessed.
            kspTask.dependsOn(processorClasspath.buildDependencies)
            kspTask.commandLineArgumentProviders.addAll(kspExtension.commandLineArgumentProviders)

            val commonSources: List<File> = when (processingModel) {
                "hierarchical" -> {
                    fun unclaimedDeps(roots: Set<KotlinSourceSet>): Set<KotlinSourceSet> {
                        val unclaimedParents =
                            roots.flatMap { it.dependsOn }.filterNot { it in sourceSetMap }.toSet()
                        return if (unclaimedParents.isEmpty()) {
                            unclaimedParents
                        } else {
                            unclaimedParents + unclaimedDeps(unclaimedParents)
                        }
                    }
                    // Source sets that are not claimed by other compilations.
                    // I.e., those that should be processed by this compilation.
                    val unclaimed =
                        kotlinCompilation.kotlinSourceSets + unclaimedDeps(kotlinCompilation.kotlinSourceSets)
                    val commonSourceSets = kotlinCompilation.allKotlinSourceSets - unclaimed
                    commonSourceSets.flatMap { it.kotlin.files }
                }
                else -> emptyList()
            }

            kspTask.options.addAll(
                kspTask.project.provider {
                    getSubpluginOptions(
                        project,
                        kspExtension,
                        sourceSetName,
                        target,
                        isIncremental,
                        kspExtension.allWarningsAsErrors,
                        kspTask.commandLineArgumentProviders,
                        commonSources,
                    )
                }
            )
            kspTask.inputs.property("apOptions", kspExtension.arguments)
            kspTask.inputs.files(processorClasspath).withNormalizer(ClasspathNormalizer::class.java)
        }

        fun configureAsAbstractKotlinCompileTool(kspTask: AbstractKotlinCompileTool<*>) {
            kspTask.destinationDirectory.set(kspOutputDir)
            kspTask.outputs.dirs(
                kotlinOutputDir,
                javaOutputDir,
                classOutputDir,
                resourceOutputDir
            )

            val kotlinCompileTask = kotlinCompileProvider.get()
            if (kspExtension.allowSourcesFromOtherPlugins) {
                fun FileCollection.nonSelfDeps(): List<Task> =
                    buildDependencies.getDependencies(null).filterNot {
                        it.name == kspTaskName
                    }

                fun setSource(source: FileCollection) {
                    // kspTask.setSource(source) would create circular dependency.
                    // Therefore we need to manually extract input deps, filter them, and tell kspTask.
                    kspTask.setSource(project.provider { source.files })
                    kspTask.dependsOn(project.provider { source.nonSelfDeps() })
                }

                setSource(kotlinCompileTask.sources - kspGeneratedSourceSet.kotlin)
                if (kotlinCompileTask is KotlinCompile) {
                    setSource(kotlinCompileTask.javaSources - kspGeneratedSourceSet.kotlin)
                }
            } else {
                kotlinCompilation.allKotlinSourceSets.filterNot { it == kspGeneratedSourceSet }.forEach { sourceSet ->
                    kspTask.setSource(sourceSet.kotlin)
                }
                if (kotlinCompilation is KotlinCommonCompilation) {
                    kspTask.setSource(kotlinCompilation.defaultSourceSet.kotlin)
                }
                val generated = when (processingModel) {
                    "hierarchical" -> {
                        // boundary parent source sets that are going to be compiled by other compilations
                        fun claimedParents(root: KotlinSourceSet): Set<KotlinSourceSet> {
                            val (claimed, unclaimed) = root.dependsOn.partition { it in sourceSetMap }
                            return claimed.toSet() + unclaimed.flatMap { claimedParents(it) }
                        }
                        kotlinCompilation.kotlinSourceSets.flatMap { claimedParents(it) }.map { sourceSetMap[it]!! }
                    }
                    else -> emptyList()
                }
                generated.forEach {
                    kspTask.setSource(it.kotlin)
                }
            }
            kspTask.exclude { kspOutputDir.isParentOf(it.file) }

            kspTask.libraries.setFrom(
                kotlinCompileTask.project.files(
                    Callable {
                        kotlinCompileTask.libraries.filter {
                            !kspOutputDir.isParentOf(it)
                        }
                    }
                )
            )
            // kotlinc's incremental compilation isn't compatible with symbol processing in a few ways:
            // * It doesn't consider private / internal changes when computing dirty sets.
            // * It compiles iteratively; Sources can be compiled in different rounds.
            (kspTask as? AbstractKotlinCompile<*>)?.incremental = false
        }

        fun maybeBlockOtherPlugins(kspTask: BaseKotlinCompile) {
            if (kspExtension.blockOtherCompilerPlugins) {
                kspTask.pluginClasspath.setFrom(kspClasspathCfg)
                kspTask.pluginOptions.set(emptyList())
            }
        }

        fun configurePluginOptions(kspTask: BaseKotlinCompile) {
            kspTask.pluginOptions.add(
                project.provider {
                    CompilerPluginConfig().apply {
                        (kspTask as KspTask).options.get().forEach {
                            addPluginArgument(KSP_PLUGIN_ID, it)
                        }
                    }
                }
            )
        }

        fun configureLanguageVersion(kspTask: KotlinCompilationTask<*>) {
            kspTask.compilerOptions.useK2.value(false)
            kspTask.compilerOptions.languageVersion.orNull?.let { version ->
                if (version >= KotlinVersion.KOTLIN_2_0) {
                    kspTask.compilerOptions.languageVersion.value(LANGUAGE_VERSION)
                }
            }
        }

        val isIncremental = project.findProperty("ksp.incremental")?.toString()?.toBoolean() ?: true

        // Create and configure KSP tasks.
        val kspTaskProvider = when (kotlinCompilation.platformType) {
            KotlinPlatformType.jvm, KotlinPlatformType.androidJvm -> {
                KotlinFactories.registerKotlinJvmCompileTask(project, kspTaskName, kotlinCompilation).also {
                    it.configure { kspTask ->
                        val kotlinCompileTask = kotlinCompileProvider.get() as KotlinCompile
                        maybeBlockOtherPlugins(kspTask as BaseKotlinCompile)
                        configureAsKspTask(kspTask, isIncremental)
                        configureAsAbstractKotlinCompileTool(kspTask as AbstractKotlinCompileTool<*>)
                        configurePluginOptions(kspTask)
                        kspTask.compilerOptions.noJdk.value(kotlinCompileTask.compilerOptions.noJdk)
                        kspTask.compilerOptions.verbose.convention(kotlinCompilation.compilerOptions.options.verbose)
                        configureLanguageVersion(kspTask)
                        if (kspTask.classpathSnapshotProperties.useClasspathSnapshot.get() == false) {
                            kspTask.compilerOptions.moduleName.convention(
                                kotlinCompileTask.moduleName.map { "$it-ksp" }
                            )
                        } else {
                            kspTask.compilerOptions.moduleName.convention(kotlinCompileTask.moduleName)
                        }

                        kspTask.moduleName.value(kotlinCompileTask.moduleName.get())
                        kspTask.destination.value(kspOutputDir)

                        val isIntermoduleIncremental =
                            (project.findProperty("ksp.incremental.intermodule")?.toString()?.toBoolean() ?: true) &&
                                isIncremental
                        val classStructureFiles = getClassStructureFiles(project, kspTask.libraries)
                        kspTask.incrementalChangesTransformers.add(
                            createIncrementalChangesTransformer(
                                isIncremental,
                                isIntermoduleIncremental,
                                getKspCachesDir(project, sourceSetName, target),
                                project.provider { classStructureFiles },
                                project.provider { kspTask.libraries },
                                project.provider { processorClasspath }
                            )
                        )
                    }
                    // Don't support binary generation for non-JVM platforms yet.
                    // FIXME: figure out how to add user generated libraries.
                    kotlinCompilation.output.classesDirs.from(classOutputDir)
                }
            }
            KotlinPlatformType.js, KotlinPlatformType.wasm -> {
                KotlinFactories.registerKotlinJSCompileTask(project, kspTaskName, kotlinCompilation).also {
                    it.configure { kspTask ->
                        val kotlinCompileTask = kotlinCompileProvider.get() as Kotlin2JsCompile
                        maybeBlockOtherPlugins(kspTask as BaseKotlinCompile)
                        configureAsKspTask(kspTask, isIncremental)
                        configureAsAbstractKotlinCompileTool(kspTask as AbstractKotlinCompileTool<*>)
                        configurePluginOptions(kspTask)
                        kspTask.compilerOptions.verbose.convention(kotlinCompilation.compilerOptions.options.verbose)
                        kspTask.compilerOptions.freeCompilerArgs
                            .value(kotlinCompileTask.compilerOptions.freeCompilerArgs)
                        configureLanguageVersion(kspTask)
                        kspTask.compilerOptions.moduleName.convention(kotlinCompileTask.moduleName)

                        kspTask.incrementalChangesTransformers.add(
                            createIncrementalChangesTransformer(
                                isIncremental,
                                false,
                                getKspCachesDir(project, sourceSetName, target),
                                project.provider { project.files() },
                                project.provider { project.files() },
                                project.provider { processorClasspath }
                            )
                        )
                    }
                }
            }
            KotlinPlatformType.common -> {
                KotlinFactories.registerKotlinMetadataCompileTask(project, kspTaskName, kotlinCompilation).also {
                    it.configure { kspTask ->
                        val kotlinCompileTask = kotlinCompileProvider.get() as KotlinCompileCommon
                        maybeBlockOtherPlugins(kspTask as BaseKotlinCompile)
                        configureAsKspTask(kspTask, isIncremental)
                        configureAsAbstractKotlinCompileTool(kspTask as AbstractKotlinCompileTool<*>)
                        configurePluginOptions(kspTask)
                        configureLanguageVersion(kspTask)

                        kspTask.incrementalChangesTransformers.add(
                            createIncrementalChangesTransformer(
                                isIncremental,
                                false,
                                getKspCachesDir(project, sourceSetName, target),
                                project.provider { project.files() },
                                project.provider { project.files() },
                                project.provider { processorClasspath }
                            )
                        )
                    }
                }
            }
            KotlinPlatformType.native -> {
                KotlinFactories.registerKotlinNativeCompileTask(project, kspTaskName, kotlinCompilation).also {
                    it.configure { kspTask ->
                        val kotlinCompileTask = kotlinCompileProvider.get() as KotlinNativeCompile
                        configureAsKspTask(kspTask, false)
                        configureAsAbstractKotlinCompileTool(kspTask)

                        val useEmbeddable = project.findProperty("kotlin.native.useEmbeddableCompilerJar")
                            ?.toString()?.toBoolean() ?: true
                        val classpathCfg = if (useEmbeddable) {
                            kspClasspathCfg
                        } else {
                            kspClasspathCfgNonEmbeddable
                        }
                        // KotlinNativeCompile computes -Xplugin=... from compilerPluginClasspath.
                        if (kspExtension.blockOtherCompilerPlugins) {
                            kspTask.compilerPluginClasspath = classpathCfg
                        } else {
                            kspTask.compilerPluginClasspath =
                                classpathCfg + kotlinCompileTask.compilerPluginClasspath!!
                            kspTask.compilerPluginOptions.addPluginArgument(kotlinCompileTask.compilerPluginOptions)
                        }
                        kspTask.compilerOptions.moduleName.convention(kotlinCompileTask.compilerOptions.moduleName)
                        kspTask.commonSources.from(kotlinCompileTask.commonSources)
                        kspTask.options.add(FilesSubpluginOption("apclasspath", processorClasspath.files.toList()))
                        val kspOptions = kspTask.options.get().flatMap { listOf("-P", it.toArg()) }
                        kspTask.compilerOptions.verbose.convention(kotlinCompilation.compilerOptions.options.verbose)
                        kspTask.compilerOptions.freeCompilerArgs.value(
                            kspOptions + kotlinCompileTask.compilerOptions.freeCompilerArgs.get()
                        )
                        configureLanguageVersion(kspTask)
                        // Cannot use lambda; See below for details.
                        // https://docs.gradle.org/7.2/userguide/validation_problems.html#implementation_unknown
                        kspTask.doFirst(object : Action<Task> {
                            override fun execute(t: Task) {
                                kspOutputDir.deleteRecursively()
                            }
                        })
                    }
                }
            }
            // No else; The cases should be exhaustive
        }
        kspGeneratedSourceSet.kotlin.srcDir(project.files(kotlinOutputDir, javaOutputDir).builtBy(kspTaskProvider))
        kotlinCompilation.source(kspGeneratedSourceSet)
        kotlinCompileProvider.configure { kotlinCompile ->
            when (kotlinCompile) {
                is AbstractKotlinCompile<*> -> kotlinCompile.libraries.from(project.files(classOutputDir))
                // is KotlinNativeCompile -> TODO: support binary generation?
            }
        }

        val processResourcesTaskName =
            (kotlinCompilation as? KotlinCompilationWithResources)?.processResourcesTaskName ?: "processResources"
        project.locateTask<ProcessResources>(processResourcesTaskName)?.let { provider ->
            provider.configure { resourcesTask ->
                resourcesTask.from(project.files(resourceOutputDir).builtBy(kspTaskProvider))
            }
        }
        if (kotlinCompilation is KotlinJvmAndroidCompilation) {
            AndroidPluginIntegration.registerGeneratedSources(
                project = project,
                kotlinCompilation = kotlinCompilation,
                kspTaskProvider = kspTaskProvider as TaskProvider<KspTaskJvm>,
                javaOutputDir = javaOutputDir,
                kotlinOutputDir = kotlinOutputDir,
                classOutputDir = classOutputDir,
                resourcesOutputDir = project.files(resourceOutputDir)
            )
        }

        return project.provider { emptyList() }
    }

    override fun getCompilerPluginId() = KSP_PLUGIN_ID
    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            groupId = "com.google.devtools.ksp",
            artifactId = KSP_COMPILER_PLUGIN_ID,
            version = KSP_VERSION
        )

    override fun getPluginArtifactForNative(): SubpluginArtifact? =
        SubpluginArtifact(
            groupId = "com.google.devtools.ksp",
            artifactId = KSP_COMPILER_PLUGIN_ID_NON_EMBEDDABLE,
            version = KSP_VERSION
        )
}

// Copied from kotlin-gradle-plugin, because they are internal.
internal inline fun <reified T : Task> Project.locateTask(name: String): TaskProvider<T>? =
    try {
        tasks.withType(T::class.java).named(name)
    } catch (e: UnknownTaskException) {
        null
    }

// Copied from kotlin-gradle-plugin, because they are internal.
internal fun findJavaTaskForKotlinCompilation(compilation: KotlinCompilation<*>): TaskProvider<out JavaCompile>? =
    when (compilation) {
        is KotlinJvmAndroidCompilation -> compilation.compileJavaTaskProvider
        is KotlinWithJavaCompilation<*, *> -> compilation.compileJavaTaskProvider
        is KotlinJvmCompilation -> compilation.compileJavaTaskProvider // may be null for Kotlin-only JVM target in MPP
        else -> null
    }

internal val artifactType = Attribute.of("artifactType", String::class.java)

internal fun maybeRegisterTransform(project: Project) {
    // Use the same flag with KAPT, so as to share the same transformation in case KAPT and KSP are both enabled.
    if (!project.extensions.extraProperties.has("KaptStructureTransformAdded")) {
        val transformActionClass =
            if (GradleVersion.current() >= GradleVersion.version("5.4"))
                StructureTransformAction::class.java
            else

                StructureTransformLegacyAction::class.java
        project.dependencies.registerTransform(transformActionClass) { transformSpec ->
            transformSpec.from.attribute(artifactType, "jar")
            transformSpec.to.attribute(artifactType, CLASS_STRUCTURE_ARTIFACT_TYPE)
        }

        project.dependencies.registerTransform(transformActionClass) { transformSpec ->
            transformSpec.from.attribute(artifactType, "directory")
            transformSpec.to.attribute(artifactType, CLASS_STRUCTURE_ARTIFACT_TYPE)
        }

        project.extensions.extraProperties["KaptStructureTransformAdded"] = true
    }
}

internal fun getClassStructureFiles(
    project: Project,
    libraries: ConfigurableFileCollection,
): FileCollection {
    maybeRegisterTransform(project)

    val classStructureIfIncremental = project.configurations.detachedConfiguration(
        project.dependencies.create(project.files(project.provider { libraries }))
    ).markResolvable()

    return classStructureIfIncremental.incoming.artifactView { viewConfig ->
        viewConfig.attributes.attribute(artifactType, CLASS_STRUCTURE_ARTIFACT_TYPE)
    }.files
}

// Reuse Kapt's infrastructure to compute affected names in classpath.
// This is adapted from KaptTask.findClasspathChanges.
internal fun findClasspathChanges(
    changes: ChangedFiles,
    cacheDir: File,
    allDataFiles: Set<File>,
    libs: List<File>,
    processorCP: List<File>,
): KaptClasspathChanges {
    cacheDir.mkdirs()

    val changedFiles = (changes as? ChangedFiles.Known)?.let { it.modified + it.removed }?.toSet() ?: allDataFiles

    val loadedPrevious = ClasspathSnapshot.ClasspathSnapshotFactory.loadFrom(cacheDir)
    val previousAndCurrentDataFiles = lazy { loadedPrevious.getAllDataFiles() + allDataFiles }
    val allChangesRecognized = changedFiles.all {
        val extension = it.extension
        if (extension.isEmpty() || extension == "kt" || extension == "java" || extension == "jar" ||
            extension == "class"
        ) {
            return@all true
        }
        // if not a directory, Java source file, jar, or class, it has to be a structure file, in order to understand changes
        it in previousAndCurrentDataFiles.value
    }
    val previousSnapshot = if (allChangesRecognized) {
        loadedPrevious
    } else {
        ClasspathSnapshot.ClasspathSnapshotFactory.getEmptySnapshot()
    }

    val currentSnapshot =
        ClasspathSnapshot.ClasspathSnapshotFactory.createCurrent(
            cacheDir,
            libs,
            processorCP,
            allDataFiles
        )

    val classpathChanges = currentSnapshot.diff(previousSnapshot, changedFiles)
    if (classpathChanges is KaptClasspathChanges.Unknown || changes is ChangedFiles.Unknown) {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
    currentSnapshot.writeToCache()

    return classpathChanges
}

internal fun ChangedFiles.hasNonSourceChange(): Boolean {
    if (this !is ChangedFiles.Known)
        return true

    return !(this.modified + this.removed).all {
        it.isKotlinFile(listOf("kt")) || it.isJavaFile()
    }
}

fun KaptClasspathChanges.toSubpluginOptions(): List<SubpluginOption> {
    return if (this is KaptClasspathChanges.Known) {
        this.names.map { it.replace('/', '.').replace('$', '.') }.ifNotEmpty {
            listOf(SubpluginOption("changedClasses", joinToString(":")))
        } ?: emptyList()
    } else {
        emptyList()
    }
}

fun ChangedFiles.toSubpluginOptions(): List<SubpluginOption> {
    return if (this is ChangedFiles.Known) {
        val options = mutableListOf<SubpluginOption>()
        this.modified.filter { it.isKotlinFile(listOf("kt")) || it.isJavaFile() }.ifNotEmpty {
            options += SubpluginOption("knownModified", map { it.path }.joinToString(File.pathSeparator))
        }
        this.removed.filter { it.isKotlinFile(listOf("kt")) || it.isJavaFile() }.ifNotEmpty {
            options += SubpluginOption("knownRemoved", map { it.path }.joinToString(File.pathSeparator))
        }
        options
    } else {
        emptyList()
    }
}

// Return a closure that captures required arguments only.
internal fun createIncrementalChangesTransformer(
    isKspIncremental: Boolean,
    isIntermoduleIncremental: Boolean,
    cacheDir: File,
    classpathStructure: Provider<FileCollection>,
    libraries: Provider<FileCollection>,
    processorCP: Provider<FileCollection>,
): (ChangedFiles) -> List<SubpluginOption> = { changedFiles ->
    val options = mutableListOf<SubpluginOption>()
    val apClasspath = processorCP.get().files.toList()
    if (isKspIncremental) {
        if (isIntermoduleIncremental) {
            // findClasspathChanges may clear caches, if there are
            // 1. unknown changes, or
            // 2. changes in annotation processors.
            val classpathChanges = findClasspathChanges(
                changedFiles,
                cacheDir,
                classpathStructure.get().files,
                libraries.get().files.toList(),
                apClasspath
            )
            options += classpathChanges.toSubpluginOptions()
        } else {
            if (changedFiles.hasNonSourceChange()) {
                cacheDir.deleteRecursively()
            }
        }
    } else {
        cacheDir.deleteRecursively()
    }
    options += changedFiles.toSubpluginOptions()

    options += FilesSubpluginOption("apclasspath", apClasspath)

    options
}

internal fun Configuration.markResolvable(): Configuration = apply {
    isCanBeResolved = true
    isCanBeConsumed = false
    isVisible = false
}
