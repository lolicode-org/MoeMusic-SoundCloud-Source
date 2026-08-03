package org.lolicode.moemusic.soundcloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.lolicode.moemusic.api.IdentifierResolutionResult
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.SearchableMusicSource
import org.lolicode.moemusic.api.SourceException
import org.lolicode.moemusic.api.SourceFormatException
import org.lolicode.moemusic.api.TrackUnavailableException
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.model.ArtistInfo
import org.lolicode.moemusic.api.model.PlaybackResolution
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.SelectionEntryKind
import org.lolicode.moemusic.api.model.TrackInfo
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

private const val SOUND_CLOUD_PREFIX = "soundcloud:"
private const val API_BASE = "https://api-v2.soundcloud.com"
private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
private const val MAX_INPUT_CHARS = 2048
private const val MAX_URL_CHARS = 4096
private const val MAX_QUERY_CHARS = 512
private const val MAX_METADATA_CHARS = 512
private const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000
private const val USER_AGENT = "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.166 Safari/537.36"
private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
private val ID_PATTERN = Regex("[0-9]{1,20}")
private val PATH_SEGMENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
private val SCRIPT_PATTERN = Regex("""https://[A-Za-z0-9.-]+/assets/[A-Za-z0-9-]+\.js""")
private val CLIENT_ID_PATTERN = Regex(
    """(?:client_id|clientId)["']?\s*[:=]\s*["']([A-Za-z0-9_-]{8,128})["']""",
)

internal fun hasSafeJsonNesting(value: String, maxDepth: Int = 64): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (character in value) {
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> if (++depth > maxDepth) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
    }
    return depth == 0 && !inString
}

internal data class SoundCloudParsedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val coverUrl: String?,
    val album: String?,
    val progressiveLookupUrl: String?,
    val blocked: Boolean,
)

private class NotFoundFailure : IOException()

private class FetchFailure(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : IOException(message, cause)

private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.metadataValue(): String? = stringValue()
    ?.takeIf { it.length <= MAX_METADATA_CHARS && it.none(Char::isISOControl) }

private fun JsonElement?.longValue(): Long? =
    (this as? JsonPrimitive)?.longOrNull

private fun JsonElement?.objectValue(): JsonObject? = this as? JsonObject

private fun JsonObject.stringValue(key: String): String? = this[key].stringValue()

private fun JsonObject.metadataValue(key: String): String? = this[key].metadataValue()

private fun JsonObject.longValue(key: String): Long? = this[key].longValue()

private fun JsonObject.objectValue(key: String): JsonObject? = this[key].objectValue()

private fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray

private fun isSoundCloudHost(host: String): Boolean =
    host == "soundcloud.com" ||
        host.endsWith(".soundcloud.com") ||
        host == "sndcdn.com" ||
        host.endsWith(".sndcdn.com") ||
        host == "soundcloud.cloud" ||
        host.endsWith(".soundcloud.cloud")

private fun isSoundCloudApiUrl(value: String): Boolean {
    if (value.length > MAX_URL_CHARS) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.port == -1 &&
        uri.rawFragment == null &&
        (host == "api-v2.soundcloud.com" || host == "api.soundcloud.com")
}

private fun isSoundCloudMediaUrl(value: String): Boolean {
    if (value.length > MAX_URL_CHARS) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.port == -1 &&
        (host == "sndcdn.com" || host.endsWith(".sndcdn.com") ||
            host == "soundcloud.cloud" || host.endsWith(".soundcloud.cloud"))
}

internal fun parseSoundCloudTrackUrl(value: String): String? {
    if (value.length > MAX_INPUT_CHARS) return null
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.port != -1) {
        return null
    }

    val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    if (host != "soundcloud.com") return null

    val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
    if (segments.size != 2 || segments.any { !PATH_SEGMENT_PATTERN.matches(it) }) return null
    if (segments[1].equals("likes", ignoreCase = true)) return null
    return "https://soundcloud.com/" + segments.joinToString("/")
}

private fun isOwnedSoundCloudUrl(value: String): Boolean {
    if (value.length > MAX_INPUT_CHARS) return false
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") == "soundcloud.com"
}

private fun parseTrackId(value: String): String? =
    value.takeIf { it.length <= 20 }?.trim()?.takeIf { ID_PATTERN.matches(it) }

private fun JsonObject.progressiveLookupUrl(): String? {
    val transcodings = objectValue("media")?.arrayValue("transcodings") ?: return null
    return transcodings.asSequence()
        .mapNotNull { it.objectValue() }
        .firstOrNull { transcoding ->
            val format = transcoding.objectValue("format")
            format?.stringValue("protocol").equals("progressive", ignoreCase = true) &&
                format?.stringValue("mime_type").equals("audio/mpeg", ignoreCase = true) &&
                (transcoding["snipped"] as? JsonPrimitive)?.booleanOrNull != true &&
                !transcoding.stringValue("url").orEmpty().contains("/preview/") &&
                transcoding.stringValue("url")?.let(::isSoundCloudApiUrl) == true
        }
        ?.stringValue("url")
}

internal fun parseSoundCloudTrack(
    data: JsonObject,
    fallbackId: String? = null,
): SoundCloudParsedTrack? {
    val id = (data.stringValue("id") ?: fallbackId)?.takeIf { ID_PATTERN.matches(it) } ?: return null
    val title = data.metadataValue("title") ?: return null
    val user = data.objectValue("user")
    val artist = user?.metadataValue("username") ?: "SoundCloud"
    val duration = (data.longValue("full_duration") ?: data.longValue("duration") ?: -1L)
        .takeIf { it in 1..MAX_DURATION_MS } ?: -1L
    val coverUrl = data.stringValue("artwork_url")?.takeIf(::isSoundCloudMediaUrl)
    val album = data.objectValue("publisher_metadata")?.metadataValue("album_title")
    val progressiveLookupUrl = data.progressiveLookupUrl()
    val policy = data.stringValue("policy")
    return SoundCloudParsedTrack(
        id = id,
        title = title,
        artist = artist,
        durationMs = duration,
        coverUrl = coverUrl,
        album = album,
        progressiveLookupUrl = progressiveLookupUrl,
        blocked = policy.equals("BLOCK", ignoreCase = true) || policy.equals("SNIP", ignoreCase = true),
    )
}

private fun SoundCloudParsedTrack.toTrackInfo(): TrackInfo =
    TrackInfo(id = id, title = title, artists = listOf(ArtistInfo.fromName(artist)), durationMs = durationMs) {
        sourceId = SoundCloudPlugin.SOURCE_ID
        coverUrl = this@toTrackInfo.coverUrl
        album = this@toTrackInfo.album
        unavailableReason = when {
            blocked -> LocalizedText.key("error.soundcloud.blocked")
            progressiveLookupUrl == null -> LocalizedText.key("error.soundcloud.no_progressive")
            else -> null
        }
    }

private fun SoundCloudParsedTrack.toSelectionEntry(): SelectionEntry =
    SelectionEntry(selectionId = id, title = title, artists = listOf(ArtistInfo.fromName(artist)), durationMs = durationMs) {
        sourceId = SoundCloudPlugin.SOURCE_ID
        album = this@toSelectionEntry.album
        kind = SelectionEntryKind.TRACK
        unavailableReason = when {
            blocked -> LocalizedText.key("error.soundcloud.blocked")
            progressiveLookupUrl == null -> LocalizedText.key("error.soundcloud.no_progressive")
            else -> null
        }
    }

class SoundCloudSource(initialConfig: SoundCloudConfig = SoundCloudConfig()) :
    SearchableMusicSource,
    org.lolicode.moemusic.api.IdentifierResolvableMusicSource {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Volatile
    private var config: SoundCloudConfig = initialConfig

    @Volatile
    private var discoveredClientId: String? = initialConfig.clientId.trim().takeIf(String::isNotEmpty)

    override val id: String = SoundCloudPlugin.SOURCE_ID
    override val displayName: LocalizedText = LocalizedText.key("source.soundcloud")

    fun updateConfig(config: SoundCloudConfig) {
        this.config = config
        discoveredClientId = config.clientId.trim().takeIf(String::isNotEmpty)
    }

    override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> {
        if (!config.enabled) return UserResult.Error(disabledMessage())
        val text = query.query.trim()
        if (text.isEmpty()) {
            return UserResult.Success(SearchResult(emptyList(), id, 0))
        }
        if (text.length > MAX_QUERY_CHARS || text.any(Char::isISOControl)) {
            return UserResult.Error(invalidQueryMessage())
        }

        return try {
            val limit = (query.limit.takeIf { it > 0 } ?: 20).coerceIn(1, 50)
            val offset = query.offset.coerceAtLeast(0)
            val root = fetchJsonWithClientId { clientId ->
                apiUrl("/search/tracks", mapOf(
                    "q" to text,
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "client_id" to clientId,
                ))
            }
            val entries = ((root["collection"] as? JsonArray) ?: JsonArray(emptyList()))
                .asSequence()
                .mapNotNull { it.objectValue()?.let(::parseSoundCloudTrack) }
                .map(SoundCloudParsedTrack::toSelectionEntry)
                .toList()
            val total = root.longValue("total_results")?.toInt()?.coerceAtLeast(entries.size)
                ?: entries.size
            UserResult.Success(SearchResult(entries, id, total))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            UserResult.Error(requestFailedMessage())
        }
    }

    override suspend fun resolveIdentifier(
        identifier: String,
        submitter: MoeMusicUser?,
    ): IdentifierResolutionResult {
        if (!config.enabled) return IdentifierResolutionResult.Blocked(disabledMessage())

        val input = identifier.trim()
        if (input.length > MAX_INPUT_CHARS) {
            return IdentifierResolutionResult.Blocked(invalidTrackIdMessage())
        }
        if (input.startsWith(SOUND_CLOUD_PREFIX)) {
            val trackId = parseTrackId(input.removePrefix(SOUND_CLOUD_PREFIX))
                ?: return IdentifierResolutionResult.Blocked(invalidTrackIdMessage())
            return resolveById(trackId)
        }

        val url = parseSoundCloudTrackUrl(input)
            ?: return if (isOwnedSoundCloudUrl(input)) {
                IdentifierResolutionResult.Blocked(unsupportedLinkMessage())
            } else {
                IdentifierResolutionResult.Pass
            }

        return try {
            val root = fetchJsonWithClientId { clientId ->
                apiUrl("/resolve", mapOf("url" to url, "client_id" to clientId))
            }
            val track = root.takeIf { it.stringValue("kind").equals("track", ignoreCase = true) }
                ?.let(::parseSoundCloudTrack)
            track?.let { IdentifierResolutionResult.Resolved(it.toTrackInfo()) }
                ?: IdentifierResolutionResult.Blocked(trackNotFoundMessage())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            IdentifierResolutionResult.Blocked(requestFailedMessage())
        }
    }

    override suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser?): UserResult<TrackInfo?> {
        if (!config.enabled) return UserResult.Error(disabledMessage())
        val id = parseTrackId(trackId) ?: return UserResult.Error(invalidTrackIdMessage())
        return try {
            fetchTrack(id)?.let { UserResult.Success(it.toTrackInfo()) } ?: UserResult.Success(null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NotFoundFailure) {
            UserResult.Success(null)
        } catch (_: Exception) {
            UserResult.Error(requestFailedMessage())
        }
    }

    override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
        if (!config.enabled) throw TrackUnavailableException(disabledMessage())
        val id = parseTrackId(track.id) ?: throw SourceFormatException()

        val parsed = try {
            fetchTrack(id) ?: throw TrackUnavailableException(trackNotFoundMessage())
        } catch (e: TrackUnavailableException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(requestFailedMessage(), e)
        }

        if (parsed.blocked) throw TrackUnavailableException(LocalizedText.key("error.soundcloud.blocked"))
        val lookupUrl = parsed.progressiveLookupUrl
            ?: throw TrackUnavailableException(LocalizedText.key("error.soundcloud.no_progressive"))

        val playbackUrl = try {
            val response = fetchJsonWithClientId { clientId -> withClientId(lookupUrl, clientId) }
            response.stringValue("url")?.takeIf(::isSoundCloudMediaUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NotFoundFailure) {
            null
        } catch (e: Exception) {
            throw SourceException(requestFailedMessage(), e)
        } ?: throw TrackUnavailableException(LocalizedText.key("error.soundcloud.no_progressive"))

        return PlaybackResolution(PlaybackResource(playbackUrl))
    }

    private suspend fun resolveById(trackId: String): IdentifierResolutionResult =
        try {
            fetchTrack(trackId)?.let { IdentifierResolutionResult.Resolved(it.toTrackInfo()) }
                ?: IdentifierResolutionResult.Blocked(trackNotFoundMessage())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            IdentifierResolutionResult.Blocked(requestFailedMessage())
        }

    private suspend fun fetchTrack(trackId: String): SoundCloudParsedTrack? {
        val root = fetchJsonWithClientId { clientId ->
            apiUrl("/tracks/" + trackId, mapOf("client_id" to clientId))
        }
        return parseSoundCloudTrack(root, trackId)
    }

    private suspend fun fetchJsonWithClientId(url: (String) -> String): JsonObject {
        repeat(2) { attempt ->
            try {
                return fetchJson(url(clientId()))
            } catch (e: FetchFailure) {
                if (attempt != 0 || config.clientId.isNotBlank() || e.statusCode !in setOf(401, 403)) throw e
                discoveredClientId = null
            }
        }
        throw FetchFailure("SoundCloud client id refresh failed")
    }

    private suspend fun fetchJson(url: String): JsonObject {
        val body = withContext(Dispatchers.IO) { requestText(url) }
            ?: throw NotFoundFailure()
        if (!hasSafeJsonNesting(body)) throw SourceFormatException()
        return runCatching { JSON.parseToJsonElement(body).jsonObject }
            .getOrElse { throw SourceFormatException(it) }
    }

    private suspend fun clientId(): String {
        val configured = config.clientId.trim()
        if (configured.isNotEmpty()) return configured
        discoveredClientId?.let { return it }

        val discovered = withContext(Dispatchers.IO) {
            val page = requestText("https://soundcloud.com") ?: throw FetchFailure("SoundCloud homepage not found")
            val scripts = SCRIPT_PATTERN.findAll(page).map { it.value }.distinct().toList().takeLast(9).asReversed()
            scripts.asSequence()
                .mapNotNull { script ->
                    val scriptHost = runCatching { URI(script).host?.lowercase() }.getOrNull()
                    if (scriptHost == null || !isSoundCloudHost(scriptHost)) return@mapNotNull null
                    val body = requestText(script) ?: return@mapNotNull null
                    CLIENT_ID_PATTERN.find(body)?.groupValues?.getOrNull(1)
                }
                .firstOrNull()
                ?: throw FetchFailure("SoundCloud client id not found")
        }
        discoveredClientId = discovered
        return discovered
    }

    private fun requestText(url: String): String? {
        val uri = runCatching { URI(url) }.getOrElse { throw FetchFailure("Invalid upstream URL", it) }
        val host = uri.host?.lowercase()
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.userInfo != null ||
            uri.port != -1 ||
            host == null ||
            !isSoundCloudHost(host)
        ) {
            throw FetchFailure("Untrusted upstream URL")
        }

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/html")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: HttpTimeoutException) {
            throw FetchFailure("SoundCloud request timed out", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FetchFailure("SoundCloud request interrupted", e)
        } catch (e: IOException) {
            throw FetchFailure("SoundCloud request failed", e)
        }

        if (response.statusCode() == 404) {
            response.body().close()
            return null
        }
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw FetchFailure("SoundCloud returned HTTP " + response.statusCode(), statusCode = response.statusCode())
        }

        val bytes = response.body().use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
        if (bytes.size > MAX_RESPONSE_BYTES) throw FetchFailure("SoundCloud response is too large")
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun apiUrl(path: String, parameters: Map<String, String>): String =
        API_BASE + path + "?" + parameters.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }

    private fun withClientId(url: String, clientId: String): String {
        if (!isSoundCloudApiUrl(url)) throw FetchFailure("Untrusted SoundCloud lookup URL")
        if (URI(url).rawQuery?.split('&')?.any { it.startsWith("client_id=") } == true) return url
        return url + (if (url.contains("?")) "&" else "?") + "client_id=" + encode(clientId)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun disabledMessage(): LocalizedText = LocalizedText.key("error.soundcloud.disabled")

    private fun invalidQueryMessage(): LocalizedText = LocalizedText.key("error.soundcloud.invalid_query")

    private fun invalidTrackIdMessage(): LocalizedText = LocalizedText.key("error.soundcloud.invalid_track_id")

    private fun trackNotFoundMessage(): LocalizedText = LocalizedText.key("error.soundcloud.track_not_found")

    private fun unsupportedLinkMessage(): LocalizedText = LocalizedText.key("error.soundcloud.unsupported_link")

    private fun requestFailedMessage(): LocalizedText = LocalizedText.key("error.soundcloud.request_failed")
}
