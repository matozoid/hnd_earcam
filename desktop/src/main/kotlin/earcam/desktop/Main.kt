package earcam.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Usage: `earcam [camera-ip]`. Without an IP the app finds the camera itself. */
fun main(args: Array<String>) = application {
    val windowState = rememberWindowState(width = 960.dp, height = 600.dp)
    val scope = rememberCoroutineScope()
    val state = remember { ViewerState(scope, args.firstOrNull()) }
    LaunchedEffect(Unit) { state.start() }

    fun toggleFullscreen() {
        windowState.placement =
            if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating
            else WindowPlacement.Fullscreen
    }

    Window(
        onCloseRequest = {
            state.close()
            exitApplication()
        },
        state = windowState,
        title = "EarCam",
        onPreviewKeyEvent = { e ->
            // Shortcuts work unless the address field has focus (it consumes typed keys first).
            if (e.type != KeyEventType.KeyDown) return@Window false
            when (e.key) {
                Key.F11 -> toggleFullscreen()
                Key.Escape -> windowState.placement = WindowPlacement.Floating
                else -> return@Window false
            }
            true
        },
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(color = Color.Black, contentColor = Color.White) {
                Viewer(
                    state = state,
                    fullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    onToggleFullscreen = ::toggleFullscreen,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Viewer(state: ViewerState, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    // In fullscreen the toolbar hides until the mouse moves.
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    val showBar = !fullscreen || now - lastMove < 2500

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPointerEvent(PointerEventType.Move) {
                lastMove = System.currentTimeMillis()
                now = lastMove
            },
    ) {
        // Windowed: toolbar above the video. Fullscreen: toolbar over the video.
        if (!fullscreen) Toolbar(state, onToggleFullscreen, Modifier)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val img = state.image
            if (img != null) {
                VideoView(img, state.rotation, state.mirrored, state.circle, Modifier.fillMaxSize())
            } else {
                Text(
                    state.status,
                    color = Color.LightGray,
                    fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (fullscreen && showBar) {
                Toolbar(state, onToggleFullscreen, Modifier.align(Alignment.TopCenter), fullscreenLabel = true)
            }
            state.message?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(Color(0xAA000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: ViewerState,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier,
    fullscreenLabel: Boolean = false,
) {
    var addressText by remember(state.addressOverride) { mutableStateOf(state.addressOverride) }
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xCC101010))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (state.streaming) "${state.status} · ${state.fps} fps" else state.status,
            color = if (state.streaming) Color(0xFF8BE28B) else Color.LightGray,
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = addressText,
            onValueChange = { addressText = it },
            singleLine = true,
            placeholder = { Text("auto", fontSize = 13.sp) },
            label = { Text("Camera IP", fontSize = 11.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.setAddress(addressText) }),
            modifier = Modifier.width(150.dp),
        )
        if (addressText.trim() != state.addressOverride) {
            TextButton(onClick = { state.setAddress(addressText) }) { Text("Connect") }
        }
        TextButton(onClick = state::snapshot) { Text("Snapshot") }
        TextButton(onClick = state::rotate) { Text("Rotate ${state.manualRotation}°") }
        TextButton(onClick = state::toggleMirror) { Text(if (state.mirrored) "Unmirror" else "Mirror") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.autoRotate, onCheckedChange = { state.toggleAutoRotate() })
            Text("Auto-rotate", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.circle, onCheckedChange = { state.toggleCircle() })
            Text("Circle", fontSize = 13.sp)
        }
        TextButton(onClick = onToggleFullscreen) { Text(if (fullscreenLabel) "Exit fullscreen" else "Fullscreen") }
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
fun VideoView(image: ImageBitmap, rotationDeg: Float, mirrored: Boolean, circle: Boolean, modifier: Modifier = Modifier) {
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
