package org.lolicode.moemusic.soundcloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundCloudSourceTest {
    @Test
    fun trackUrlParserCanonicalizesOwnedUrls() {
        assertEquals(
            "https://soundcloud.com/artist/track-name",
            parseSoundCloudTrackUrl("https://www.soundcloud.com/artist/track-name?si=abc"),
        )
        assertNull(parseSoundCloudTrackUrl("https://soundcloud.com/artist/likes"))
        assertNull(parseSoundCloudTrackUrl("https://example.com/artist/track-name"))
        assertNull(parseSoundCloudTrackUrl("https://soundcloud.com.evil.test/artist/track-name"))
        assertNull(parseSoundCloudTrackUrl("https://user@soundcloud.com/artist/track-name"))
        assertNull(parseSoundCloudTrackUrl("https://soundcloud.com/artist/" + "x".repeat(2048)))
    }

    @Test
    fun trackParserKeepsNumericIdentityAndOnlyAcceptsProgressiveMp3() {
        val parsed = parseSoundCloudTrack(
            Json.parseToJsonElement(
                """{"kind":"track","id":123,"title":"Track","duration":120000,"user":{"username":"Artist"},"media":{"transcodings":[{"url":"https://api-v2.soundcloud.com/media/123","format":{"protocol":"progressive","mime_type":"audio/mpeg"}},{"url":"https://api-v2.soundcloud.com/media/456","format":{"protocol":"hls","mime_type":"audio/mp4"}}]}}""",
            ).jsonObject,
        )

        assertEquals("123", parsed?.id)
        assertEquals("Artist", parsed?.artist)
        assertEquals("https://api-v2.soundcloud.com/media/123", parsed?.progressiveLookupUrl)
    }

    @Test
    fun trackParserRejectsFragmentedLookupUrls() {
        val parsed = parseSoundCloudTrack(
            Json.parseToJsonElement(
                """{"kind":"track","id":123,"title":"Track","media":{"transcodings":[{"url":"https://api-v2.soundcloud.com/media/123#fragment","format":{"protocol":"progressive","mime_type":"audio/mpeg"}}]}}""",
            ).jsonObject,
        )

        assertNull(parsed?.progressiveLookupUrl)
    }

    @Test
    fun trackParserRejectsSnippedAndOversizedMetadata() {
        val snipped = parseSoundCloudTrack(
            Json.parseToJsonElement(
                """{"kind":"track","id":123,"title":"Track","policy":"SNIP","media":{"transcodings":[{"url":"https://api-v2.soundcloud.com/media/123/preview/stream/progressive","snipped":true,"format":{"protocol":"progressive","mime_type":"audio/mpeg"}}]}}""",
            ).jsonObject,
        )
        val oversized = parseSoundCloudTrack(
            Json.parseToJsonElement("""{"id":123,"title":"${"x".repeat(513)}"}""").jsonObject,
        )

        assertEquals(true, snipped?.blocked)
        assertNull(snipped?.progressiveLookupUrl)
        assertNull(oversized)
        assertEquals(false, hasSafeJsonNesting("[".repeat(65) + "]".repeat(65)))
        assertEquals(true, hasSafeJsonNesting("{\"value\":\"[[[\"}"))
    }
}
