package com.thalovant.sdk

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI

public const val INVENTORY_CACHE_VERSION: Int = 1
public const val INVENTORY_CACHE_TTL_SECONDS: Double = 3600.0
public const val HUB_SOURCE: String = "hub"
public val LIVE_SOURCES: Set<String> = setOf(HUB_SOURCE, "ovos-runtime")

@Serializable
public data class Intent(public val id: String, public val name: String,
    @SerialName("skill_id") public val skillId: String, public val engine: String,
    public val phrases: Map<String,List<String>> = emptyMap(),
    public val languages: List<String> = phrases.keys.toList()) {
    public fun examples(language: String? = null, limit: Int = 3): List<String> {
        val tags = (languages + phrases.keys).distinct().filter { it in phrases }
        val tag = if (language.isNullOrEmpty()) tags.firstOrNull() else closestLanguage(language,tags)
        val pool = phrases[tag].orEmpty()
        return if (limit <= 0) pool.toList() else DEFAULT_LISTING.rank(pool,language).take(limit)
    }
}
@Serializable
public data class Skill(public val id: String, public val title: String,
    public val locales: List<String> = emptyList(), public val intents: List<Intent> = emptyList()) {
    public val declaresLocales: Boolean get() = locales.isNotEmpty()
    public fun speaks(language: String): Boolean? = if (locales.isEmpty()) null else closestLanguage(language,locales) != null
}
@Serializable
public data class Inventory(@SerialName("hub_id") public val hubId: String,
    @SerialName("hub_name") public val hubName: String, public val source: String,
    @SerialName("generated_at") public val generatedAt: String, public val skills: List<Skill>,
    public val notes: List<String> = emptyList(), @SerialName("cache_version") public val cacheVersion: Int = INVENTORY_CACHE_VERSION) {
    init { require(cacheVersion == INVENTORY_CACHE_VERSION) { "Not a current inventory cache" } }
    public val live: Boolean get() = source in LIVE_SOURCES
    public val intents: List<Intent> get() = skills.flatMap { it.intents }
    public val hasPhrases: Boolean get() = intents.any { it.phrases.isNotEmpty() }
    public fun asJson(): String = inventoryJson.encodeToString(this)
    public companion object { public fun fromJson(raw: String): Inventory {
        val value = inventoryJson.parseToJsonElement(raw).jsonObject
        require(value["cache_version"]?.jsonPrimitive?.content == "1" && value["cache_version"]?.jsonPrimitive?.isString == false)
        require(value["notes"] is JsonArray && value["skills"] is JsonArray)
        for (skill in value.getValue("skills").jsonArray) {
            require(skill.jsonObject["locales"] is JsonArray && skill.jsonObject["intents"] is JsonArray)
            for (intent in skill.jsonObject.getValue("intents").jsonArray) require(intent.jsonObject["phrases"] is JsonObject)
        }
        return inventoryJson.decodeFromJsonElement(value)
    } }
}
private val inventoryJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
public fun languagesPresent(inventory: Inventory): List<String> = inventory.skills.flatMap { skill -> skill.locales + skill.intents.flatMap { it.phrases.keys } }.toSortedSet().toList()
public fun friendlyTitle(skillId: String): String {
    var name = skillId
    for (prefix in listOf("thalovant-skill-","ovos-skill-","skill-")) if (name.startsWith(prefix)) { name = name.removePrefix(prefix); break }
    if ('.' in name) name = name.substringBeforeLast('.')
    name = name.replace('-',' ').replace('_',' ').trim()
    return Regex("\\p{L}+").replace(name) { match -> match.value.lowercase().replaceFirstChar { it.titlecase() } }.ifEmpty { skillId }
}
private fun inventoryTokens(name: String): List<String> = name.split(Regex("[._]+")).filter { it.isNotEmpty() }
public fun humanize(name: String): String = inventoryTokens(name).joinToString(" ")
public fun commonAffix(names: List<String>): Pair<String?,String> {
    val parts = names.map(::inventoryTokens)
    if (parts.size < 2 || parts.any { it.size < 2 }) return null to ""
    if (parts.map { it.last() }.distinct().size == 1) return "suffix" to parts.first().last()
    if (parts.map { it.first() }.distinct().size == 1) return "prefix" to parts.first().first()
    return null to ""
}
public fun stripAffix(name: String, kind: String?, token: String): String {
    if (kind == null) return name
    val parts = inventoryTokens(name).toMutableList()
    if (kind == "suffix" && parts.lastOrNull() == token) parts.removeAt(parts.lastIndex)
    else if (kind == "prefix" && parts.firstOrNull() == token) parts.removeAt(0)
    return parts.joinToString(" ").ifEmpty { name }
}
public fun compareNames(left: String, right: String): Int {
    val chunks = Regex("[0-9]+|[^0-9]+")
    val a = chunks.findAll(left.lowercase()).map { it.value }.toList(); val b = chunks.findAll(right.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(a.size,b.size)) {
        val x=a[i]; val y=b[i]; val nx=x.first() in '0'..'9'; val ny=y.first() in '0'..'9'
        val compared = if (nx && ny) x.toBigInteger().compareTo(y.toBigInteger()) else if (nx != ny) { if(nx) -1 else 1 } else x.compareTo(y)
        if (compared != 0) return compared
    }
    return a.size.compareTo(b.size)
}
/** Best-effort private cache. A failed read/write never prevents a hub call. */
public class InventoryCache(public val directory: Path = Path.of(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotEmpty() } ?: Path.of(System.getProperty("user.home"),".cache").toString(),"thalovant"),
    public val ttl: Double = INVENTORY_CACHE_TTL_SECONDS) {
    init { require(ttl.isFinite() && ttl >= 0) }
    public fun path(key: String): Path {
        require(Regex("[A-Za-z0-9._-]{1,160}").matches(key)) { "Invalid inventory cache key" }
        return directory.resolve("intents-$key.json")
    }
    public fun load(key: String): Inventory? = try {
        val file=path(key)
        if ((System.currentTimeMillis()-Files.getLastModifiedTime(file).toMillis())/1000.0 > ttl || Files.size(file)>8*1024*1024) null else {
            val raw = Files.newInputStream(file).use { it.readNBytes(8*1024*1024+1) }
            if(raw.size>8*1024*1024) null else Inventory.fromJson(raw.toString(Charsets.UTF_8))
        }
    } catch (_: Exception) { null }
    public fun store(key: String, inventory: Inventory) {
        var scratch: Path? = null
        try {
            val target=path(key); Files.createDirectories(directory)
            scratch = if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.createTempFile(directory,".intents-",".partial",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else Files.createTempFile(directory,".intents-",".partial")
            Files.writeString(scratch,inventory.asJson())
            Files.move(scratch,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) { /* The cache is optional. */ }
        finally { scratch?.let { runCatching { Files.deleteIfExists(it) } } }
    }
    public companion object {
        public fun key(mode: String, identity: Path? = null, host: String? = null): String {
            val digest=MessageDigest.getInstance("SHA-256").digest("$mode|${identity ?: ""}".toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }
            val readable=(host ?: identityHost(identity) ?: "local").replace(Regex("[^A-Za-z0-9._-]"),"-").take(40)
            return "$mode-$readable-$digest"
        }
    }
}

public fun identityHost(identity: Path?): String? = try {
    identity?.let { path -> inventoryJson.parseToJsonElement(Files.readString(path)).jsonObject["default_master"]?.jsonPrimitive?.contentOrNull?.let { URI(it).host } }
} catch (_: Exception) { null }
