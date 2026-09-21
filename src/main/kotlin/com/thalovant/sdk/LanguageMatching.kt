package com.thalovant.sdk

import kotlinx.serialization.json.*

// OVOS-compatible CLDR distances. Adapted from langcodes 3.5.1 (MIT),
// with versioned tables and attribution in the packaged resources.
internal object LanguageMatching {
    val data = listingResource("language-matching.json")
    fun field(section: String, key: String): String? = data[section]?.jsonObject?.get(key)?.jsonPrimitive?.content
    internal data class Tag(var language: String, var script: String = "", var region: String = "")
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

/**
 * The form a language is usually written in, when that differs from [tag].
 *
 * `en-CA` and `en-AT` both to `en-us`, `fr-BE` to `fr-fr`, `pt-AO` to
 * `pt-br`, from CLDR's likely subtags. Null when there is nothing different
 * to try, so a caller can tell "already the usual form" from "no idea".
 *
 * Listing and asking do not agree about languages, and this is what closes
 * the gap. A hub matches an utterance to the closest language it knows, so a
 * phone set to `en-CA` is understood by skills registered under `en-US`; its
 * manifest is keyed by exact tag, so the same hub lists nothing for `en-CA`.
 *
 * Lower case, because that is how skills register and how the manifest is
 * keyed. The manifest lookup is exact, so a retry in the wrong case finds
 * nothing, which is the very failure this exists to end.
 */
public fun usualForm(tag: String): String? {
    if (tag.isBlank()) return null
    val base = LanguageMatching.parse(tag).language.ifEmpty { return null }
    // `und` is the tag for "no idea", and `parse` produces it for anything
    // it cannot read. It has a likely entry -- CLDR's guess for an unknown
    // language is English -- so without this, an empty tag would come back
    // `en-us` and a hub would be listed in a language nobody asked for.
    if (base == "und") return null
    // `maximize` does not fail on a language it has never heard of: it walks
    // its probes down to `und` and takes the root locale's region, so "zzz"
    // comes back "zzz-us" -- a confident United States for a language that
    // does not exist. A direct entry in the likely table is what says CLDR
    // has actually heard of this language, and round-tripping the tag does
    // not, because the unknown language is carried through unchanged.
    if (LanguageMatching.field("likely", base) == null) return null
    val likely = runCatching { LanguageMatching.maximize(LanguageMatching.Tag(base)) }.getOrNull() ?: return null
    val usual = (if (likely.region.isNotEmpty()) "${likely.language}-${likely.region}" else likely.language).lowercase()
    return usual.takeUnless { sameLanguage(it, tag) }
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
