package org.bearo

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Paths
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class ConfigLoader<T : Any>(
    private val cfgClass: KClass<T>, private val options: ConfigLoaderOptions = ConfigLoaderOptions()
) {
    private val json = Json {
        ignoreUnknownKeys = options.ignoreUnknownKeys
        isLenient = options.lenientParsing
        prettyPrint = true
        encodeDefaults = true
    }

    private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = options.ignoreUnknownKeys))

    fun load(): ConfigResult<T> {
        val errors = mutableListOf<ConfigError>()
        val sources = mutableMapOf<String, ConfigLayer>()
        val loadedLayers = mutableSetOf<ConfigLayer>()

        val layerVals = ConfigLayer.entries.sortedBy { it.priority }.mapNotNull { layer ->
            when (val result = loadLayer(layer)) {
                is LayerResult.Success -> {
                    loadedLayers.add(layer)
                    layer to result.values
                }

                is LayerResult.Error -> {
                    if (result.error.cause !is java.io.FileNotFoundException || options.failOnMissingFile) {
                        errors.add(result.error)
                    }

                    null
                }

                is LayerResult.NotFound -> null
            }
        }

        val mergedVals = mutableMapOf<String, Any?>()
        for ((layer, values) in layerVals.reversed()) {
            for ((k, v) in values) {
                if (!mergedVals.containsKey(k)) {
                    mergedVals[k] = v
                    sources[k] = layer
                }
            }
        }

        return try {
            val instance = constructInstance(mergedVals, sources, errors)
            if (errors.isNotEmpty() && options.failFast) {
                ConfigResult.Failure(errors)
            } else {
                ConfigResult.Success(instance, ConfigMetadata(sources, loadedLayers))
            }
        } catch (e: Exception) {
            errors.add(
                ConfigError(
                    ConfigLayer.HARDCODED_DEFAULTS, "Failed to construct configuration object", e
                )
            )
            ConfigResult.Failure(errors)
        }
    }

    private fun loadLayer(layer: ConfigLayer): LayerResult {
        return try {
            when (layer) {
                ConfigLayer.COMMAND_LINE_ARGS -> loadCmdLineArgs()
                ConfigLayer.SYSTEM_PROPERTIES -> loadSystemProperties()
                ConfigLayer.ENVIRONMENT_VARIABLES -> loadEnvVars()
                ConfigLayer.LOCAL_CONFIG_JSON -> loadLocalFile("config.json", ::parseJson)
                ConfigLayer.LOCAL_CONFIG_TOML -> loadLocalFile("config.toml", ::parseToml)
                ConfigLayer.GLOBAL_CONFIG_JSON -> loadGlobalFile("config.json", ::parseJson)
                ConfigLayer.GLOBAL_CONFIG_TOML -> loadGlobalFile("config.toml", ::parseToml)
                ConfigLayer.CLASSPATH_CONFIG_JSON -> loadClasspathResource("config.json", ::parseJson)
                ConfigLayer.CLASSPATH_CONFIG_TOML -> loadClasspathResource("config.toml", ::parseToml)
                ConfigLayer.DEFAULTS_JSON -> loadClasspathResource("defaults.json", ::parseJson)
                ConfigLayer.DEFAULTS_TOML -> loadClasspathResource("defaults.toml", ::parseToml)
                ConfigLayer.HARDCODED_DEFAULTS -> loadHardcodedDefaults()
            }
        } catch (e: Exception) {
            LayerResult.Error(ConfigError(layer, "Failed to load layer", e))
        }
    }

    private fun loadCmdLineArgs(): LayerResult {
        val args = options.commandLineArgs ?: return LayerResult.NotFound
        val values = mutableMapOf<String, Any?>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("--")) {
                val key = arg.removePrefix("--")
                if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    values[key] = parseValue(args[i + 1])
                    i += 2
                } else {
                    values[key] = true
                    i++
                }
            } else {
                i++
            }
        }

        return if (values.isNotEmpty()) LayerResult.Success(values) else LayerResult.NotFound
    }

    private fun loadSystemProperties(): LayerResult {
        val prefix = options.systemPropertyPrefix ?: return LayerResult.NotFound
        val values = System.getProperties().filter { (key, _) -> key.toString().startsWith(prefix) }
            .mapKeys { (key, _) -> key.toString().removePrefix(prefix) }
            .mapValues { (_, value) -> parseValue(value.toString()) }.toMutableMap()

        return if (values.isNotEmpty()) LayerResult.Success(values) else LayerResult.NotFound
    }

    private fun loadEnvVars(): LayerResult {
        val pfx = options.envVarPrefix ?: return LayerResult.NotFound
        val values = System.getenv().filter { (key, _) -> key.startsWith(pfx) }.mapKeys { (key, _) ->
            key.removePrefix(pfx).lowercase().replace('_', '.')
        }.mapValues { (_, value) -> parseValue(value) }.toMutableMap()

        return if (values.isNotEmpty()) LayerResult.Success(values) else LayerResult.NotFound
    }

    private fun loadLocalFile(filename: String, parser: (String) -> Map<String, Any?>): LayerResult {
        val localDir = options.localConfigDirectory ?: Paths.get(System.getProperty("user.dir"))
        val file = localDir.resolve(filename).toFile()

        return if (file.exists() && file.isFile) {
            try {
                val content = file.readText()
                LayerResult.Success(parser(content))
            } catch (e: Exception) {
                LayerResult.Error(
                    ConfigError(
                        ConfigLayer.LOCAL_CONFIG_JSON, "Failed to parse $filename", e, file.absolutePath
                    )
                )
            }
        } else {
            LayerResult.NotFound
        }
    }

    private fun loadGlobalFile(filename: String, parser: (String) -> Map<String, Any?>): LayerResult {
        val globalDir = options.globalConfigDirectory ?: run {
            val home = System.getProperty("user.home")
            Paths.get(home, ".config", options.appName ?: "app")
        }
        val file = globalDir.resolve(filename).toFile()

        return if (file.exists() && file.isFile) {
            try {
                val content = file.readText()
                LayerResult.Success(parser(content))
            } catch (e: Exception) {
                LayerResult.Error(
                    ConfigError(
                        ConfigLayer.GLOBAL_CONFIG_JSON, "Failed to parse $filename", e, file.absolutePath
                    )
                )
            }
        } else {
            LayerResult.NotFound
        }
    }

    private fun loadClasspathResource(resourceName: String, parser: (String) -> Map<String, Any?>): LayerResult {
        val resource = cfgClass.java.classLoader.getResourceAsStream(resourceName) ?: return LayerResult.NotFound

        return try {
            val content = resource.bufferedReader().use { it.readText() }
            LayerResult.Success(parser(content))
        } catch (e: Exception) {
            LayerResult.Error(
                ConfigError(
                    ConfigLayer.CLASSPATH_CONFIG_JSON,
                    "Failed to parse classpath resource $resourceName",
                    e,
                    resourceName
                )
            )
        }
    }

    private fun loadHardcodedDefaults(): LayerResult {
        val defaults = mutableMapOf<String, Any?>()
        val constructor = cfgClass.primaryConstructor ?: return LayerResult.NotFound

        constructor.parameters.forEach { param ->
            if (param.isOptional) {
                defaults[param.name!!] = null
            }
        }

        return LayerResult.Success(defaults)
    }

    private fun parseValue(value: String): Any {
        return when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            value.toLongOrNull() != null -> value.toLong()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }

    private fun constructInstance(
        values: Map<String, Any?>, sources: Map<String, ConfigLayer>, errors: MutableList<ConfigError>
    ): T {
        val constructor = cfgClass.primaryConstructor
            ?: throw IllegalArgumentException("Config class must have a primary constructor")

        val params = constructor.parameters.associateWith { parameter ->
            val name = parameter.name ?: throw IllegalArgumentException("Parameter name is null")
            val value = values[name]

            when {
                value != null -> convertValue(value, parameter.type.classifier as? KClass<*>)
                parameter.isOptional -> null
                else -> {
                    errors.add(
                        ConfigError(
                            ConfigLayer.HARDCODED_DEFAULTS, "Required parameter '$name' is missing", path = name
                        )
                    )
                    null
                }
            }
        }

        return constructor.callBy(params.filterValues { it != null })
    }

    private fun parseJson(content: String): Map<String, Any?> {
        return try {
            json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(content)
                .mapValues { (_, element) -> jsonElementToAny(element) }
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid JSON format", e)
        }
    }

    private fun parseToml(content: String): Map<String, Any?> {
        return try {
            toml.decodeFromString(kotlinx.serialization.serializer(), content)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid TOML format", e)
        }
    }

    private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is kotlinx.serialization.json.JsonNull -> null
            is kotlinx.serialization.json.JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content.toLongOrNull() != null -> element.content.toLong()
                    element.content.toDoubleOrNull() != null -> element.content.toDouble()
                    else -> element.content
                }
            }

            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToAny(it) }
            is kotlinx.serialization.json.JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertValue(value: Any?, targetType: KClass<*>?): Any? {
        if (value == null || targetType == null) return value

        return when (targetType) {
            String::class -> value.toString()
            Int::class -> when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }

            Long::class -> when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }

            Double::class -> when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }

            Boolean::class -> when (value) {
                is Boolean -> value
                is String -> value.toBoolean()
                else -> null
            }

            List::class if value is List<*> -> value
            Map::class if value is Map<*, *> -> value
            else -> value
        }
    }

    private sealed class LayerResult {
        data class Success(val values: Map<String, Any?>) : LayerResult()
        data class Error(val error: ConfigError) : LayerResult()
        object NotFound : LayerResult()
    }
}

data class ConfigLoaderOptions(
    val appName: String? = null,
    val localConfigDirectory: java.nio.file.Path? = null,
    val globalConfigDirectory: java.nio.file.Path? = null,
    val envVarPrefix: String? = null,
    val systemPropertyPrefix: String? = null,
    val commandLineArgs: Array<String>? = null,
    val ignoreUnknownKeys: Boolean = true,
    val lenientParsing: Boolean = true,
    val failOnMissingFile: Boolean = false,
    val failFast: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConfigLoaderOptions
        return appName == other.appName && localConfigDirectory == other.localConfigDirectory && globalConfigDirectory == other.globalConfigDirectory && envVarPrefix == other.envVarPrefix && systemPropertyPrefix == other.systemPropertyPrefix && commandLineArgs.contentEquals(
            other.commandLineArgs
        ) && ignoreUnknownKeys == other.ignoreUnknownKeys && lenientParsing == other.lenientParsing && failOnMissingFile == other.failOnMissingFile && failFast == other.failFast
    }

    override fun hashCode(): Int {
        var result = appName?.hashCode() ?: 0
        result = 31 * result + (localConfigDirectory?.hashCode() ?: 0)
        result = 31 * result + (globalConfigDirectory?.hashCode() ?: 0)
        result = 31 * result + (envVarPrefix?.hashCode() ?: 0)
        result = 31 * result + (systemPropertyPrefix?.hashCode() ?: 0)
        result = 31 * result + (commandLineArgs?.contentHashCode() ?: 0)
        result = 31 * result + ignoreUnknownKeys.hashCode()
        result = 31 * result + lenientParsing.hashCode()
        result = 31 * result + failOnMissingFile.hashCode()
        result = 31 * result + failFast.hashCode()
        return result
    }
}