package com.thalovant.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What to call a hub on a screen somebody is reading.
 *
 * Every control-plane read in this SDK returns raw JSON, so each caller picks
 * its own fields -- and on 2026-09-15 a phone offered somebody a list of rooms
 * called "ops-copilot", "daily-desk", "news-stream". Those are slugs. The app
 * was not careless: it read `name` and preferred it over `slug`, and on that
 * deployment `name` *holds* the slug. The name a person was shown when the hub
 * was made lives in `spec.catalog.title`.
 *
 * One place to get that wrong is better than one per app.
 */
public fun hubDisplayName(hub: JsonObject): String {
    val title = ((hub["spec"] as? JsonObject)
        ?.get("catalog") as? JsonObject)
        ?.get("title")
        ?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        ?.takeIf { it.isNotBlank() }
    if (title != null) return title

    val name = (hub["name"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?.takeIf { it.isNotBlank() }
    val slug = (hub["slug"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?.takeIf { it.isNotBlank() }

    // A name that is exactly the slug is the slug.
    if (name != null && name != slug) return name

    val identifier = name ?: slug ?: return "A Thalovant hub"
    return identifier.split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { "A Thalovant hub" }
}
