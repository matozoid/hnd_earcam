package earcam.android

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import earcam.protocol.CameraAddress
import earcam.protocol.CameraClient
import earcam.protocol.Frame
import earcam.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole app. Finds the camera's Wi-Fi network, routes this app's traffic through it,
 * and shows the video.
 *
 * Needs no location, storage or Bluetooth permission. The camera's address comes from the
 * Wi-Fi network's gateway (ACCESS_NETWORK_STATE). Snapshots go through MediaStore.
 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("earcam", MODE_PRIVATE) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private var image by mutableStateOf<ImageBitmap?>(null)
    private var status by mutableStateOf("Connect to the camera's Wi-Fi")
    private var streaming by mutableStateOf(false)
    private var fps by mutableIntStateOf(0)
    private var sensorRotation by mutableStateOf<Float?>(null)
    private var manualRotation by mutableIntStateOf(0)
    private var mirrored by mutableStateOf(false)
    private var autoRotate by mutableStateOf(false)
    private var circle by mutableStateOf(true)
    private var addressOverride by mutableStateOf("")

    private val latest = MutableStateFlow<Frame?>(null)
    private val frameCount = AtomicInteger()
    private var client: CameraClient? = null

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var wifiGateway: InetAddress? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            // The camera's Wi-Fi has no internet, so Android would send our packets over
            // mobile data. Binding the process to the Wi-Fi network prevents that.
            connectivity.bindProcessToNetwork(network)
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            if (network != wifiNetwork) return
            wifiGateway = lp.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
        }

        override fun onLost(network: Network) {
            if (network != wifiNetwork) return
            wifiNetwork = null
            wifiGateway = null
            connectivity.bindProcessToNetwork(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        manualRotation = prefs.getInt("rotation", 0)
        mirrored = prefs.getBoolean("mirror", false)
        autoRotate = prefs.getBoolean("autoRotate", false)
        circle = prefs.getBoolean("circle", true)
        addressOverride = prefs.getString("address", "") ?: ""

        // Matches Wi-Fi networks with or without internet access.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, wifiCallback)

        lifecycleScope.launch(Dispatchers.Default) {
            latest.filterNotNull().collectLatest { frame ->
                val bmp = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: return@collectLatest
                withContext(Dispatchers.Main) {
                    image = bmp.asImageBitmap()
                    sensorRotation = frame.rotationDeg
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                fps = frameCount.getAndSet(0)
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        super.onStop()
        disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivity.unregisterNetworkCallback(wifiCallback)
        connectivity.bindProcessToNetwork(null)
    }

    private fun connect() {
        disconnect()
        val override = addressOverride.trim()
        val c = CameraClient(
            resolveCamera = {
                when {
                    override.isNotEmpty() -> InetAddress.getByName(override)
                    else -> wifiGateway ?: CameraAddress.DEFAULT
                }
            },
            listener = object : CameraClient.Listener {
                override fun onFrame(frame: Frame, protocol: Protocol) {
                    frameCount.incrementAndGet()
                    latest.value = frame
                }

                override fun onStatus(status: CameraClient.Status) {
                    runOnUiThread { showStatus(status) }
                }
            },
        )
        client = c
        c.start()
    }

    private fun disconnect() {
        val c = client ?: return
        client = null
        // close() waits for threads. Keep that off the main thread.
        thread(name = "camera-close") { c.close() }
    }

    private fun showStatus(s: CameraClient.Status) {
        streaming = s is CameraClient.Status.Streaming
        status = when (s) {
            is CameraClient.Status.Searching ->
                if (wifiNetwork == null && addressOverride.isBlank()) "Connect to the camera's Wi-Fi"
                else "Looking for camera at ${s.address.hostAddress}…"
            is CameraClient.Status.Streaming -> "${s.protocol.displayName} · ${s.address.hostAddress}"
            is CameraClient.Status.Stopped -> "Stopped"
        }
    }

    private fun snapshot() {
        val frame = latest.value ?: return toast("No picture yet")
        lifecycleScope.launch(Dispatchers.IO) {
            val name = "earcam-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EarCam")
            }
            val msg = try {
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore refused the file")
                contentResolver.openOutputStream(uri)?.use { it.write(frame.jpeg) }
                "Saved to Pictures/EarCam"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
            withContext(Dispatchers.Main) { toast(msg) }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    @Composable
    private fun Screen() {
        var showControls by remember { mutableStateOf(true) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    showControls = !showControls
                },
        ) {
            val img = image
            if (img != null) {
                val rotation = manualRotation + (if (autoRotate) sensorRotation ?: 0f else 0f)
                VideoView(img, rotation, mirrored, circle, Modifier.fillMaxSize())
            } else {
                Text(status, color = Color.LightGray, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            }
            if (showControls) {
                Text(
                    if (streaming) "$status · $fps fps" else status,
                    color = if (streaming) Color(0xFF8BE28B) else Color.LightGray,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .background(Color(0x99000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Controls(Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    @Composable
    private fun Controls(modifier: Modifier) {
        var addressText by remember { mutableStateOf(addressOverride) }
        Column(
            modifier
                .fillMaxWidth()
                .background(Color(0xCC101010))
                .safeDrawingPadding()
                .padding(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = ::snapshot) { Text("Snapshot") }
                TextButton(onClick = {
                    manualRotation = (manualRotation + 90) % 360
                    prefs.edit().putInt("rotation", manualRotation).apply()
                }) { Text("Rotate $manualRotation°") }
                TextButton(onClick = {
                    mirrored = !mirrored
                    prefs.edit().putBoolean("mirror", mirrored).apply()
                }) { Text(if (mirrored) "Unmirror" else "Mirror") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoRotate, onCheckedChange = {
                        autoRotate = it
                        prefs.edit().putBoolean("autoRotate", it).apply()
                    })
                    Text("Auto", color = Color.White, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = circle, onCheckedChange = {
                        circle = it
                        prefs.edit().putBoolean("circle", it).apply()
                    })
                    Text("Circle", color = Color.White, fontSize = 13.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    singleLine = true,
                    label = { Text("Camera IP (empty = automatic)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { applyAddress(addressText) }),
                    modifier = Modifier.weight(1f),
                )
                if (addressText.trim() != addressOverride) {
                    TextButton(onClick = { applyAddress(addressText) }) { Text("Connect") }
                }
            }
        }
    }

    private fun applyAddress(text: String) {
        addressOverride = text.trim()
        prefs.edit().putString("address", addressOverride).apply()
        image = null
        connect()
    }
}

/**
 * Draws [image] rotated by [rotationDeg] around its centre, optionally mirrored.
 *
 * With [circle] the picture is cut to the largest circle that fits inside it, and that circle
 * fills the view. The mask stays put while the picture turns, so rotation shows no corners.
 * Without it the whole picture is scaled so its rotated bounding box fits the view.
 */
@Composable
private fun VideoView(image: ImageBitmap, rotationDeg: Float, mirrored: Boolean, circle: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val scale = if (circle) {
            min(size.width, size.height) / min(iw, ih)
        } else {
            val rad = Math.toRadians(rotationDeg.toDouble())
            val c = abs(cos(rad)).toFloat()
            val s = abs(sin(rad)).toFloat()
            min(size.width / (iw * c + ih * s), size.height / (iw * s + ih * c))
        }
        val draw: DrawScope.() -> Unit = {
            withTransform({
                translate(size.width / 2, size.height / 2)
                rotate(rotationDeg, pivot = Offset.Zero)
                scale(if (mirrored) -scale else scale, scale, pivot = Offset.Zero)
            }) {
                drawImage(
                    image,
                    dstOffset = IntOffset((-iw / 2).toInt(), (-ih / 2).toInt()),
                    dstSize = IntSize(image.width, image.height),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        if (circle) {
            val mask = Path().apply { addOval(Rect(center, min(size.width, size.height) / 2)) }
            clipPath(mask, block = draw)
        } else {
            draw()
        }
    }
}
