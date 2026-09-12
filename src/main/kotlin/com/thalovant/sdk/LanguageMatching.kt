package com.thalovant.sdk

import kotlinx.serialization.json.*

// OVOS-compatible CLDR distances. Adapted from langcodes 3.5.1 (MIT),
// with versioned tables and attribution in the packaged resources.
private object LanguageMatching {
    val data = listingResource("language-matching.json")
    fun field(section: String, key: String): String? = data[section]?.jsonObject?.get(key)?.jsonPrimitive?.content
    data class Tag(var language: String, var script: String = "", var region: String = "")
    private fun script(value: String) = value.length == 4 && value.all { it in 'a'..'z' }
    private fun region(value: String) = (value.length == 2 && value.all { it in 'a'..'z' }) || (value.length == 3 && value.all { it in '0'..'9' })
    fun parse(raw: String, aliases: Boolean = true): Tag {
        var value = raw.trim().replace('_', '-').lowercase()
        if (aliases) value = field("languages", value)?.lowercase() ?: value
        val tokens = value.split('-'); val primary = tokens.first().ifEmpty { "und" }
        val base = if (aliases) field("languages", primary)?.let { parse(it, false) } ?: Tag(primary) else Tag(primary)
        var onlyScript = true
        for (token in tokens.drop(1)) {
            if (!script(token)) onlyScript = false
            if (token.length == 1) break
            if (script(token)) base.script = field("scripts", token) ?: token.replaceFirstChar { it.uppercase() }
            else if (region(token)) base.region = field("territories", token) ?: token.uppercase()
        }
        if (base.script == field("default_scripts", base.language)) base.script = ""
        if (base.language == "pt" && base.script.isEmpty() && base.region.isEmpty() && onlyScript) base.region = "PT"
        return base
    }
    fun maximize(value: Tag): Tag {
        if (value.language == "und" && value.script.isEmpty() && value.region.isEmpty()) return Tag("und", "Zzzz", "ZZ")
        value.language = field("macrolanguages", value.language) ?: value.language
        fun join(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("-")
        val probes = mutableListOf(join(value.language,value.script,value.region),join(value.language,value.region),join(value.language,value.script),value.language)
        if (value.script.isNotEmpty()) probes.add("und-${value.script}")
        probes.add("und")
        val parts = probes.firstNotNullOf { field("likely",it) }.split('-')
        if (value.language == "und") value.language = parts[0]
        if (value.script.isEmpty()) value.script = parts[1]
        if (value.region.isEmpty()) value.region = parts[2]
        return value
    }
    fun distance(target: String, candidate: String): Int {
        val a = maximize(parse(target)); val b = maximize(parse(candidate))
        fun lookup(from: String, to: String, fallback: Int) = data["distances"]!!.jsonObject[from]?.jsonObject?.get(to)?.jsonPrimitive?.int ?: fallback
        var result = if (a.language == b.language) 0 else lookup(a.language,b.language,80)
        val pa = "${a.language}_${a.script}"; val pb = "${b.language}_${b.script}"
        if (a.script != b.script) result += lookup(pa,pb,50)
        if (a.region == b.region) return result
        fun inside(group: String, region: String) = data["regions"]!!.jsonObject[group]!!.jsonArray.any { it.jsonPrimitive.content == region }
        var td = 4
        if (pa == pb) {
            when {
                a.language == "ar" -> if (inside("MAGHREB",a.region) != inside("MAGHREB",b.region)) td = 5
                a.language == "en" -> {
                    if ((a.region == "GB" && !inside("US",b.region)) || (!inside("US",a.region) && b.region == "GB")) td = 3
                    else if (inside("US",a.region) != inside("US",b.region)) td = 5
                }
                inside("LATIN_AMERICA",a.region) && b.region == "419" -> td = 1
                a.language == "es" || a.language == "pt" -> if (inside("AMERICAS",a.region) != inside("AMERICAS",b.region)) td = 5
                pa == "zh_Hant" -> if (inside("CNSAR",a.region) != inside("CNSAR",b.region)) td = 5
            }
        }
        return result + td
    }
}

/** Nearest OVOS-compatible locale at distance ten or less; ties retain input order. */
public fun closestLanguage(target: String, available: Iterable<String>): String? {
    var best: String? = null; var minimum = Int.MAX_VALUE
    for (candidate in available) {
        val distance = LanguageMatching.distance(target,candidate)
        if (distance < minimum) { best = candidate; minimum = distance }
    }
    return best.takeIf { minimum <= 10 }
}
