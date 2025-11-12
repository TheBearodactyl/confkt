package org.bearo.confkt

sealed class ConfigResult<out T> {
    data class Success<T>(val value: T, val metadata: ConfigMetadata) : ConfigResult<T>()
    data class Failure(val errors: List<ConfigError>) : ConfigResult<Nothing>()

    fun <R> map(transform: (T) -> R): ConfigResult<R> = when (this) {
        is Success -> Success(transform(value), metadata)
        is Failure -> this
    }

    fun <R> flatMap(transform: (T) -> ConfigResult<R>): ConfigResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    fun getOrElse(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> default
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw ConfigurationException(errors)
    }

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure
}

data class ConfigMetadata(
    val sources: Map<String, ConfigLayer>,
    val loadedLayers: Set<ConfigLayer>,
    val timestamp: Long = System.currentTimeMillis()
)

data class ConfigError(
    val layer: ConfigLayer, val message: String, val cause: Throwable? = null, val path: String? = null
) {
    override fun toString(): String = buildString {
        append("[$layer]")
        if (path != null) append(" at '$path'")
        append(": $message")
        if (cause != null) append(" (${cause.message})")
    }
}

class ConfigurationException(val errors: List<ConfigError>) : Exception(
    "Configuration failed with ${errors.size} error(s):\n${errors.joinToString("\n") { "  - $it" }}"
)