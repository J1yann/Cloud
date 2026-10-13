package cloud.music.catalog

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException

/** Original parser for the structured renderers returned by YouTube Music's web catalogue. */
internal class YouTubeMusicParser {
    fun hasContent(data: JsonObject): Boolean = listOf(
        "contents", "continuationContents", "onResponseReceivedActions", "onResponseReceivedEndpoints",
    ).any { data.has(it) }

    fun tracks(data: JsonObject): CloudPage<CloudTrack> = CloudPage(
        items(data).mapNotNull(::track), continuation(data),
    )

    fun artists(data: JsonObject): CloudPage<CloudArtist> = CloudPage(
        items(data).mapNotNull(::artist).distinctBy { it.id }, continuation(data),
    )

    fun playlists(data: JsonObject): CloudPage<CloudPlaylist> = CloudPage(
        items(data).mapNotNull(::playlist).distinctBy { it.id }, continuation(data),
    )

    fun sections(data: JsonObject): CloudPage<CloudSection> {
        val shelves = renderers(data, setOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicShelfContinuation", "musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation", "gridRenderer"))
        val sections = shelves.mapNotNull { shelf ->
            val rows = items(shelf)
            val tracks = rows.mapNotNull(::track)
            val playlists = rows.mapNotNull(::playlist)
            val artists = rows.mapNotNull(::artist)
            val destinations = renderers(shelf, setOf("musicNavigationButtonRenderer")).mapNotNull navigation@ { button ->
                val browse = button.objectValue("clickCommand")?.objectValue("browseEndpoint") ?: return@navigation null
                val id = browse.string("browseId")
                if (id.isBlank()) return@navigation null
                CloudDestination(id, text(button.get("buttonText")), browse.string("params"),
                    button.objectValue("solid")?.get("leftStripeColor")?.asLong?.toInt() ?: 0)
            }
            if (tracks.isEmpty() && playlists.isEmpty() && artists.isEmpty() && destinations.isEmpty()) return@mapNotNull null
            val title = text(shelf.get("title")).ifBlank { firstText(shelf.get("header"), "title").ifBlank { "Discover" } }
            val more = shelf.objectValue("bottomEndpoint")?.objectValue("browseEndpoint")
                ?: firstObject(shelf.get("header"), "moreContentButton")?.let { firstObject(it, "browseEndpoint") }
            CloudSection(
                title,
                CloudPage(playlists, null), CloudPage(tracks, null), CloudPage(artists, null),
                destinations, more?.let { CloudDestination(it.string("browseId"), title, it.string("params")) },
            )
        }
        if (sections.isEmpty()) {
            // Some artist/playlist destinations return bare continuation items rather than shelves.
            val tracks = tracks(data)
            val playlists = playlists(data)
            if (tracks.items.isNotEmpty() || playlists.items.isNotEmpty()) {
                return CloudPage(listOf(CloudSection("Discover", playlists = playlists, tracks = tracks)), continuation(data))
            }
        }
        if (sections.isEmpty() && shelves.isEmpty()) {
            throw IOException("YouTube Music's home layout changed. Try again later.")
        }
        return CloudPage(sections, continuation(data))
    }

    fun suggestions(data: JsonObject): List<String> =
        renderers(data, setOf("searchSuggestionRenderer", "musicSearchSuggestionRenderer"))
            .map { text(it.get("suggestion")) }.filter { it.isNotBlank() }.distinct()

    fun relatedEndpoint(data: JsonObject): JsonObject? = renderers(data, setOf("tabRenderer"))
        .firstOrNull { it.string("title") == "Related" }
        ?.objectValue("endpoint")?.objectValue("browseEndpoint")?.requestBody()

    fun shelfEndpoint(data: JsonObject, title: String): JsonObject? {
        val shelf = shelfData(data, title) ?: return null
        val endpoint = shelf.objectValue("bottomEndpoint")?.objectValue("browseEndpoint")
            ?: firstObject(shelf.get("header"), "moreContentButton")?.let { button ->
                firstObject(button, "browseEndpoint")
            }
        return endpoint?.requestBody()
    }

    fun shelfData(data: JsonObject, title: String): JsonObject? =
        renderers(data, setOf("musicShelfRenderer", "musicCarouselShelfRenderer"))
            .firstOrNull { text(it.get("title")) == title || firstText(it.get("header"), "title") == title }

    private fun items(data: JsonElement): List<JsonObject> = renderers(data, setOf(
        "musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "playlistPanelVideoRenderer",
    ))

    private fun track(data: JsonObject): CloudTrack? {
        val browse = endpoint(data)?.objectValue("browseEndpoint")
        if (browse != null) return null // Album/artist menus also contain watch endpoints; they are not songs.
        val id = data.string("videoId").ifBlank {
            data.objectValue("playlistItemData")?.string("videoId").orEmpty()
        }.ifBlank {
            endpoint(data)?.objectValue("watchEndpoint")?.string("videoId").orEmpty()
        }.ifBlank {
            firstObject(data.get("overlay"), "watchEndpoint")?.string("videoId").orEmpty()
        }
        if (!VIDEO_ID.matches(id)) return null
        val title = title(data)
        if (title.isBlank()) return null
        val runs = metadataRuns(data)
        val artistRuns = runs.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }
        val artistName = artistRuns.map { it.string("text") }.distinct().joinToString(", ")
            .ifBlank { metadataParts(data).firstOrNull { meaningfulCredit(it) }.orEmpty() }
            .ifBlank { "Unknown artist" }
        val artistId = artistRuns.firstOrNull()?.objectValue("navigationEndpoint")
            ?.objectValue("browseEndpoint")?.string("browseId").orEmpty()
        val artist = CloudArtist(artistId, artistRuns.firstOrNull()?.string("text") ?: artistName,
            url = if (artistId.isBlank()) "" else "https://music.youtube.com/channel/$artistId")
        val album = runs.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.string("text").orEmpty()
        val durationText = text(data.get("lengthText")).ifBlank {
            data.getAsJsonArray("fixedColumns")?.mapNotNull { column ->
                column.asObject()?.objectValue("musicResponsiveListItemFixedColumnRenderer")?.get("text")?.let(::text)
            }?.firstOrNull { DURATION.matches(it) }.orEmpty()
        }.ifBlank { metadataParts(data).firstOrNull { DURATION.matches(it) }.orEmpty() }
        val explicit = renderers(data.get("badges"), setOf("musicInlineBadgeRenderer"))
            .any { it.objectValue("icon")?.string("iconType") == "MUSIC_EXPLICIT_BADGE" }
        val available = data.get("isPlayable")?.takeIf { it.isJsonPrimitive }?.asBoolean != false &&
            data.string("musicItemRendererDisplayPolicy") != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
        return CloudTrack(
            id, title, artistName, artist, artwork(data).ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
            duration(durationText), album = album, available = available, explicit = explicit,
        )
    }

    private fun artist(data: JsonObject): CloudArtist? {
        val browse = endpoint(data)?.objectValue("browseEndpoint") ?: return null
        if (pageType(browse) != "MUSIC_PAGE_TYPE_ARTIST" && !browse.string("browseId").startsWith("UC")) return null
        val id = browse.string("browseId")
        val title = title(data)
        if (id.isBlank() || title.isBlank()) return null
        return CloudArtist(id, title, artwork(data), "https://music.youtube.com/channel/$id", metadataParts(data).joinToString(" · "))
    }

    private fun playlist(data: JsonObject): CloudPlaylist? {
        val browse = endpoint(data)?.objectValue("browseEndpoint") ?: return null
        val id = browse.string("browseId")
        val type = pageType(browse)
        val album = type == "MUSIC_PAGE_TYPE_ALBUM" || id.startsWith("MPRE")
        if (!album && type != "MUSIC_PAGE_TYPE_PLAYLIST" && !id.startsWith("VL")) return null
        val title = title(data)
        if (id.isBlank() || title.isBlank()) return null
        val parts = metadataParts(data)
        val creator = metadataRuns(data).filter {
            it.objectValue("navigationEndpoint")?.objectValue("browseEndpoint") != null
        }.map { it.string("text") }.firstOrNull { meaningfulCredit(it) }
            ?: parts.firstOrNull { meaningfulCredit(it) } ?: "YouTube Music"
        val count = parts.firstNotNullOfOrNull { value ->
            Regex("([0-9][0-9,]*)\\s+(?:songs|tracks)").find(value)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        } ?: 0
        val url = if (album) "https://music.youtube.com/browse/$id"
            else "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}"
        return CloudPlaylist(id, title, creator, artwork(data), url, count, album)
    }

    private fun endpoint(data: JsonObject): JsonObject? = data.objectValue("navigationEndpoint")
        ?: titleElement(data)?.asObject()?.getAsJsonArray("runs")?.firstOrNull()?.asObject()?.objectValue("navigationEndpoint")

    private fun titleElement(data: JsonObject): JsonElement? = data.get("title")
        ?: data.getAsJsonArray("flexColumns")?.firstOrNull()?.asObject()
            ?.objectValue("musicResponsiveListItemFlexColumnRenderer")?.get("text")

    private fun title(data: JsonObject): String = text(titleElement(data))

    private fun metadataElements(data: JsonObject): List<JsonElement> {
        data.get("longBylineText")?.let { return listOf(it) }
        data.get("subtitle")?.let { return listOf(it) }
        return data.getAsJsonArray("flexColumns")?.drop(1)?.mapNotNull {
            it.asObject()?.objectValue("musicResponsiveListItemFlexColumnRenderer")?.get("text")
        }.orEmpty()
    }

    private fun metadataRuns(data: JsonObject): List<JsonObject> = metadataElements(data)
        .flatMap { it.asObject()?.getAsJsonArray("runs")?.mapNotNull { run -> run.asObject() }.orEmpty() }

    private fun metadataParts(data: JsonObject): List<String> = metadataElements(data)
        .flatMap { text(it).split('•', '·') }.map { it.trim() }.filter { it.isNotBlank() }

    private fun meaningfulCredit(value: String): Boolean = value.isNotBlank() &&
        value !in setOf("Song", "Video", "Artist", "Album", "Single", "EP", "Playlist", "Chart") &&
        !DURATION.matches(value) && !Regex("^[0-9,.]+[KMB]?\\s+(songs|tracks|views|plays|subscribers|monthly audience).*$", RegexOption.IGNORE_CASE).matches(value) &&
        !Regex("^\\d{4}$").matches(value)

    private fun pageType(data: JsonObject): String {
        val browse = data.objectValue("navigationEndpoint")?.objectValue("browseEndpoint") ?: data
        return browse.objectValue("browseEndpointContextSupportedConfigs")
            ?.objectValue("browseEndpointContextMusicConfig")?.string("pageType").orEmpty()
    }

    private fun artwork(data: JsonObject): String {
        val base = data.get("thumbnail") ?: data.get("thumbnailRenderer") ?: return ""
        return thumbnailArrays(base).flatMap { it }.mapNotNull { it.asObject() }
            .maxByOrNull { it.get("width")?.takeIf { width -> width.isJsonPrimitive }?.asInt ?: 0 }
            ?.string("url").orEmpty().let(::largeArtwork)
    }

    private fun largeArtwork(url: String): String {
        if (!(url.contains("googleusercontent.com/") || url.contains("ggpht.com/"))) return url
        // Keep signed/encoded image paths and crop/quality flags intact; only increase size directives.
        return url.replace(Regex("([=-])w(\\d+)")) { match ->
            "${match.groupValues[1]}w${maxOf(720, match.groupValues[2].toInt())}"
        }.replace(Regex("([=-])h(\\d+)")) { match ->
            "${match.groupValues[1]}h${maxOf(720, match.groupValues[2].toInt())}"
        }.replace(Regex("=s(\\d+)")) { match -> "=s${maxOf(720, match.groupValues[1].toInt())}" }
    }

    private fun thumbnailArrays(data: JsonElement?): List<List<JsonElement>> {
        val result = mutableListOf<List<JsonElement>>()
        walk(data) { key, value ->
            if (key == "thumbnails" && value.isJsonArray) result.add(value.asJsonArray.toList())
        }
        return result
    }

    private fun duration(value: String): Long = if (DURATION.matches(value)) {
        value.split(':').fold(0L) { seconds, part -> seconds * 60 + part.toLong() } * 1000
    } else 0L

    private fun continuation(data: JsonElement): String? {
        val next = firstObject(data, "nextContinuationData") ?: firstObject(data, "nextRadioContinuationData")
        return next?.string("continuation")?.takeIf { it.isNotBlank() }
            ?: firstObject(data, "continuationCommand")?.string("token")?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.requestBody(): JsonObject = JsonObject().apply {
        addProperty("browseId", this@requestBody.string("browseId"))
        this@requestBody.string("params").takeIf { it.isNotBlank() }?.let { addProperty("params", it) }
    }

    private fun text(data: JsonElement?): String {
        val value = data?.asObject() ?: return data?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        value.get("simpleText")?.let { return it.asString }
        return value.getAsJsonArray("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }.orEmpty()
    }

    private fun firstText(data: JsonElement?, key: String): String {
        var found = ""
        walk(data) { name, value -> if (found.isBlank() && name == key) found = text(value) }
        return found
    }

    private fun firstObject(data: JsonElement?, key: String): JsonObject? {
        var found: JsonObject? = null
        walk(data) { name, value -> if (found == null && name == key) found = value.asObject() }
        return found
    }

    private fun renderers(data: JsonElement?, names: Set<String>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        fun visit(node: JsonElement?) {
            when {
                node == null -> Unit
                node.isJsonArray -> node.asJsonArray.forEach(::visit)
                node.isJsonObject -> node.asJsonObject.entrySet().forEach { (key, value) ->
                    if (key in names) value.asObject()?.let(result::add) else visit(value)
                }
            }
        }
        visit(data)
        return result
    }

    private fun walk(data: JsonElement?, action: (String, JsonElement) -> Unit) {
        when {
            data == null -> Unit
            data.isJsonArray -> data.asJsonArray.forEach { walk(it, action) }
            data.isJsonObject -> data.asJsonObject.entrySet().forEach { (key, value) -> action(key, value); walk(value, action) }
        }
    }

    private fun JsonElement.asObject(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.asObject()
    private fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val DURATION = Regex("\\d{1,3}(?::\\d{2}){1,2}")
    }
}
