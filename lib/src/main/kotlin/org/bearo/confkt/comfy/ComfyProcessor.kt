package org.bearo.confkt.comfy


import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.*

class ComfyProcessor(
    private val codeGen: CodeGenerator, private val logger: KSPLogger, private val options: Map<String, String>
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols =
            resolver.getSymbolsWithAnnotation("org.bearo.confkt.Comfy").filterIsInstance<KSClassDeclaration>()

        val unableToProcess = symbols.filterNot { it.validate() }.toList()

        symbols.filter { it.validate() }.forEach { classDeclaration ->
            try {
                processComfyClass(classDeclaration)
            } catch (e: Exception) {
                logger.error(
                    "Failed to process ${classDeclaration.qualifiedName?.asString()}: ${e.message}", classDeclaration
                )
            }
        }

        return unableToProcess
    }

    private fun processComfyClass(classDeclaration: KSClassDeclaration) {
        val pkgName = classDeclaration.packageName.asString()
        val className = classDeclaration.simpleName.asString()
        val comfyAnnotation = classDeclaration.annotations.first { it.shortName.asString() == "Comfy" }
        val config = extractComfyConfig(comfyAnnotation)

        val file = FileSpec.builder(pkgName, "${className}Loader")
            .addFileComment("Generated code for @Comfy configuration. Do not edit!", arrayOf<Any>())
            .addImport("org.bearo.confkt", "ConfigLoader", "ConfigLoaderOptions", "ConfigResult").apply {
                generateLoaderObject(this, classDeclaration, className, config)

                if (config.generateExtensions) {
                    generateExtensionFunctions(this, classDeclaration, className)
                }

                if (config.generateSingleton) {
                    generateSingletonObject(this, classDeclaration, className, config)
                }

                generateBuilderClass(this, classDeclaration, className, config)
            }.build()

        val dependencies = Dependencies(true, classDeclaration.containingFile!!)
        codeGen.createNewFile(dependencies, pkgName, "${className}Loader").use { outputStream ->
                outputStream.writer().use { writer ->
                    file.writeTo(writer)
                }
            }
    }

    private fun generateLoaderObject(
        fileBuilder: FileSpec.Builder, classDeclaration: KSClassDeclaration, className: String, config: ComfyConfig
    ) {
        val loaderClass = ClassName("org.bearo.confkt", "ConfigLoader")
        val optionsClass = ClassName("org.bearo.confkt", "ConfigLoaderOptions")
        val configClassName = ClassName(classDeclaration.packageName.asString(), className)

        val objectSpec = TypeSpec.objectBuilder("${className}Loader").addKdoc(
            """
                Loader for {@link $className} configuration.
                
                Usage:
```kotlin
                val result = ${className}Loader.load()
                when (result) {
                    is ConfigResult.Success -> println(result.value)
                    is ConfigResult.Failure -> result.errors.forEach { println(it) }
                }
```
                """.trimIndent()
        ).addFunction(
            FunSpec.builder("load").addParameter(
                ParameterSpec.builder("commandLineArgs", ARRAY.parameterizedBy(STRING).copy(nullable = true))
                    .defaultValue("null").build()
            ).addParameter(
                ParameterSpec.builder(
                    "localConfigDir", ClassName("java.nio.file", "Path").copy(nullable = true)
                ).defaultValue("null").build()
            ).addParameter(
                ParameterSpec.builder(
                    "globalConfigDir", ClassName("java.nio.file", "Path").copy(nullable = true)
                ).defaultValue("null").build()
            ).returns(ClassName("org.bearo.confkt", "ConfigResult").parameterizedBy(configClassName)).addCode(
                """
                        val options = %T(
                            appName = %S,
                            envVarPrefix = %S,
                            systemPropertyPrefix = %S,
                            commandLineArgs = commandLineArgs,
                            localConfigDirectory = localConfigDir,
                            globalConfigDirectory = globalConfigDir,
                            ignoreUnknownKeys = %L,
                            lenientParsing = %L,
                            failOnMissingFile = %L,
                            failFast = %L
                        )
                        
                        val loader = %T(%T::class, options)
                        return loader.load()
                        """.trimIndent(),
                optionsClass,
                config.appName.ifEmpty { className.lowercase() },
                config.envPrefix.ifEmpty { "${className.uppercase()}_" },
                config.sysPropPrefix.ifEmpty { "${className.lowercase()}." },
                config.ignoreUnknownKeys,
                config.lenientParsing,
                config.failOnMissingFile,
                config.failFast,
                loaderClass,
                configClassName
            ).build()
        ).addFunction(
            FunSpec.builder("loadOrThrow").addParameter(
                ParameterSpec.builder("commandLineArgs", ARRAY.parameterizedBy(STRING).copy(nullable = true))
                    .defaultValue("null").build()
            ).addParameter(
                ParameterSpec.builder(
                    "localConfigDir", ClassName("java.nio.file", "Path").copy(nullable = true)
                ).defaultValue("null").build()
            ).addParameter(
                ParameterSpec.builder(
                    "globalConfigDir", ClassName("java.nio.file", "Path").copy(nullable = true)
                ).defaultValue("null").build()
            ).returns(configClassName)
                .addCode("return load(commandLineArgs, localConfigDir, globalConfigDir).getOrThrow()").build()
        ).build()

        fileBuilder.addType(objectSpec)
    }

    private fun generateExtensionFunctions(
        fileBuilder: FileSpec.Builder, classDeclaration: KSClassDeclaration, className: String
    ) {
        val configClassName = ClassName(classDeclaration.packageName.asString(), className)

        fileBuilder.addFunction(
            FunSpec.builder("getOrDefault")
                .receiver(ClassName("org.bearo.confkt", "ConfigResult").parameterizedBy(configClassName))
                .addParameter("default", configClassName).returns(configClassName).addCode("return getOrElse(default)")
                .build()
        )

        fileBuilder.addFunction(
            FunSpec.builder("to$className").receiver(MAP.parameterizedBy(STRING, ANY.copy(nullable = true)))
                .returns(configClassName.copy(nullable = true)).addCode(
                    """
                    return try {
                        %T::class.constructors.first().callBy(
                            %T::class.constructors.first().parameters.associateWith { param ->
                                this[param.name]
                            }
                        )
                    } catch (e: Exception) {
                        null
                    }
                    """.trimIndent(), configClassName, configClassName
                ).build()
        )
    }

    private fun generateSingletonObject(
        fileBuilder: FileSpec.Builder, classDeclaration: KSClassDeclaration, className: String, config: ComfyConfig
    ) {
        val configClassName = ClassName(classDeclaration.packageName.asString(), className)

        fileBuilder.addType(
            TypeSpec.objectBuilder("${className}Singleton")
                .addKdoc("Lazily initialized singleton instance of $className.").addProperty(
                    PropertySpec.builder("instance", configClassName)
                        .delegate("lazy·{ ${className}Loader.loadOrThrow() }").build()
                ).build()
        )
    }

    private fun generateBuilderClass(
        fileBuilder: FileSpec.Builder, classDeclaration: KSClassDeclaration, className: String, config: ComfyConfig
    ) {
        val cfgClassName = ClassName(classDeclaration.packageName.asString(), className)
        val properties = classDeclaration.primaryConstructor?.parameters ?: emptyList()

        val builderClass = TypeSpec.classBuilder("${className}Builder").addKdoc("Builder for $className.").apply {
            properties.forEach { parameter ->
                val paramName = parameter.name?.asString() ?: return@forEach
                val paramType = parameter.type.toTypeName()

                addProperty(
                    PropertySpec.builder(paramName, paramType.copy(nullable = true)).mutable(true).initializer("null")
                        .build()
                )

                addFunction(
                    FunSpec.builder(paramName).addParameter(paramName, paramType)
                        .returns(ClassName("", "${className}Builder")).addCode(
                            """
                                this.$paramName = $paramName
                                return this
                                """.trimIndent()
                        ).build()
                )

                addFunction(
                    FunSpec.builder("build").returns(cfgClassName).addCode(
                        buildString {
                            append("return %T(\n")
                            properties.forEachIndexed { index, param ->
                                val paramName = param.name?.asString() ?: return@forEachIndexed
                                val hasDefault = param.hasDefault

                                append("    $paramName = $paramName")
                                if (hasDefault) {
                                    append(" ?: TODO(\"Provide default\")")
                                } else {
                                    append("!!")
                                }
                                if (index < properties.size - 1) append(",")
                                append("\n")
                            }
                            append(")")
                        }, cfgClassName
                    ).build()
                )
            }
        }.build()

        fileBuilder.addType(builderClass)

        fileBuilder.addFunction(
            FunSpec.builder("builder")
                .receiver(ClassName(classDeclaration.packageName.asString(), className).nestedClass("Companion"))
                .returns(ClassName("", "${className}Builder")).addCode("return ${className}Builder()").build()
        )
    }

    private fun extractComfyConfig(annotation: KSAnnotation): ComfyConfig {
        var appName = ""
        var envPrefix = ""
        var sysPropPrefix = ""
        var failFast = false
        var failOnMissingFile = false
        var ignoreUnknownKeys = true
        var lenientParsing = true
        var generateExtensions = true
        var generateSingleton = false

        annotation.arguments.forEach { arg ->
            when (arg.name?.asString()) {
                "appName" -> appName = arg.value as? String ?: ""
                "envPrefix" -> envPrefix = arg.value as? String ?: ""
                "sysPropPrefix" -> sysPropPrefix = arg.value as? String ?: ""
                "failFast" -> failFast = arg.value as? Boolean ?: false
                "failOnMissingFile" -> failOnMissingFile = arg.value as? Boolean ?: false
                "ignoreUnknownKeys" -> ignoreUnknownKeys = arg.value as? Boolean ?: true
                "lenientParsing" -> lenientParsing = arg.value as? Boolean ?: true
                "generateExtensions" -> generateExtensions = arg.value as? Boolean ?: true
                "generateSingleton" -> generateSingleton = arg.value as? Boolean ?: false
            }
        }

        return ComfyConfig(
            appName,
            envPrefix,
            sysPropPrefix,
            failFast,
            failOnMissingFile,
            ignoreUnknownKeys,
            lenientParsing,
            generateExtensions,
            generateSingleton
        )
    }

    private data class ComfyConfig(
        val appName: String,
        val envPrefix: String,
        val sysPropPrefix: String,
        val failFast: Boolean,
        val failOnMissingFile: Boolean,
        val ignoreUnknownKeys: Boolean,
        val lenientParsing: Boolean,
        val generateExtensions: Boolean,
        val generateSingleton: Boolean
    )
}

class ComfyProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ComfyProcessor(
            environment.codeGenerator, environment.logger, environment.options
        )
    }
}