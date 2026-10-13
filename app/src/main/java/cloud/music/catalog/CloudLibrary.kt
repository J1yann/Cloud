package cloud.music.catalog

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import top.wangchenyan.common.CommonApp
import java.util.UUID

data class SavedPlaylist(val id: String, var name: String, val tracks: MutableList<String> = mutableListOf())
data class LibraryState(
    val tracks: MutableMap<String, CloudTrack> = mutableMapOf(),
    val likes: MutableList<String> = mutableListOf(),
    val history: MutableList<String> = mutableListOf(),
    val playlists: MutableList<SavedPlaylist> = mutableListOf(),
)

/** Local library: no provider login or remote playlist mutation. */
object CloudLibrary {
    private val preferences by lazy { CommonApp.app.getSharedPreferences("cloud_library_v2", Context.MODE_PRIVATE) }
    private val gson = Gson()
    private val state: LibraryState by lazy {
        runCatching { gson.fromJson(preferences.getString("state", ""), LibraryState::class.java) }
            .getOrNull() ?: LibraryState()
    }
    val revision = MutableStateFlow(0)
    @Synchronized private fun save() {
        preferences.edit().putString("state", gson.toJson(state)).apply()
        revision.value += 1
    }
    fun searchHistory(): List<String> = runCatching { gson.fromJson(preferences.getString("search_history", "[]"), Array<String>::class.java).toList() }.getOrDefault(emptyList())
    fun rememberSearch(query: String) {
        val value = query.trim()
        if (value.isBlank()) return
        preferences.edit().putString("search_history", gson.toJson((listOf(value) + searchHistory().filter { it != value }).take(20))).apply()
    }
    @Synchronized fun remember(track: CloudTrack) { state.tracks[track.id] = track; save() }
    @Synchronized fun track(id: String): CloudTrack? = state.tracks[id]
    @Synchronized fun played(id: String) {
        if (state.tracks[id] == null) return
        state.history.remove(id); state.history.add(0, id)
        while (state.history.size > 100) state.history.removeAt(state.history.lastIndex)
        save()
    }
    @Synchronized fun liked(id: String): Boolean = id in state.likes
    @Synchronized fun toggleLike(id: String) {
        if (!state.likes.remove(id)) state.likes.add(0, id)
        save()
    }
    @Synchronized fun likes(): List<CloudTrack> = state.likes.mapNotNull(state.tracks::get)
    @Synchronized fun history(): List<CloudTrack> = state.history.mapNotNull(state.tracks::get)
    @Synchronized fun playlists(): List<SavedPlaylist> = state.playlists.map { it.copy(tracks = it.tracks.toMutableList()) }
    @Synchronized fun create(name: String): SavedPlaylist {
        require(name.trim().isNotEmpty())
        val item = SavedPlaylist(UUID.randomUUID().toString(), name.trim())
        state.playlists.add(item); save(); return item
    }
    @Synchronized fun saveCollection(name: String, tracks: List<CloudTrack>): SavedPlaylist {
        val item = SavedPlaylist(UUID.randomUUID().toString(), name.trim().ifBlank { "Saved collection" })
        tracks.forEach { track ->
            state.tracks[track.id] = track
            if (track.id !in item.tracks) item.tracks.add(track.id)
        }
        state.playlists.add(item); save(); return item
    }
    @Synchronized fun rename(id: String, name: String) {
        if (name.trim().isEmpty()) return
        state.playlists.find { it.id == id }?.name = name.trim(); save()
    }
    @Synchronized fun delete(id: String) { state.playlists.removeAll { it.id == id }; save() }
    @Synchronized fun add(id: String, track: CloudTrack) {
        state.tracks[track.id] = track
        state.playlists.find { it.id == id }?.tracks?.let { if (track.id !in it) it.add(track.id) }
        save()
    }
    @Synchronized fun remove(id: String, track: String) {
        state.playlists.find { it.id == id }?.tracks?.remove(track); save()
    }
    @Synchronized fun playlistTracks(id: String): List<CloudTrack> =
        state.playlists.find { it.id == id }?.tracks?.mapNotNull(state.tracks::get) ?: emptyList()
}
