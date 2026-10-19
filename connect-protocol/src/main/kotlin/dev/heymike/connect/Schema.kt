// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package dev.heymike.connect

import kotlinx.serialization.descriptors.*
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.json.*

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Describe(val text: String)

/** Bounded JSON Schema 2020-12 profile. Unknown assertions are rejected, never ignored. */
object Schema {
    private val supported = setOf("type", "properties", "required", "additionalProperties", "items", "enum", "const",
        "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "anyOf", "oneOf", "allOf",
        "description", "title", "default", "\$schema", "\$defs", "\$ref")
    fun check(schema: JsonObject) {
        require(schema.toString().length <= 32_000) { "Schema too large" }
        var count = 0
        val refs = mutableListOf<String>()
        fun walk(s: JsonObject, depth: Int) {
            require(depth <= 8 && ++count <= 200) { "Schema too complex" }
            require(s.keys.all { it in supported }) { "Unsupported schema keyword" }
            for (key in listOf("properties", "\$defs")) s[key]?.let { require(it is JsonObject) { "Invalid $key" } }
            s["items"]?.let { require(it is JsonObject) { "Only object item schemas are supported" } }
            s["additionalProperties"]?.let { require(it is JsonObject || (it is JsonPrimitive && !it.isString && it.booleanOrNull != null)) }
            s["required"]?.let { require(it is JsonArray && it.size <= 200) { "Invalid required list" } }
            s["enum"]?.let { require(it is JsonArray && it.size in 1..200) { "Invalid enum" } }
            for (key in listOf("minLength", "maxLength", "minItems", "maxItems")) s[key]?.let {
                require(it is JsonPrimitive && !it.isString && it.intOrNull?.let { n -> n in 0..1_000_000 } == true) { "Invalid $key" }
            }
            for (key in listOf("minimum", "maximum")) s[key]?.let { require(it is JsonPrimitive && !it.isString && it.content.toBigDecimalOrNull() != null) }
            for (key in listOf("anyOf", "oneOf", "allOf")) s[key]?.let { require(it is JsonArray && it.size in 1..16) { "Invalid $key" } }
            for (key in listOf("title", "description")) s[key]?.let { require(it is JsonPrimitive && it.isString && it.content.length <= 1_000) }
            s["\$schema"]?.let { require(it.jsonPrimitive.content in setOf("https://json-schema.org/draft/2020-12/schema")) { "Unsupported schema dialect" } }
            s["\$ref"]?.let { require(it.jsonPrimitive.content.startsWith("#/\$defs/")) { "Only local defs references are allowed" }; refs += it.jsonPrimitive.content }
            s["type"]?.let { t -> (if (t is JsonArray) t else JsonArray(listOf(t))).forEach { require(it.jsonPrimitive.content in setOf("object","array","string","integer","number","boolean","null")) } }
            for (key in listOf("properties", "\$defs")) (s[key] as? JsonObject)?.values?.forEach { walk(it.jsonObject, depth + 1) }
            for (key in listOf("items", "additionalProperties")) (s[key] as? JsonObject)?.let { walk(it, depth + 1) }
            for (key in listOf("anyOf","oneOf","allOf")) (s[key] as? JsonArray)?.forEach { walk(it.jsonObject, depth + 1) }
            (s["required"] as? JsonArray)?.forEach { require(it is JsonPrimitive && it.isString) }
        }
        walk(schema, 0)
        refs.forEach { reference ->
            val name = reference.removePrefix("#/\$defs/")
            require(!name.contains('/') && (schema["\$defs"] as? JsonObject)?.containsKey(name.replace("~1", "/").replace("~0", "~")) == true) { "Unresolved local reference" }
        }
    }
    fun validate(schema: JsonObject, value: JsonElement) {
        check(schema)
        var steps = 0
        fun verify(s: JsonObject, v: JsonElement, path: String, depth: Int) {
            require(++steps <= 2_000 && depth <= 24) { "Validation budget exceeded" }
            s["\$ref"]?.let {
                val name = it.jsonPrimitive.content.removePrefix("#/\$defs/").replace("~1", "/").replace("~0", "~")
                val ref = (schema["\$defs"] as? JsonObject)?.get(name) as? JsonObject ?: error("Unresolved schema reference")
                verify(ref, v, path, depth + 1)
            }
            s["type"]?.let { type ->
                val types = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
                require(types.any { when (it) {
                    "null" -> v is JsonNull; "object" -> v is JsonObject; "array" -> v is JsonArray
                    "string" -> v is JsonPrimitive && v.isString
                    "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
                    "number" -> v is JsonPrimitive && !v.isString && v.content.toBigDecimalOrNull() != null
                    "integer" -> v is JsonPrimitive && !v.isString && v.content.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
                    else -> false
                } }) { "$path has the wrong type" }
            }
            (s["enum"] as? JsonArray)?.let { require(v in it) { "$path is not an allowed value" } }
            s["const"]?.let { require(v == it) { "$path must match const" } }
            for (key in listOf("anyOf", "oneOf", "allOf")) (s[key] as? JsonArray)?.let { branches ->
                val matches = branches.count { runCatching { verify(it.jsonObject, v, path, depth + 1) }.isSuccess }
                require(when(key) { "anyOf" -> matches > 0; "oneOf" -> matches == 1; else -> matches == branches.size }) { "$path does not match $key" }
            }
            if (v is JsonObject) {
                (s["required"] as? JsonArray)?.forEach { require(v.containsKey(it.jsonPrimitive.content)) { "$path missing required field " + it.jsonPrimitive.content } }
                val props = s["properties"] as? JsonObject ?: buildJsonObject {}
                v.forEach { (key, item) ->
                    val child = props[key] as? JsonObject
                    if (child != null) verify(child, item, "$path.$key", depth + 1)
                    else when (val additional = s["additionalProperties"]) {
                        JsonPrimitive(false) -> throw IllegalArgumentException("$path has unknown field $key")
                        is JsonObject -> verify(additional, item, "$path.$key", depth + 1)
                        else -> Unit
                    }
                }
            }
            if (v is JsonArray) {
                s["minItems"]?.let { require(v.size >= it.jsonPrimitive.int) { "$path too short" } }
                s["maxItems"]?.let { require(v.size <= it.jsonPrimitive.int) { "$path too long" } }
                (s["items"] as? JsonObject)?.let { child -> v.forEachIndexed { i, item -> verify(child, item, "$path[$i]", depth + 1) } }
            }
            if (v is JsonPrimitive && v.isString) {
                s["minLength"]?.let { require(v.content.length >= it.jsonPrimitive.int) }
                s["maxLength"]?.let { require(v.content.length <= it.jsonPrimitive.int) }
            }
            if (v is JsonPrimitive && !v.isString) v.content.toBigDecimalOrNull()?.let { n ->
                s["minimum"]?.let { require(n >= it.jsonPrimitive.content.toBigDecimal()) { "$path below minimum" } }
                s["maximum"]?.let { require(n <= it.jsonPrimitive.content.toBigDecimal()) { "$path above maximum" } }
            }
        }
        verify(schema, value, "arguments", 0)
    }
    fun generate(descriptor: SerialDescriptor): JsonObject {
        fun build(d: SerialDescriptor, depth: Int): JsonObject {
            require(depth <= 8) { "Recursive or deep descriptors need an explicit schema" }
            val body = buildJsonObject {
                when (d.kind) {
                    PrimitiveKind.STRING, PrimitiveKind.CHAR -> put("type", "string")
                    PrimitiveKind.BOOLEAN -> put("type", "boolean")
                    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> put("type", "integer")
                    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
                    SerialKind.ENUM -> { put("type", "string"); put("enum", JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) })) }
                    StructureKind.LIST -> { put("type", "array"); put("items", build(d.getElementDescriptor(0), depth + 1)); put("maxItems", 200) }
                    StructureKind.MAP -> { require(d.getElementDescriptor(0).kind == PrimitiveKind.STRING); put("type", "object"); put("additionalProperties", build(d.getElementDescriptor(1), depth + 1)) }
                    StructureKind.CLASS, StructureKind.OBJECT -> {
                        put("type", "object"); put("additionalProperties", false)
                        put("properties", buildJsonObject {
                            for (i in 0 until d.elementsCount) put(d.getElementName(i), buildJsonObject {
                                build(d.getElementDescriptor(i), depth + 1).forEach { (k,v) -> put(k,v) }
                                d.getElementAnnotations(i).filterIsInstance<Describe>().firstOrNull()?.let { put("description", it.text) }
                            })
                        })
                        put("required", JsonArray((0 until d.elementsCount).filter { !d.isElementOptional(it) }.map { JsonPrimitive(d.getElementName(it)) }))
                    }
                    else -> error("Unsupported descriptor; use an explicit schema")
                }
            }
            return if (d.isNullable) buildJsonObject { put("anyOf", JsonArray(listOf(body, buildJsonObject { put("type", "null") }))) } else body
        }
        return build(descriptor, 0).also(::check)
    }
}
