package org.bearo.confkt

import kotlinx.serialization.Serializable

@Serializable
data class MaskedValue(val value: String) {
    override fun toString(): String = "****"
    fun reveal(): String = value
}