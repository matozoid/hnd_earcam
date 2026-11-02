package earcam.protocol

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Puts JPEG frames back together from numbered UDP packets. Both Beken protocols use it.
 * Each frame ID maps to a slot (`frameId % slots`). A packet with a new frame ID resets its slot.
 */
internal class PacketFrameAssembler(slotCount: Int, private val maxPackets: Int = 40) {
    private class Slot(maxPackets: Int) {
        var frameId = -1
        var total = 0
        var received = 0
        var done = false
        val parts = arrayOfNulls<ByteArray>(maxPackets)
    }

    private val slots = Array(slotCount) { Slot(maxPackets) }
    private var seq = 0L

    /**
     * Adds one packet. [pkgSeq] starts at 1. [total] is the packet count if known, or 0.
     * Returns the full JPEG when this packet completes a frame.
     */
    fun add(frameId: Int, pkgSeq: Int, eof: Boolean, total: Int, data: ByteArray, off: Int, len: Int): Frame? {
        if (pkgSeq !in 1..maxPackets || len < 0) return null
        val s = slots[frameId % slots.size]
        if (s.frameId != frameId) {
            s.frameId = frameId
            s.total = 0
            s.received = 0
            s.done = false
            s.parts.fill(null)
        }
        if (s.done) return null
        if (total in 1..maxPackets) s.total = total
        if (eof) s.total = pkgSeq
        if (s.parts[pkgSeq - 1] == null) {
            s.parts[pkgSeq - 1] = data.copyOfRange(off, off + len)
            s.received++
        }
        if (s.total == 0 || s.received < s.total) return null
        for (i in 0 until s.total) if (s.parts[i] == null) return null

        s.done = true
        val size = (0 until s.total).sumOf { s.parts[it]!!.size }
        val jpeg = ByteArray(size)
        var pos = 0
        for (i in 0 until s.total) {
            val part = s.parts[i]!!
            System.arraycopy(part, 0, jpeg, pos, part.size)
            pos += part.size
        }
        s.parts.fill(null)
        return Frame(jpeg, ++seq, null)
    }
}

/** Base class for the two Beken drivers: sockets, threads, and a restart watchdog. */
abstract class UdpDriverBase : CameraDriver {
    @Volatile protected var running = false
    private val sockets = mutableListOf<DatagramSocket>()
    private val threads = mutableListOf<Thread>()

    protected fun openSocket(bind: InetSocketAddress, timeoutMs: Int): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.soTimeout = timeoutMs
        try {
            s.bind(bind)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        synchronized(sockets) { sockets += s }
        return s
    }

    protected fun spawn(name: String, body: () -> Unit) {
        threads += thread(name = name, isDaemon = true) {
            try {
                body()
            } catch (_: InterruptedException) {
            }
        }
    }

    /** Receives on [socket] until closed. Timeouts call [onTimeout]. */
    protected fun receiveLoop(socket: DatagramSocket, onTimeout: () -> Unit = {}, handle: (DatagramPacket) -> Unit) {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.setData(buf)
                socket.receive(pkt)
            } catch (_: SocketTimeoutException) {
                onTimeout()
                continue
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                continue
            }
            handle(pkt)
        }
    }

    protected fun DatagramSocket.trySend(data: ByteArray, to: InetSocketAddress) {
        try {
            send(DatagramPacket(data, data.size, to))
        } catch (_: IOException) {
        }
    }

    /** Called before the sockets close. Send the stop command here. */
    protected open fun beforeClose() {}

    override fun close() {
        if (!running) return
        beforeClose()
        running = false
        synchronized(sockets) { sockets.forEach { it.close() } }
        threads.forEach { it.interrupt() }
        threads.forEach { it.join(500) }
        threads.clear()
    }
}

/**
 * Beken "TXIN" cameras. Video and sensor data use separate ports. The camera answers a
 * broadcast discovery packet, so the driver can find it even without a known address.
 */
class BekenTxinDriver(
    private val camera: InetAddress?,
    private val videoPort: Int = 44506,
    private val sensorPort: Int = 52219,
    private val discoveryPort: Int = 46526,
    private val localVideoPort: Int = videoPort,
    private val localSensorPort: Int = sensorPort,
    private val broadcastAddresses: List<InetAddress> = listOf(
        InetAddress.getByName("192.168.1.255"),
        InetAddress.getByName("255.255.255.255"),
    ),
) : UdpDriverBase() {
    override val protocol = Protocol.BEKEN_TXIN

    private lateinit var video: DatagramSocket
    private lateinit var sensor: DatagramSocket
    @Volatile private var target: InetAddress? = camera
    @Volatile private var lastFrameMs = 0L
    @Volatile private var rotation: Float? = null
    private val assembler = PacketFrameAssembler(3)

    override fun start(onFrame: (Frame) -> Unit) {
        video = openSocket(InetSocketAddress(localVideoPort), 3000)
        try {
            sensor = openSocket(InetSocketAddress(localSensorPort), 3000)
        } catch (e: IOException) {
            video.close()
            throw e
        }
        running = true

        spawn("txin-video") {
            receiveLoop(video) { pkt ->
                if (pkt.port == discoveryPort) {
                    // Discovery reply. Its sender is the camera.
                    if (target == null || target != pkt.address) {
                        target = pkt.address
                        sendStart()
                    }
                    return@receiveLoop
                }
                if (pkt.length < 4 || (target != null && pkt.address != target)) return@receiveLoop
                val d = pkt.data
                val frame = assembler.add(
                    frameId = d.u8(0), pkgSeq = d.u8(2), eof = d.u8(1) != 0, total = 0,
                    data = d, off = 4, len = pkt.length - 4,
                ) ?: return@receiveLoop
                lastFrameMs = monotonicMs()
                onFrame(Frame(frame.jpeg, frame.seq, rotation))
            }
        }
        spawn("txin-sensor") {
            receiveLoop(sensor) { pkt ->
                if (pkt.length > 19) {
                    val d = pkt.data
                    rotation = ((d.u8(18) shl 8) or d.u8(19)).toShort().toFloat()
                }
            }
        }
        spawn("txin-watchdog") {
            while (running) {
                if (monotonicMs() - lastFrameMs > 3000) {
                    discover()
                    sendStart()
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun discover() {
        val probe = bytesOf(0x66, 0x30, 0x01, 0x01)
        for (addr in broadcastAddresses) {
            repeat(2) { video.trySend(probe, InetSocketAddress(addr, discoveryPort)) }
        }
        // Also ask the expected address directly, in case broadcasts are filtered.
        camera?.let { video.trySend(probe, InetSocketAddress(it, discoveryPort)) }
    }

    private fun sendStart() {
        val cam = target ?: return
        sensor.trySend(bytesOf(0x86, 0x06, 0x01), InetSocketAddress(cam, sensorPort))
        video.trySend(bytesOf(0x20, 0x36), InetSocketAddress(cam, videoPort))
    }

    override fun beforeClose() {
        val cam = target ?: return
        video.trySend(bytesOf(0x20, 0x37), InetSocketAddress(cam, videoPort))
    }
}

/**
 * Vii Beken cameras. One port carries video and commands. Every video packet has a
 * 12-byte header with 6 bytes of accelerometer data.
 */
class ViiBekenDriver(
    private val camera: InetAddress,
    private val cameraPort: Int = 7080,
    private val localPort: Int = 7080,
) : UdpDriverBase() {
    override val protocol = Protocol.VII_BEKEN

    private lateinit var socket: DatagramSocket
    @Volatile private var lastFrameMs = 0L
    private val assembler = PacketFrameAssembler(5)
    private val tilt = TiltFilter()

    override fun start(onFrame: (Frame) -> Unit) {
        socket = openSocket(InetSocketAddress(localPort), 1000)
        running = true
        val to = InetSocketAddress(camera, cameraPort)

        spawn("vii-video") {
            receiveLoop(socket) { pkt ->
                if (pkt.length < 12 || pkt.address != camera) return@receiveLoop
                val d = pkt.data
                tilt.add(d.le16(4 + 2).toShort().toInt(), d.le16(4 + 4).toShort().toInt())
                val frame = assembler.add(
                    frameId = d.u8(0), pkgSeq = d.u8(3), eof = d.u8(1) != 0, total = d.u8(2),
                    data = d, off = 12, len = pkt.length - 12,
                ) ?: return@receiveLoop
                lastFrameMs = monotonicMs()
                onFrame(Frame(frame.jpeg, frame.seq, tilt.angle))
            }
        }
        spawn("vii-control") {
            var lastHeartbeat = 0L
            while (running) {
                val now = monotonicMs()
                if (now - lastFrameMs > 1000) {
                    // Not streaming (yet). Ask again.
                    socket.trySend(bytesOf(0x20, 0x36), to)
                } else if (now - lastHeartbeat >= 2000) {
                    socket.trySend(bytesOf(0x20, 0x35), to)
                    lastHeartbeat = now
                }
                Thread.sleep(500)
            }
        }
    }

    override fun beforeClose() {
        socket.trySend(bytesOf(0x20, 0x37), InetSocketAddress(camera, cameraPort))
    }
}

/** Turns raw accelerometer X/Y into a smoothed rotation angle, like the vendor app does. */
internal class TiltFilter(private val window: Int = 5) {
    private val xs = ArrayDeque<Int>()
    private val ys = ArrayDeque<Int>()

    @Volatile var angle: Float? = null
        private set

    @Synchronized
    fun add(x: Int, y: Int) {
        // Small readings mean the sensor is not upright enough to give a direction.
        if (abs(x) < 8000 && abs(y) < 8000) return
        xs.addLast(x)
        ys.addLast(y)
        if (xs.size > window) {
            xs.removeFirst()
            ys.removeFirst()
        }
        val a = (Math.toDegrees(atan2(ys.average(), xs.average())) + 180.0).toFloat()
        val prev = angle
        if (prev == null) {
            angle = a
            return
        }
        var diff = a - prev
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        if (abs(diff) > 1f) angle = ((prev + diff / 2 + 360) % 360)
    }
}
