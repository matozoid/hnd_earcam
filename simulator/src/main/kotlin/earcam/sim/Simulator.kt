package earcam.sim

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.plugins.jpeg.JPEGQTable
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fake ear cameras, for testing the apps and the protocol code without hardware.
 *
 *     ./gradlew :simulator:run --args="bl 127.0.0.2"
 *
 * Then enter 127.0.0.2 as the camera address in the app. A separate loopback address
 * lets the fake camera and the app both use the same port numbers on one machine (Linux).
 */
fun main(args: Array<String>) {
    val kind = args.getOrNull(0) ?: "bl"
    val host = InetAddress.getByName(args.getOrNull(1) ?: "127.0.0.2")
    val camera: FakeCamera = when (kind) {
        "bl" -> FakeBlCamera(InetSocketAddress(host, 8800))
        "txin" -> FakeTxinCamera(host)
        "vii" -> FakeViiCamera(InetSocketAddress(host, 7080))
        else -> error("usage: simulator bl|txin|vii [bind address]")
    }
    camera.start()
    println("Fake $kind camera on ${host.hostAddress}. Press Ctrl+C to stop.")
    Thread.currentThread().join()
}

/** Draws the moving test picture and encodes it as JPEG. */
object TestPattern {
    const val WIDTH = 640
    const val HEIGHT = 360

    fun render(n: Long, width: Int = WIDTH, height: Int = HEIGHT): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(40, 20, 60), width.toFloat(), height.toFloat(), Color(200, 120, 60))
        g.fillRect(0, 0, width, height)
        val t = n / 25.0
        val cx = width / 2 + (cos(t) * width / 3).toInt()
        val cy = height / 2 + (sin(t * 1.3) * height / 3).toInt()
        g.color = Color(240, 240, 255)
        g.fillOval(cx - 30, cy - 30, 60, 60)
        g.stroke = BasicStroke(4f)
        g.drawLine(width / 2, 0, width / 2, 40) // "Up" marker, to check rotation.
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        g.drawString("frame $n", 20, height - 20)
        g.dispose()
        return img
    }

    /**
     * Encodes with the standard IJG tables at the given quality (1..100), 4:4:4, 8-bit tables.
     * These are the tables a BL camera uses, so the scan data fits the vendor's headers.
     */
    fun jpeg(img: BufferedImage, quality: Int = 50): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        // IJG quality scaling, as in libjpeg's jpeg_quality_scaling().
        val scale = (if (quality < 50) 5000 / quality else 200 - quality * 2) / 100f
        val tables = listOf(JPEGQTable.K1Luminance, JPEGQTable.K2Chrominance).map { it.getScaledInstance(scale, true) }
        // Replace the quantisation tables in the default metadata. The writer then uses them.
        val format = "javax_imageio_jpeg_image_1.0"
        val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier(img), null)
        val root = meta.getAsTree(format) as IIOMetadataNode
        val dqt = root.getElementsByTagName("dqtable")
        for (i in 0 until dqt.length) {
            val node = dqt.item(i) as IIOMetadataNode
            node.userObject = tables[node.getAttribute("qtableId").toInt()]
        }
        // BL cameras use 4:4:4 (no chroma subsampling).
        val comps = root.getElementsByTagName("componentSpec")
        for (i in 0 until comps.length) {
            val node = comps.item(i) as IIOMetadataNode
            node.setAttribute("HsamplingFactor", "1")
            node.setAttribute("VsamplingFactor", "1")
        }
        meta.setFromTree(format, root)
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, meta), null)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** The entropy-coded data between the SOS header and EOI. This is what a BL camera sends. */
    fun scanData(jpeg: ByteArray): ByteArray {
        var i = 2
        while (i + 4 <= jpeg.size) {
            require(jpeg[i] == 0xFF.toByte()) { "bad marker at $i" }
            val marker = jpeg[i + 1].toInt() and 0xFF
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                val start = i + 2 + len
                var end = jpeg.size - 2
                while (end > start && !(jpeg[end] == 0xFF.toByte() && jpeg[end + 1] == 0xD9.toByte())) end--
                return jpeg.copyOfRange(start, end)
            }
            i += 2 + len
        }
        error("no SOS marker")
    }
}

/** Common socket and thread handling for the fake cameras. */
abstract class FakeCamera : AutoCloseable {
    @Volatile protected var running = false
    private val sockets = mutableListOf<DatagramSocket>()
    private val threads = mutableListOf<Thread>()

    /** Packets to drop on purpose, from 0 to 1. Exercises the retransmit logic. */
    @Volatile var lossRate = 0.0
    private val random = Random(1)

    /** Frames per second. */
    var fps = 25

    abstract fun start()

    protected fun open(bind: SocketAddress): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.soTimeout = 200
        s.bind(bind)
        sockets += s
        return s
    }

    protected fun spawn(name: String, body: () -> Unit) {
        threads += thread(name = name, isDaemon = true) {
            try {
                body()
            } catch (_: InterruptedException) {
            } catch (_: SocketException) {
            }
        }
    }

    protected fun DatagramSocket.receiveOrNull(buf: ByteArray): DatagramPacket? {
        val p = DatagramPacket(buf, buf.size)
        return try {
            receive(p)
            p
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    protected fun DatagramSocket.lossySend(data: ByteArray, len: Int, to: SocketAddress) {
        if (lossRate > 0 && synchronized(random) { random.nextDouble() } < lossRate) return
        try {
            send(DatagramPacket(data, len, to))
        } catch (_: IOException) {
        }
    }

    override fun close() {
        running = false
        sockets.forEach { it.close() }
        threads.forEach { it.interrupt() }
        threads.forEach { it.join(1000) }
    }
}

private fun le() = ByteOrder.LITTLE_ENDIAN

/**
 * Fake BL camera. Streams after the start command. Resends fragments the client reports as
 * missing, and resends whole frames that get no ACK.
 *
 * @property tilt the x and y values sent in fragment 0, for auto-rotate tests
 */
class FakeBlCamera(
    private val bind: SocketAddress,
    private val quality: Int = 50,
    var tilt: Pair<Int, Int> = 0 to 100,
) : FakeCamera() {
    private class Sent(val seq: Long, val packets: List<ByteArray>, var sentAt: Long, var done: Boolean = false)

    private val lock = Any()
    private var client: SocketAddress? = null
    private val window = ArrayDeque<Sent>()
    private var nextSeq = 1L
    private var lastClientMs = 0L

    /** Number of ACK packets received. */
    @Volatile var acksReceived = 0
        private set

    /** The last ACK packet received. */
    @Volatile var lastAck: ByteArray? = null
        private set

    override fun start() {
        val socket = open(bind)
        running = true
        spawn("fake-bl-recv") {
            val buf = ByteArray(2048)
            while (running) {
                val p = socket.receiveOrNull(buf) ?: continue
                val d = buf.copyOf(p.length)
                synchronized(lock) {
                    lastClientMs = now()
                    if (d.size >= 4 && d[0] == 0xEF.toByte()) when (d[1].toInt()) {
                        0 -> if (client != p.socketAddress) {
                            // Start command from a new client: begin a new stream.
                            client = p.socketAddress
                            window.clear()
                            nextSeq = 1
                        }
                        2 -> {
                            acksReceived++
                            lastAck = d
                            handleAck(socket, d)
                        }
                    }
                }
            }
        }
        spawn("fake-bl-send") {
            var frameNo = 0L
            while (running) {
                Thread.sleep(1000L / fps)
                synchronized(lock) {
                    val to = client ?: return@synchronized
                    if (now() - lastClientMs > 2000) {
                        client = null // The client is gone.
                        return@synchronized
                    }
                    // Flow control: at most 4 frames in flight.
                    while (window.isNotEmpty() && window.first().done) window.removeFirst()
                    if (window.size < 4) {
                        val sent = Sent(nextSeq++, buildFrame(frameNo++, nextSeq - 1), now())
                        window.addLast(sent)
                        sent.packets.forEach { socket.lossySend(it, it.size, to) }
                    }
                    // Resend whole frames that got no ACK at all.
                    for (s in window) if (!s.done && now() - s.sentAt > 200) {
                        s.sentAt = now()
                        s.packets.forEach { socket.lossySend(it, it.size, to) }
                    }
                }
            }
        }
    }

    private fun handleAck(socket: DatagramSocket, d: ByteArray) {
        val b = ByteBuffer.wrap(d).order(le())
        val count = d[8].toInt() and 0xFF
        // Entries start at 0x20, or 0x10 in the old layout. The QPara bytes tell them apart.
        var off = if (d.size > 0x19 && d[0x19].toInt() == 75) 0x20 else 0x10
        repeat(count) {
            if (off + 16 > d.size) return
            val seq = b.getLong(off)
            val status = b.getInt(off + 8)
            val len = b.getInt(off + 12)
            val sent = window.firstOrNull { it.seq == seq }
            when (status) {
                1 -> sent?.done = true
                0 -> if (sent != null) {
                    sent.sentAt = now()
                    for (i in sent.packets.indices) {
                        val word = b.getInt(off + 16 + (i / 32) * 4)
                        if (word and (1 shl (i % 32)) == 0) {
                            socket.lossySend(sent.packets[i], sent.packets[i].size, client!!)
                        }
                    }
                }
                3 -> window.filter { it.seq < seq - 3 }.forEach { it.done = true }
            }
            off += len
        }
    }

    private fun buildFrame(frameNo: Long, seq: Long): List<ByteArray> {
        val img = TestPattern.render(frameNo)
        val scan = TestPattern.scanData(TestPattern.jpeg(img, quality))
        val cnt = (scan.size + 1023) / 1024
        val doneSeq = window.lastOrNull { it.done }?.seq ?: 0L
        return (0 until cnt).map { idx ->
            val n = minOf(1024, scan.size - idx * 1024)
            val extra = if (idx == 0) 11 else 0
            val pkt = ByteArray(0x38 + n + extra)
            val b = ByteBuffer.wrap(pkt).order(le())
            pkt[0] = 0x93.toByte()
            pkt[1] = 1
            b.putShort(2, pkt.size.toShort())
            b.putLong(8, seq)
            b.putLong(0x18, doneSeq)
            b.putInt(0x20, idx)
            b.putInt(0x24, cnt)
            b.putInt(0x28, scan.size)
            b.putShort(0x2C, img.width.toShort())
            b.putShort(0x2E, img.height.toShort())
            pkt[0x30] = quality.toByte()
            System.arraycopy(scan, idx * 1024, pkt, 0x38, n)
            if (idx == 0) {
                val (x, y) = tilt
                val s = String.format("%s%04d%s%04d", sign(x), Math.abs(x), "x" + sign(y), Math.abs(y))
                System.arraycopy(s.toByteArray(), 0, pkt, pkt.size - 11, 11)
            }
            pkt
        }
    }

    private fun sign(v: Int) = if (v < 0) "-" else "+"
    private fun now() = System.nanoTime() / 1_000_000
}

/** Fake Beken TXIN camera: answers discovery, streams on 44506, sends tilt on 52219. */
class FakeTxinCamera(
    private val host: InetAddress,
    private val videoPort: Int = 44506,
    private val sensorPort: Int = 52219,
    private val discoveryPort: Int = 46526,
    var angle: Int = 0,
) : FakeCamera() {
    @Volatile private var videoClient: SocketAddress? = null
    @Volatile private var sensorClient: SocketAddress? = null

    /** Number of discovery probes answered. */
    @Volatile var discoveries = 0
        private set

    override fun start() {
        val video = open(InetSocketAddress(host, videoPort))
        val sensor = open(InetSocketAddress(host, sensorPort))
        // Listen on the camera address so replies come from it, like a real camera. Pass the
        // wildcard address (0.0.0.0) as host to also receive broadcast probes.
        val discovery = open(InetSocketAddress(host, discoveryPort))
        running = true
        val buf = ByteArray(64)
        spawn("fake-txin-discovery") {
            val b = ByteArray(64)
            while (running) {
                val p = discovery.receiveOrNull(b) ?: continue
                if (p.length >= 2 && b[0] == 0x66.toByte() && b[1] == 0x30.toByte()) {
                    discoveries++
                    discovery.lossySend(byteArrayOf(0x66, 0x31, 0x01, 0x01), 4, p.socketAddress)
                }
            }
        }
        spawn("fake-txin-video-cmd") {
            while (running) {
                val p = video.receiveOrNull(buf) ?: continue
                if (p.length == 2 && buf[0] == 0x20.toByte()) when (buf[1].toInt()) {
                    0x36 -> videoClient = p.socketAddress
                    0x37 -> videoClient = null
                }
            }
        }
        spawn("fake-txin-sensor-cmd") {
            val b = ByteArray(64)
            while (running) {
                val p = sensor.receiveOrNull(b) ?: continue
                if (p.length >= 1 && b[0] == 0x86.toByte()) sensorClient = p.socketAddress
            }
        }
        spawn("fake-txin-send") {
            var frameNo = 0L
            while (running) {
                Thread.sleep(1000L / fps)
                val to = videoClient ?: continue
                val jpeg = TestPattern.jpeg(TestPattern.render(frameNo))
                val chunks = (jpeg.size + 1399) / 1400
                for (i in 0 until chunks) {
                    val n = minOf(1400, jpeg.size - i * 1400)
                    val pkt = ByteArray(4 + n)
                    pkt[0] = (frameNo and 0xFF).toByte()
                    pkt[1] = if (i == chunks - 1) 1 else 0
                    pkt[2] = (i + 1).toByte()
                    System.arraycopy(jpeg, i * 1400, pkt, 4, n)
                    video.lossySend(pkt, pkt.size, to)
                }
                sensorClient?.let { s ->
                    val pkt = ByteArray(20)
                    pkt[18] = (angle shr 8).toByte()
                    pkt[19] = angle.toByte()
                    sensor.lossySend(pkt, pkt.size, s)
                }
                frameNo++
            }
        }
    }
}

/** Fake Vii Beken camera: one port, sensor data inside each video packet. */
class FakeViiCamera(
    private val bind: SocketAddress,
    var tilt: Pair<Int, Int> = 0 to 16000,
) : FakeCamera() {
    @Volatile private var client: SocketAddress? = null
    @Volatile private var lastHeardMs = 0L

    /** Number of heartbeats received. */
    @Volatile var heartbeats = 0
        private set

    override fun start() {
        val socket = open(bind)
        running = true
        spawn("fake-vii-cmd") {
            val buf = ByteArray(64)
            while (running) {
                val p = socket.receiveOrNull(buf) ?: continue
                if (p.length != 2 || buf[0] != 0x20.toByte()) continue
                lastHeardMs = System.nanoTime() / 1_000_000
                when (buf[1].toInt()) {
                    0x36 -> client = p.socketAddress
                    0x35 -> heartbeats++
                    0x37 -> client = null
                }
            }
        }
        spawn("fake-vii-send") {
            var frameNo = 0L
            while (running) {
                Thread.sleep(1000L / fps)
                val to = client ?: continue
                if (System.nanoTime() / 1_000_000 - lastHeardMs > 6000) {
                    client = null // No heartbeat.
                    continue
                }
                val jpeg = TestPattern.jpeg(TestPattern.render(frameNo))
                val chunks = (jpeg.size + 1399) / 1400
                for (i in 0 until chunks) {
                    val n = minOf(1400, jpeg.size - i * 1400)
                    val pkt = ByteArray(12 + n)
                    val b = ByteBuffer.wrap(pkt).order(le())
                    pkt[0] = (frameNo and 0xFF).toByte()
                    pkt[1] = if (i == chunks - 1) 1 else 0
                    pkt[2] = chunks.toByte()
                    pkt[3] = (i + 1).toByte()
                    b.putShort(4 + 2, tilt.first.toShort())
                    b.putShort(4 + 4, tilt.second.toShort())
                    System.arraycopy(jpeg, i * 1400, pkt, 12, n)
                    socket.lossySend(pkt, pkt.size, to)
                }
                frameNo++
            }
        }
    }
}
