package org.bearo

import java.net.URI
import kotlin.reflect.KClass

interface Validator<T> {
    fun validate(value: T): ValidationResult
}

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val message: String) : ValidationResult()
}

annotation class Validate(val validator: KClass<out Validator<*>>)

class NonEmptyStringValidator : Validator<String> {
    override fun validate(value: String): ValidationResult {
        return if (value.isNotEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("String cannot be empty")
        }
    }
}

class PortRangeValidator : Validator<Int> {
    override fun validate(value: Int): ValidationResult {
        return if (value in 1..65535) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Port must be between 1 and 65535")
        }
    }
}

class UrlValidator : Validator<String> {
    override fun validate(value: String): ValidationResult {
        return try {
            URI(value)
            ValidationResult.Valid
        } catch (e: Exception) {
            ValidationResult.Invalid("Invalid URL format")
        }
    }
}