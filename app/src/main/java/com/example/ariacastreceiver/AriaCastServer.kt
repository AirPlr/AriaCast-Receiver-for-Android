package com.example.ariacastreceiver

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class AriaCastServer(
    private val context: Context,
    private val config: ServerConfig = ServerConfig()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: EmbeddedServer<*, *>? = null
    private var jmdns: JmDNS? = null
    private val audioPlayer = AudioPlayer(config.audio)
    
    private val _metadata = MutableStateFlow(Metadata())
    val metadata = _metadata.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    
    private var artworkBytes: ByteArray? = null
    private var lastRemoteArtworkUrl: String? = null
    
    private val metadataClients = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val listeningClients = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val controlClients = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    private val multicastLock: WifiManager.MulticastLock by lazy {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.createMulticastLock("AriaCastLock")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun start() {
        multicastLock.acquire()
        
        server = embeddedServer(Netty, port = config.streamingPort, host = "0.0.0.0") {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(json)
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }
            
            routing {
                // Audio Input
                webSocket("/audio") {
                    Log.i("AriaCastServer", "Audio source connected")
                    try {
                        sendSerialized(HandshakeResponse(
                            status = "READY",
                            sampleRate = config.audio.sampleRate,
                            channels = config.audio.channels,
                            frameSize = config.audio.frameSize
                        ))
                    } catch (e: Exception) {
                        Log.e("AriaCastServer", "Handshake failed: ${e.message}")
                    }
                    
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Binary) {
                                val data = frame.data
                                audioPlayer.enqueueFrame(data)
                                // Broadcast to web listeners
                                listeningClients.forEach { client ->
                                    scope.launch {
                                        try { client.send(Frame.Binary(true, data)) } catch (e: Exception) {}
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AriaCastServer", "Audio socket error: ${e.message}")
                    } finally {
                        audioPlayer.stop()
                        Log.i("AriaCastServer", "Audio source disconnected")
                    }
                }
                
                // Control WebSocket
                webSocket("/control") {
                    controlClients.add(this)
                    Log.i("AriaCastServer", "Control client connected")
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                try {
                                    val command = json.decodeFromString<ControlMessage>(text)
                                    handleCommand(command)
                                } catch (e: Exception) {
                                    Log.e("AriaCastServer", "Error decoding command: ${e.message}")
                                }
                            }
                        }
                    } finally {
                        controlClients.remove(this)
                        Log.i("AriaCastServer", "Control client disconnected")
                    }
                }

                // Metadata WebSocket
                webSocket("/metadata") {
                    metadataClients.add(this)
                    try {
                        sendSerialized(MetadataMessage("metadata", _metadata.value))
                    } catch (e: Exception) {}
                    
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                try {
                                    val root = json.parseToJsonElement(text).jsonObject
                                    if (root["type"]?.jsonPrimitive?.content == "update") {
                                        root["data"]?.jsonObject?.let { updateMetadataFromJson(it) }
                                    }
                                } catch (e: Exception) {
                                    Log.e("AriaCastServer", "Error handling metadata ws update: ${e.message}")
                                }
                            }
                        }
                    } finally {
                        metadataClients.remove(this)
                    }
                }

                // Web Listen WebSocket
                webSocket("/listen") {
                    listeningClients.add(this)
                    try {
                        sendSerialized(mapOf(
                            "sample_rate" to config.audio.sampleRate,
                            "channels" to config.audio.channels
                        ))
                    } catch (e: Exception) {}
                    
                    try {
                        for (frame in incoming) { /* Keep alive */ }
                    } finally {
                        listeningClients.remove(this)
                    }
                }

                // HTTP Endpoints
                get("/artwork") {
                    artworkBytes?.let {
                        call.respondBytes(it, ContentType.Image.JPEG)
                    } ?: call.respond(HttpStatusCode.NotFound)
                }

                post("/metadata") {
                    try {
                        val body = call.receive<JsonObject>()
                        val data = if (body.containsKey("data")) body["data"]?.jsonObject else body
                        data?.let { updateMetadataFromJson(it) }
                        call.respond(mapOf("success" to true))
                    } catch (e: Exception) {
                        Log.e("AriaCastServer", "POST /metadata error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                    }
                }
                
                get("/api/metadata") {
                    call.respond(_metadata.value)
                }

                post("/api/command") {
                    try {
                        val cmd = call.receive<ControlMessage>()
                        handleCommand(cmd)
                        call.respond(mapOf("success" to true))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                    }
                }
            }
        }.start(wait = false)
        
        startDiscovery()
        startUdpDiscovery()
        Log.i("AriaCastServer", "Server started on port ${config.streamingPort}")
    }

    fun handleCommand(cmd: ControlMessage) {
        val action = cmd.action ?: cmd.command
        Log.i("AriaCastServer", "Handling command: $action")
        when (action) {
            "play" -> {
                _isPlaying.value = true
                updateMetadata(_metadata.value.copy(isPlaying = true))
            }
            "pause" -> {
                _isPlaying.value = false
                updateMetadata(_metadata.value.copy(isPlaying = false))
            }
            "play_pause" -> {
                val newState = !_isPlaying.value
                _isPlaying.value = newState
                updateMetadata(_metadata.value.copy(isPlaying = newState))
            }
            "stop" -> {
                _isPlaying.value = false
                audioPlayer.stop()
                updateMetadata(_metadata.value.copy(isPlaying = false))
            }
        }
        
        // Broadcast to all control clients
        scope.launch {
            controlClients.forEach { client ->
                try { client.sendSerialized(cmd) } catch (e: Exception) {}
            }
        }
    }

    private fun updateMetadataFromJson(data: JsonObject) {
        val current = _metadata.value
        
        val newTitle = data["title"]?.jsonPrimitive?.contentOrNull ?: current.title
        val newArtist = data["artist"]?.jsonPrimitive?.contentOrNull ?: current.artist
        val newAlbum = data["album"]?.jsonPrimitive?.contentOrNull ?: current.album
        val newArtworkUrl = data["artwork_url"]?.jsonPrimitive?.contentOrNull 
            ?: data["artworkUrl"]?.jsonPrimitive?.contentOrNull 
            ?: current.artworkUrl
        
        val newDuration = data["duration_ms"]?.jsonPrimitive?.longOrNull 
            ?: data["durationMs"]?.jsonPrimitive?.longOrNull 
            ?: current.durationMs
            
        val newPosition = data["position_ms"]?.jsonPrimitive?.longOrNull 
            ?: data["positionMs"]?.jsonPrimitive?.longOrNull 
            ?: current.positionMs
            
        val newIsPlaying = data["is_playing"]?.jsonPrimitive?.booleanOrNull 
            ?: data["isPlaying"]?.jsonPrimitive?.booleanOrNull 
            ?: current.isPlaying

        val updated = current.copy(
            title = newTitle,
            artist = newArtist,
            album = newAlbum,
            artworkUrl = newArtworkUrl,
            durationMs = newDuration,
            positionMs = newPosition,
            isPlaying = newIsPlaying
        )
        
        updateMetadata(updated)
    }

    private fun updateMetadata(meta: Metadata) {
        _metadata.value = meta
        _isPlaying.value = meta.isPlaying
        
        if (meta.artworkUrl != null && meta.artworkUrl != lastRemoteArtworkUrl && meta.artworkUrl.startsWith("http")) {
            lastRemoteArtworkUrl = meta.artworkUrl
            downloadArtwork(meta.artworkUrl)
        }
        
        // Broadcast
        scope.launch {
            val msg = MetadataMessage("metadata", meta)
            metadataClients.forEach { client ->
                try { client.sendSerialized(msg) } catch (e: Exception) {}
            }
        }
    }

    private fun downloadArtwork(url: String) {
        scope.launch {
            try {
                val bytes = java.net.URL(url).readBytes()
                artworkBytes = bytes
            } catch (e: Exception) {
                Log.e("AriaCastServer", "Failed to download artwork: ${e.message}")
            }
        }
    }

    private fun startDiscovery() {
        scope.launch(Dispatchers.IO) {
            try {
                val ip = getLocalIpAddress()
                Log.i("AriaCastServer", "Binding JmDNS to IP: $ip")
                jmdns = JmDNS.create(InetAddress.getByName(ip))
                val serviceInfo = ServiceInfo.create(
                    "_audiocast._tcp.local.",
                    "${config.serverName}._audiocast._tcp.local.",
                    config.streamingPort,
                    0, 0,
                    mapOf(
                        "version" to "1.0",
                        "samplerate" to config.audio.sampleRate.toString(),
                        "channels" to config.audio.channels.toString()
                    )
                )
                jmdns?.registerService(serviceInfo)
                Log.i("AriaCastServer", "mDNS service registered: ${config.serverName}")
            } catch (e: Exception) {
                Log.e("AriaCastServer", "mDNS error: ${e.message}")
            }
        }
    }

    private fun startUdpDiscovery() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(config.discoveryPort)
                socket.broadcast = true
                val buffer = ByteArray(1024)
                Log.i("AriaCastServer", "UDP Discovery listening on port ${config.discoveryPort}")
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val data = String(packet.data, 0, packet.length).trim()
                        if (data == "DISCOVER_AUDIOCAST") {
                            Log.i("AriaCastServer", "Discovery request from ${packet.address}")
                            val response = """
                                {
                                    "server_name": "${config.serverName}",
                                    "ip": "${getLocalIpAddress()}",
                                    "port": ${config.streamingPort},
                                    "samplerate": ${config.audio.sampleRate},
                                    "channels": ${config.audio.channels}
                                }
                            """.trimIndent()
                            val respBytes = response.toByteArray()
                            val respPacket = DatagramPacket(respBytes, respBytes.size, packet.address, packet.port)
                            socket.send(respPacket)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.e("AriaCastServer", "UDP Discovery error: ${e.message}")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.address.size == 4) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AriaCastServer", "Error getting IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    fun stop() {
        if (multicastLock.isHeld) {
            multicastLock.release()
        }
        server?.stop(1000, 1000)
        jmdns?.unregisterAllServices()
        jmdns?.close()
        audioPlayer.stop()
        scope.cancel()
    }
}
