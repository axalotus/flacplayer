package com.antigravity.audioplayer.desktop.sync

import com.antigravity.audioplayer.desktop.audio.DesktopAudioPlayer
import com.antigravity.audioplayer.desktop.audio.DesktopEqualizer
import com.antigravity.audioplayer.desktop.model.SyncPayload
import com.antigravity.audioplayer.desktop.repository.DesktopLibraryRepository
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

@Serializable
data class SyncServerStatus(
    val serverName: String,
    val ip: String,
    val port: Int,
    val pin: String,
    val version: String,
    val tracksCount: Int
)

object DesktopSyncServer {

    private var serverEngine: EmbeddedServer<*, *>? = null

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    private val _serverIp = MutableStateFlow("127.0.0.1")
    val serverIp = _serverIp.asStateFlow()

    private val _serverPort = MutableStateFlow(8990)
    val serverPort = _serverPort.asStateFlow()

    private val _pairingPin = MutableStateFlow(generateNewPin())
    val pairingPin = _pairingPin.asStateFlow()

    private val _lastSyncMessage = MutableStateFlow<String?>("Готов к синхронизации")
    val lastSyncMessage = _lastSyncMessage.asStateFlow()

    fun startServer(port: Int = 8990) {
        if (_isServerRunning.value) return

        try {
            val localIp = detectLocalIp()
            _serverIp.value = localIp
            _serverPort.value = port

            serverEngine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Options)
                }

                install(ContentNegotiation) {
                    json(Json { prettyPrint = true; ignoreUnknownKeys = true })
                }

                routing {
                    get("/api/status") {
                        val status = SyncServerStatus(
                            serverName = "FlacPlayer PC",
                            ip = _serverIp.value,
                            port = _serverPort.value,
                            pin = _pairingPin.value,
                            version = "1.0.0",
                            tracksCount = DesktopLibraryRepository.tracks.value.size
                        )
                        call.respond(status)
                    }

                    get("/api/sync") {
                        val curTrack = DesktopAudioPlayer.currentTrack.value
                        val payload = SyncPayload(
                            playlists = DesktopLibraryRepository.playlists.value,
                            favoriteIds = DesktopLibraryRepository.favoriteIds.value.toList(),
                            customPresets = DesktopEqualizer.customPresets.value,
                            currentTrackTitle = curTrack?.title,
                            currentTrackArtist = curTrack?.artist,
                            playbackProgressMs = DesktopAudioPlayer.progressMs.value
                        )
                        call.respond(payload)
                    }

                    post("/api/sync") {
                        try {
                            val incoming = call.receive<SyncPayload>()
                            DesktopLibraryRepository.importSyncData(
                                importedPlaylists = incoming.playlists,
                                importedFavorites = incoming.favoriteIds
                            )
                            incoming.customPresets.forEach {
                                DesktopEqualizer.saveCustomPreset(it.name)
                            }
                            _lastSyncMessage.value = "Синхронизировано: ${incoming.playlists.size} плейлистов, ${incoming.favoriteIds.size} избранных"
                            call.respond(mapOf("success" to true, "message" to "Синхронизация завершена успешно"))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Unknown error")))
                        }
                    }

                    post("/api/playback/transfer") {
                        try {
                            val data = call.receive<Map<String, String>>()
                            val title = data["title"]
                            val progress = data["progressMs"]?.toLongOrNull() ?: 0L

                            if (title != null) {
                                val match = DesktopLibraryRepository.tracks.value.firstOrNull {
                                    it.title.equals(title, ignoreCase = true)
                                }
                                if (match != null) {
                                    DesktopAudioPlayer.playTrack(match)
                                    DesktopAudioPlayer.seekTo(progress)
                                    _lastSyncMessage.value = "Воспроизведение перенесено с телефона: ${match.title}"
                                }
                            }
                            call.respond(mapOf("success" to true))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Error")))
                        }
                    }
                }
            }.start(wait = false)

            _isServerRunning.value = true
            _lastSyncMessage.value = "Сервер синхронизации запущен: http://$localIp:$port"
        } catch (e: Exception) {
            e.printStackTrace()
            _lastSyncMessage.value = "Ошибка запуска сервера: ${e.message}"
        }
    }

    fun stopServer() {
        serverEngine?.stop(1000, 2000)
        serverEngine = null
        _isServerRunning.value = false
        _lastSyncMessage.value = "Сервер синхронизации остановлен"
    }

    fun regeneratePin() {
        _pairingPin.value = generateNewPin()
    }

    private fun generateNewPin(): String {
        val num = Random.nextInt(1000, 9999)
        return num.toString()
    }

    private fun detectLocalIp(): String {
        return try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
                socket.localAddress.hostAddress
            }
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
