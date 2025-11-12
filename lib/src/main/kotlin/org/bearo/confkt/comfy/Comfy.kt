package org.bearo.confkt.comfy

import org.bearo.Validator
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Comfy(
    /** Application name for global config directory (~/.config/{appName}) */
    val appName: String = "",

    /** Environment variable prefix (e.g., "MYAPP_" for MYAPP_HOST) */
    val envPrefix: String = "",

    /** System property prefix (e.g., "myapp." for myapp.host) */
    val sysPropPrefix: String = "",

    /** Fail fast on any configuration error */
    val failFast: Boolean = false,

    /** Fail if expected config files are missing */
    val failOnMissingFile: Boolean = false,

    /** Ignore unknown keys in config files */
    val ignoreUnknownKeys: Boolean = true,

    /** Use lenient parsing for JSON/TOML */
    val lenientParsing: Boolean = true,

    /** Generate extension functions for easy access */
    val generateExtensions: Boolean = true,

    /** Generate a singleton instance */
    val generateSingleton: Boolean = false,

    /** Custom validator classes to apply */
    val validators: Array<KClass<out ConfigValidator<*>>> = []
)

/**
 * Marks a property as containing sensitive data that should be masked in logs.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Secret

/**
 * Specifies custom validation for a property.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ValidateWith(val validator: KClass<out Validator<*>>)

/**
 * Specifies a custom config key name (overrides property name).
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ConfigKey(val key: String)

/**
 * Marks a property as required (no default value needed in class definition).
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Required

/**
 * Base interface for configuration validators.
 */
interface ConfigValidator<T : Any> {
    fun validate(config: T): List<String>
}