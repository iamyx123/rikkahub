package me.rerere.rikkahub.data.classrecord

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Account validation must become visible before optional tool/catalog network requests finish. */
internal suspend fun refreshClassRecordAccount(
    request: suspend (String) -> JsonObject,
    onAuthenticated: (JsonObject) -> Unit,
    onTools: (List<JsonObject>) -> Unit,
    onOperations: (List<JsonObject>) -> Unit,
) {
    onAuthenticated(request("/api/v1/auth/me"))
    val tools = request("$CLASS_RECORD_PREFIX/tools")
    onTools((tools["tools"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject })
    val catalog = request("$CLASS_RECORD_PREFIX/catalog")
    onOperations((catalog["operations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject })
}
