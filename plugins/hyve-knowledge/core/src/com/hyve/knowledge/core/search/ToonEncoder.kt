package com.hyve.knowledge.core.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


class ToonNotApplicable(message: String) : Exception(message)


object ToonEncoder {
    private const val INDENT = "  "
    private val NUMERIC = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")

    fun encode(element: JsonElement): String {
        val sb = StringBuilder()
        when (element) {
            is JsonObject -> encodeObject(element, 0, sb)
            is JsonPrimitive -> sb.append(encodePrimitive(element))
            is JsonArray -> throw ToonNotApplicable("top-level array unsupported")
        }
        return sb.toString().trimEnd('\n')
    }

    private fun encodeObject(obj: JsonObject, depth: Int, sb: StringBuilder) {
        val pad = INDENT.repeat(depth)
        for ((key, value) in obj) {
            when (value) {
                is JsonPrimitive -> sb.append(pad).append(key).append(": ").append(encodePrimitive(value)).append('\n')
                is JsonObject -> {
                    sb.append(pad).append(key).append(":\n")
                    encodeObject(value, depth + 1, sb)
                }
                is JsonArray -> encodeArray(key, value, depth, sb)
            }
        }
    }

    private fun encodeArray(key: String, arr: JsonArray, depth: Int, sb: StringBuilder) {
        val pad = INDENT.repeat(depth)
        if (arr.isEmpty()) {
            sb.append(pad).append(key).append(": []\n")
            return
        }
        if (arr.all { it is JsonPrimitive }) {
            val cells = arr.joinToString(",") { encodePrimitive(it as JsonPrimitive) }
            sb.append(pad).append(key).append('[').append(arr.size).append("]: ").append(cells).append('\n')
            return
        }
        if (arr.all { it is JsonObject }) {
            val rows = arr.map { it as JsonObject }
            val fields = rows.first().keys.toList()
            val uniform = rows.all { it.keys.toList() == fields }
            val flat = rows.all { row -> row.values.all { it is JsonPrimitive } }
            if (!uniform || !flat) throw ToonNotApplicable("non-uniform/non-flat object array: $key")
            sb.append(pad).append(key).append('[').append(rows.size).append("]{")
                .append(fields.joinToString(",")).append("}:\n")
            val rowPad = INDENT.repeat(depth + 1)
            for (row in rows) {
                sb.append(rowPad)
                    .append(fields.joinToString(",") { f -> encodePrimitive(row.getValue(f) as JsonPrimitive) })
                    .append('\n')
            }
            return
        }
        throw ToonNotApplicable("mixed array: $key")
    }

    private fun encodePrimitive(p: JsonPrimitive): String {
        if (p is JsonNull) return "null"
        if (!p.isString) return p.content
        return quoteIfNeeded(p.content)
    }

    private fun quoteIfNeeded(s: String): String {
        if (!needsQuoting(s)) return s
        val out = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        return out.append('"').toString()
    }

    private fun needsQuoting(s: String): Boolean {
        if (s.isEmpty()) return true
        if (s != s.trim()) return true
        if (s == "true" || s == "false" || s == "null") return true
        if (s.startsWith("-")) return true
        if (NUMERIC.matches(s)) return true
        return s.any { it == ',' || it == ':' || it == '"' || it == '\\' ||
            it == '[' || it == ']' || it == '{' || it == '}' || it < ' ' }
    }
}
