package org.bearo.confkt

/**
 * All available configuration layers.
 */
enum class ConfigLayer(val priority: Int) {
    /**
     * The CLI layer.
     *
     * This represents all config options specified
     * via command-line flags
     */
    COMMAND_LINE_ARGS(0),

    /**
     * The system properties layer.
     *
     * This represents all config options specified
     * via system properties. (`-D` flags)
     */
    SYSTEM_PROPERTIES(1),

    /**
     * The environment variable layer.
     *
     * This represents all config options specified
     * via setting environment variables.
     */
    ENVIRONMENT_VARIABLES(2),

    /**
     * The local JSON config layer.
     *
     * This represents all config options specified
     * via a `.json` file in the current working directory.
     */
    LOCAL_CONFIG_JSON(3),

    /**
     * The local TOML config layer.
     *
     * This represents all config options specified
     * via a `.toml` file in the current working directory.
     */
    LOCAL_CONFIG_TOML(4),

    /**
     * The global JSON config layer.
     *
     * This represents all config options specified
     * via a `.json` file in your config directory.
     */
    GLOBAL_CONFIG_JSON(5),

    /**
     * The global TOML config layer.
     *
     * This represents all config options specified
     * via a `.toml` file in your config directory.
     */
    GLOBAL_CONFIG_TOML(6),

    /**
     * The classpath JSON config layer.
     *
     * This represents all config options specified
     * via a `.json` file in the Java class path.
     */
    CLASSPATH_CONFIG_JSON(7),

    /**
     * The classpath TOML config layer.
     *
     * This represents all config options specified
     * via a `.toml` file in the Java class path.
     */
    CLASSPATH_CONFIG_TOML(8),

    /**
     * The default JSON config layer.
     *
     * This represents all config options specified
     * in the default `.json` file (`config.json`)
     */
    DEFAULTS_JSON(9),

    /**
     * The default TOML config layer.
     *
     * This represents all config options specified
     * in the default `.toml` file (`config.toml`)
     */
    DEFAULTS_TOML(10),

    /**
     * The hardcoded defaults config layer.
     *
     * This represents all config options specified
     * via defaults hardcoded into your source.
     */
    HARDCODED_DEFAULTS(11),
}