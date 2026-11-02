package earcam.protocol

import earcam.sim.FakeBlCamera
import earcam.sim.FakeTxinCamera
import earcam.sim.FakeViiCamera
import earcam.sim.TestPattern
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()

private fun decode(jpeg: ByteArray) = assertNotNull(ImageIO.read(ByteArrayInputStream(jpeg)), "JPEG did not decode")

/** Collects frames and waits for a number of them. */
private class Collector(val want: Int) {
    val frames = CopyOnWriteArrayList<Frame>()
    private val latch = CountDownLatch(want)
    val sink: (Frame) -> Unit = { frames += it; latch.countDown() }
    fun await(seconds: Long = 10) = assertTrue(latch.await(seconds, TimeUnit.SECONDS), "got only ${frames.size}/$want frames")
}

class BlJpegHeaderTest {
    @Test
    fun `vendor header plus scan data decodes`() {
        for (q in listOf(5, 10, 25, 50, 75, 100)) {
            val img = TestPattern.render(3, 320, 240)
            val original = TestPattern.jpeg(img, q)
            val scan = TestPattern.scanData(original)
            val header = BlJpegHeaders.forQuality(q).copyOf()
            val o = BlJpegHeaders.SIZE_OFFSET
            header[o] = 0; header[o + 1] = 240.toByte(); header[o + 2] = 1; header[o + 3] = 64
            val decoded = decode(header + scan + bytesOf(0xFF, 0xD9))
            assertEquals(320, decoded.width)
            assertEquals(240, decoded.height)
            // Same tables as the standard IJG encoder, so the pixels match the original file.
            val reference = decode(original)
            for ((x, y) in listOf(10 to 10, 160 to 120, 300 to 200)) {
                val a = reference.getRGB(x, y)
                val b = decoded.getRGB(x, y)
                for (shift in listOf(0, 8, 16)) {
                    val diff = Math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                    assertTrue(diff <= 2, "q=$q at $x,$y channel $shift differs by $diff")
                }
            }
        }
    }
}

class BlSessionTest {
    private fun frag(seq: Long, idx: Int, cnt: Int, scan: ByteArray, extra: String = "+0000x+0100"): ByteArray {
        val n = minOf(1024, scan.size - idx * 1024)
        val pkt = ByteArray(0x38 + n + if (idx == 0) 11 else 0)
        val b = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN)
        pkt[0] = 0x93.toByte(); pkt[1] = 1
        b.putShort(2, pkt.size.toShort())
        b.putLong(8, seq)
        b.putInt(0x20, idx); b.putInt(0x24, cnt); b.putInt(0x28, scan.size)
        b.putShort(0x2C, 640); b.putShort(0x2E, 360)
        pkt[0x30] = 50
        System.arraycopy(scan, idx * 1024, pkt, 0x38, n)
        if (idx == 0) System.arraycopy(extra.toByteArray(), 0, pkt, pkt.size - 11, 11)
        return pkt
    }

    private val scan = TestPattern.scanData(TestPattern.jpeg(TestPattern.render(1)))
    private val cnt = (scan.size + 1023) / 1024

    @Test
    fun `sends start until fragments arrive`() {
        val sent = mutableListOf<ByteArray>()
        val s = BlSession { sent += it }
        s.tick(1000); s.tick(1050); s.tick(1101)
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.contentEquals(BlSession.START_CMD) })
    }

    @Test
    fun `out of order fragments assemble and frames deliver in order`() {
        val s = BlSession { }
        // Frame 2 completes first. It must wait for frame 1.
        for (i in (0 until cnt).reversed()) frag(2, i, cnt, scan).let { assertTrue(s.onPacket(it, it.size, 0).isEmpty()) }
        val out = mutableListOf<Frame>()
        for (i in listOf(1, 0) + (2 until cnt)) {
            val p = frag(1, i, cnt, scan)
            out += s.onPacket(p, p.size, 0)
        }
        assertEquals(listOf(1L, 2L), out.map { it.seq })
        val img = decode(out[0].jpeg)
        assertEquals(640, img.width)
        assertEquals(360, img.height)
        assertEquals(90f, out[0].rotationDeg!!, 0.01f) // atan2(0, 100) + 90
    }

    @Test
    fun `duplicate and malformed packets are ignored`() {
        val s = BlSession { }
        val p = frag(1, 0, cnt, scan)
        assertTrue(s.onPacket(p, p.size - 1, 0).isEmpty()) // Length mismatch.
        val bad = p.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20, cnt) }
        assertTrue(s.onPacket(bad, bad.size, 0).isEmpty()) // Index out of range.
        s.onPacket(p, p.size, 0)
        s.onPacket(p, p.size, 0)
        var got = emptyList<Frame>()
        for (i in 1 until cnt) frag(1, i, cnt, scan).let { got = s.onPacket(it, it.size, 0) }
        assertEquals(1, got.size)
    }

    @Test
    fun `ack reports missing fragments`() {
        val sent = mutableListOf<ByteArray>()
        val s = BlSession { sent += it }
        // Frame 1 is missing fragment 1. Frame 2 starts.
        for (i in (0 until cnt).filter { it != 1 }) frag(1, i, cnt, scan).let { s.onPacket(it, it.size, 100) }
        frag(2, 0, cnt, scan).let { s.onPacket(it, it.size, 100) }
        s.tick(110)
        val ack = sent.last()
        val b = ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xEF, ack[0].toInt() and 0xFF)
        assertEquals(2, ack[1].toInt())
        assertEquals(ack.size, b.getShort(2).toInt() and 0xFFFF)
        assertEquals(75, ack[0x19].toInt())
        // One entry: slot 0 (frame 1, still receiving). Slot 1 holds the current frame and
        // is skipped. Slots 2 and 3 are empty and not yet due.
        assertEquals(1, ack[8].toInt())
        assertEquals(1L, b.getLong(0x20))
        assertEquals(0, b.getInt(0x28))
        val words = (cnt + 31) / 32
        assertEquals(16 + words * 4, b.getInt(0x2C))
        val bitmap0 = b.getInt(0x30)
        assertEquals(0, bitmap0 and 2, "fragment 1 must be reported missing")
        assertEquals(1, bitmap0 and 1)
        // 16 trailing zero bytes, like the vendor library.
        assertEquals(0x20 + 16 + words * 4 + 16, ack.size)
    }

    @Test
    fun `resets after two seconds without data`() {
        val sent = mutableListOf<ByteArray>()
        val s = BlSession { sent += it }
        frag(1, 0, cnt, scan).let { s.onPacket(it, it.size, 0) }
        s.tick(2001)
        sent.clear()
        s.tick(2002); s.tick(2200)
        assertTrue(sent.isNotEmpty() && sent.all { it.contentEquals(BlSession.START_CMD) })
    }

    @Test
    fun `rotation string parsing`() {
        assertEquals(90f, BlSession.parseRotation("+0000x+0100".toByteArray())!!, 0.01f)
        assertEquals(180f, BlSession.parseRotation("+0100x+0000".toByteArray())!!, 0.01f)
        assertEquals(0f, BlSession.parseRotation("-0100x+0000".toByteArray())!!, 0.01f)
        assertNull(BlSession.parseRotation("garbage!!!!".toByteArray()))
    }
}

class DriverIntegrationTest {
    @Test
    fun `bl driver streams from fake camera with packet loss`() {
        val cam = FakeBlCamera(InetSocketAddress(LOOPBACK, 18800)).apply { lossRate = 0.1 }
        cam.start()
        val driver = BlDriver(InetSocketAddress(LOOPBACK, 18800))
        val c = Collector(20)
        try {
            driver.start(c.sink)
            c.await(20)
        } finally {
            driver.close(); cam.close()
        }
        val seqs = c.frames.map { it.seq }
        assertEquals(seqs.sorted(), seqs, "frames out of order")
        assertEquals(seqs.size, seqs.toSet().size, "duplicate frames")
        c.frames.take(3).forEach { assertEquals(640, decode(it.jpeg).width) }
        assertTrue(cam.acksReceived > 0)
    }

    @Test
    fun `txin driver discovers camera and streams`() {
        val cam = FakeTxinCamera(LOOPBACK, videoPort = 14506, sensorPort = 12219, discoveryPort = 14526, angle = 45)
        cam.start()
        val driver = BekenTxinDriver(
            camera = null, videoPort = 14506, sensorPort = 12219, discoveryPort = 14526,
            localVideoPort = 14507, localSensorPort = 12220, broadcastAddresses = listOf(LOOPBACK),
        )
        val c = Collector(10)
        try {
            driver.start(c.sink)
            c.await()
        } finally {
            driver.close(); cam.close()
        }
        assertTrue(cam.discoveries > 0)
        decode(c.frames.first().jpeg)
        assertEquals(45f, c.frames.last().rotationDeg)
    }

    @Test
    fun `vii driver streams and reads tilt`() {
        val cam = FakeViiCamera(InetSocketAddress(LOOPBACK, 17080), tilt = 0 to 16000)
        cam.start()
        val driver = ViiBekenDriver(LOOPBACK, cameraPort = 17080, localPort = 17081)
        val c = Collector(10)
        try {
            driver.start(c.sink)
            c.await()
        } finally {
            driver.close(); cam.close()
        }
        decode(c.frames.first().jpeg)
        // atan2(16000, 0) = 90 degrees, plus 180.
        assertEquals(270f, c.frames.last().rotationDeg!!, 0.5f)
    }

    @Test
    fun `client picks the protocol that delivers video`() {
        val cam = FakeViiCamera(InetSocketAddress(LOOPBACK, 17090))
        cam.start()
        val statuses = CopyOnWriteArrayList<CameraClient.Status>()
        val protocols = CopyOnWriteArrayList<Protocol>()
        val latch = CountDownLatch(5)
        val client = CameraClient(
            resolveCamera = { LOOPBACK },
            listener = object : CameraClient.Listener {
                override fun onFrame(frame: Frame, protocol: Protocol) {
                    protocols += protocol; latch.countDown()
                }
                override fun onStatus(status: CameraClient.Status) { statuses += status }
            },
            driverFactory = { addr ->
                listOf(
                    BlDriver(InetSocketAddress(addr, 18810)),
                    BekenTxinDriver(addr, 14516, 12229, 14536, 14517, 12230, listOf(LOOPBACK)),
                    ViiBekenDriver(addr, cameraPort = 17090, localPort = 17091),
                )
            },
        )
        try {
            client.start()
            assertTrue(latch.await(10, TimeUnit.SECONDS))
        } finally {
            client.close(); cam.close()
        }
        assertTrue(protocols.all { it == Protocol.VII_BEKEN })
        assertTrue(statuses.any { it is CameraClient.Status.Streaming && it.protocol == Protocol.VII_BEKEN })
    }
}

class CameraAddressTest {
    @Test
    fun `parses linux route table`() {
        val text = """
            Iface	Destination	Gateway 	Flags	RefCnt	Use	Metric	Mask		MTU	Window	IRTT
            wlp4s0	00000000	FE02A8C0	0003	0	0	600	00000000	0	0	0
            wlp4s0	0002A8C0	00000000	0001	0	0	600	00FFFFFF	0	0	0
            wlan1	00000000	01A9A8C0	0003	0	0	700	00000000	0	0	0
        """.trimIndent()
        assertEquals(listOf("192.168.2.254", "192.168.169.1"), CameraAddress.parseLinuxRoutes(text))
    }

    @Test
    fun `parses windows and mac route output`() {
        val win = """
            Network Destination        Netmask          Gateway       Interface  Metric
                      0.0.0.0          0.0.0.0      192.168.1.1    192.168.1.100     25
                    127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
        """.trimIndent()
        assertEquals(listOf("192.168.1.1"), CameraAddress.parseWindowsRoutes(win))
        val mac = """
            Destination        Gateway            Flags               Netif Expire
            default            192.168.169.1      UGScg                 en0
            127                127.0.0.1          UCS                   lo0
        """.trimIndent()
        assertEquals(listOf("192.168.169.1"), CameraAddress.parseBsdRoutes(mac))
    }

    @Test
    fun `prefers camera-like subnets`() {
        val pick = CameraAddress.pickBest(listOf("10.0.0.1", "192.168.2.254", "192.168.169.1").map { InetAddress.getByName(it) })
        assertEquals("192.168.169.1", pick?.hostAddress)
    }
}
