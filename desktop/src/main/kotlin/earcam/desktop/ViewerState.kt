package earcam.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import earcam.protocol.CameraAddress
import earcam.protocol.CameraClient
import earcam.protocol.Frame
import earcam.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences

/**
 * Connection, settings and the current picture. Compose reads the state properties.
 *
 * @param commandLineAddress camera IP from the command line. It overrides the saved setting
 *   for this run only.
 */
class ViewerState(private val scope: CoroutineScope, commandLineAddress: String? = null) {
    private val prefs = Preferences.userRoot().node("earcam")

    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var status by mutableStateOf("Starting…")
        private set
    var streaming by mutableStateOf(false)
        private set
    var fps by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Device tilt from the last frame, in degrees. */
    var sensorRotation by mutableStateOf<Float?>(null)
        private set

    var manualRotation by mutableStateOf(prefs.getInt("rotation", 0))
        private set
    var mirrored by mutableStateOf(prefs.getBoolean("mirror", false))
        private set
    var autoRotate by mutableStateOf(prefs.getBoolean("autoRotate", false))
        private set

    /** Show the picture in a circle, so rotating it shows no corners. */
    var circle by mutableStateOf(prefs.getBoolean("circle", true))
        private set

    /** Camera IP typed by the user. Empty means "find it automatically". */
    var addressOverride by mutableStateOf(commandLineAddress ?: prefs.get("address", ""))
        private set

    private val latest = MutableStateFlow<Frame?>(null)
    private val frameCount = AtomicInteger()
    private var client: CameraClient? = null

    /** Total rotation to draw with: manual setting plus sensor angle if auto-rotate is on. */
    val rotation: Float
        get() = manualRotation + (if (autoRotate) sensorRotation ?: 0f else 0f)

    fun start() {
        scope.launch(Dispatchers.Default) {
            // collectLatest drops frames that arrive while one is being decoded.
            latest.filterNotNull().collectLatest { frame ->
                val bmp = try {
                    Image.makeFromEncoded(frame.jpeg).toComposeImageBitmap()
                } catch (_: Exception) {
                    return@collectLatest // Corrupt frame. Skip it.
                }
                withContext(Dispatchers.Main) {
                    image = bmp
                    sensorRotation = frame.rotationDeg
                }
            }
        }
        scope.launch {
            while (true) {
                delay(1000)
                fps = frameCount.getAndSet(0)
            }
        }
        connect()
    }

    private fun connect() {
        client?.close()
        val override = addressOverride.trim()
        val c = CameraClient(
            resolveCamera = {
                if (override.isNotEmpty()) InetAddress.getByName(override) else CameraAddress.guess()
            },
            listener = object : CameraClient.Listener {
                override fun onFrame(frame: Frame, protocol: Protocol) {
                    frameCount.incrementAndGet()
                    latest.value = frame
                }

                override fun onStatus(status: CameraClient.Status) {
                    scope.launch(Dispatchers.Main) { showStatus(status) }
                }

                override fun onWarning(message: String) {
                    System.err.println("earcam: $message")
                }
            },
        )
        client = c
        c.start()
    }

    private fun showStatus(s: CameraClient.Status) {
        streaming = s is CameraClient.Status.Streaming
        status = when (s) {
            is CameraClient.Status.Searching -> "Looking for camera at ${s.address.hostAddress}…"
            is CameraClient.Status.Streaming -> "${s.protocol.displayName} · ${s.address.hostAddress}"
            is CameraClient.Status.Stopped -> "Stopped"
        }
    }

    fun setAddress(text: String) {
        addressOverride = text.trim()
        prefs.put("address", addressOverride)
        image = null
        connect()
    }

    fun rotate() {
        manualRotation = (manualRotation + 90) % 360
        prefs.putInt("rotation", manualRotation)
    }

    fun toggleMirror() {
        mirrored = !mirrored
        prefs.putBoolean("mirror", mirrored)
    }

    fun toggleAutoRotate() {
        autoRotate = !autoRotate
        prefs.putBoolean("autoRotate", autoRotate)
    }

    fun toggleCircle() {
        circle = !circle
        prefs.putBoolean("circle", circle)
    }

    /** Saves the current frame, as sent by the camera, to ~/Pictures/EarCam. */
    fun snapshot() {
        val frame = latest.value ?: return flash("No picture yet")
        scope.launch(Dispatchers.IO) {
            val msg = try {
                val home = File(System.getProperty("user.home"))
                val pictures = File(home, "Pictures").takeIf { it.isDirectory } ?: home
                val dir = File(pictures, "EarCam").apply { mkdirs() }
                val name = "earcam-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".jpg"
                File(dir, name).writeBytes(frame.jpeg)
                "Saved ${File(dir, name)}"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
            withContext(Dispatchers.Main) { flash(msg) }
        }
    }

    private fun flash(text: String) {
        message = text
        scope.launch {
            delay(3000)
            if (message == text) message = null
        }
    }

    fun close() {
        client?.close()
        client = null
    }
}
