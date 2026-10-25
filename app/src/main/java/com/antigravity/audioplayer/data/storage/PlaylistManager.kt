package com.antigravity.audioplayer.data.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

@Stable
class PlaylistManager private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("playlists_prefs_v2", Context.MODE_PRIVATE)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    init {
        _playlists.value = loadPlaylists()
        _favoriteIds.value = loadFavorites()
    }

    fun isFavorite(trackId: String) = _favoriteIds.value.contains(trackId)

    fun toggleFavorite(trackId: String): Boolean {
        val current = _favoriteIds.value.toMutableSet()
        val isFav = if (current.contains(trackId)) {
            current.remove(trackId)
            false
        } else {
            current.add(trackId)
            true
        }
        _favoriteIds.value = current
        prefs.edit().putStringSet("favorites_v2", current).apply()
        return isFav
    }

    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(
            id = "playlist_${System.currentTimeMillis()}",
            name = name.trim(),
            trackIds = emptyList()
        )
        val updated = _playlists.value + playlist
        _playlists.value = updated
        savePlaylists(updated)
        return playlist
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        val updated = _playlists.value.map { if (it.id == playlistId) it.copy(name = newName.trim()) else it }
        _playlists.value = updated
        savePlaylists(updated)
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val updated = _playlists.value.map { p ->
            if (p.id == playlistId && !p.trackIds.contains(trackId)) {
                p.copy(trackIds = p.trackIds + trackId)
            } else p
        }
        _playlists.value = updated
        savePlaylists(updated)
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val updated = _playlists.value.map { p ->
            if (p.id == playlistId) p.copy(trackIds = p.trackIds - trackId) else p
        }
        _playlists.value = updated
        savePlaylists(updated)
    }

    fun deletePlaylist(playlistId: String) {
        val updated = _playlists.value.filter { it.id != playlistId }
        _playlists.value = updated
        savePlaylists(updated)
    }

    private fun loadFavorites(): Set<String> {
        return prefs.getStringSet("favorites_v2", emptySet()) ?: emptySet()
    }

    private fun loadPlaylists(): List<Playlist> {
        val json = prefs.getString("playlists_json_v2", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val idsArr = obj.getJSONArray("trackIds")
                Playlist(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    trackIds = (0 until idsArr.length()).map { j -> idsArr.getString(j) },
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun savePlaylists(playlists: List<Playlist>) {
        val arr = JSONArray()
        playlists.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            val idsArr = JSONArray()
            p.trackIds.forEach { idsArr.put(it) }
            obj.put("trackIds", idsArr)
            obj.put("createdAt", p.createdAt)
            arr.put(obj)
        }
        prefs.edit().putString("playlists_json_v2", arr.toString()).apply()
    }

    companion object {
        @Volatile private var INSTANCE: PlaylistManager? = null
        fun getInstance(context: Context): PlaylistManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaylistManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
