package com.hyve.knowledge.core.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement


object ResponseEncoder {
    fun encode(element: JsonElement, json: Json): String {
        try {
            return ToonEncoder.encode(element)
        } catch (_: ToonNotApplicable) {

        }
        return json.encodeToString(JsonElement.serializer(), element)
    }
}
