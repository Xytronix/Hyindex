// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.diff

import kotlinx.serialization.json.*


object JsonDiffer {


    fun flatten(jsonStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val element = Json.parseToJsonElement(jsonStr)
            flattenElement("", element, result)
        } catch (_: Exception) {

        }
        return result
    }

    private fun flattenElement(prefix: String, element: JsonElement, out: MutableMap<String, String>) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    val path = if (prefix.isEmpty()) key else "$prefix.$key"
                    flattenElement(path, value, out)
                }
            }
            is JsonArray -> {
                for ((idx, value) in element.withIndex()) {
                    flattenElement("$prefix.$idx", value, out)
                }
            }
            is JsonPrimitive -> {
                out[prefix] = element.content
            }
            is JsonNull -> {
                out[prefix] = "null"
            }
        }
    }


    fun diffFlat(
        oldFields: Map<String, String>,
        newFields: Map<String, String>,
    ): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()


        for ((key, newVal) in newFields) {
            if (key !in oldFields) {
                changes.add(FieldChange(field = key, newValue = newVal, changeType = ChangeType.ADDED))
            }
        }


        for ((key, oldVal) in oldFields) {
            if (key !in newFields) {
                changes.add(FieldChange(field = key, oldValue = oldVal, changeType = ChangeType.REMOVED))
            }
        }


        for ((key, newVal) in newFields) {
            val oldVal = oldFields[key] ?: continue
            if (oldVal != newVal) {
                changes.add(FieldChange(field = key, oldValue = oldVal, newValue = newVal, changeType = ChangeType.CHANGED))
            }
        }

        return changes.sortedBy { it.field }
    }
}
