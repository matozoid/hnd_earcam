package earcam.protocol

import java.io.IOException
import java.lang.Long.compareUnsigned
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.math.atan2

/**
 * Driver for the "BL" camera chip. The vendor app does this in libbl_vii_jni.so.
 *
 * The client sends a start command every 100 ms until fragments arrive. Each frame is split
 * into 1024-byte fragments. The client ACKs every 25 ms with a bitmap of the fragments it has,
 * and the camera resends the missing ones. Frames hold only JPEG scan data. The client adds
 * a JPEG header that matches the frame's quality setting (see [BlJpegHeaders]).
 */
class BlDriver(
    private val camera: InetSocketAddress,
    private val bindAddress: InetSocketAddress = InetSocketAddress(0),
) : CameraDriver {
    override val protocol = Protocol.BL

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()

    override fun start(onFrame: (Frame) -> Unit) {
        val s = DatagramSocket(bindAddress)
        socket = s
        running = true
        val session = BlSession { data ->
            try {
                s.send(DatagramPacket(data, data.size, camera))
            } catch (_: IOException) {
                // Network down or camera unreachable. The timer retries.
            }
        }
        threads += thread(name = "bl-recv", isDaemon = true) {
            val buf = ByteArray(BlSession.MAX_PACKET)
            val pkt = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    pkt.setData(buf)
                    s.receive(pkt)
                } catch (_: SocketException) {
                    break // Closed.
                } catch (_: IOException) {
                    continue
                }
                if (pkt.address != camera.address) continue
                for (frame in session.onPacket(buf, pkt.length, monotonicMs())) onFrame(frame)
            }
        }
        threads += thread(name = "bl-timer", isDaemon = true) {
            while (running) {
                session.tick(monotonicMs())
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    override fun close() {
        running = false
        socket?.close()
        threads.forEach { it.interrupt() }
        threads.forEach { it.join(500) }
        threads.clear()
    }
}

/**
 * The BL protocol state machine, without any I/O. All times are in milliseconds from a
 * monotonic clock. [send] is called while the session lock is held.
 */
internal class BlSession(private val send: (ByteArray) -> Unit) {
    companion object {
        const val MAX_PACKET = 0x443
        const val CAMERA_PORT = 8800
        const val FRAG_SIZE = 1024
        const val MAX_FRAGS = 1024
        const val HEADER_SIZE = 0x38
        const val EXTRA_SIZE = 11

        const val MAGIC_IN = 0x93
        const val TYPE_FRAG = 1
        const val TYPE_CTLMSG = 4

        val START_CMD = bytesOf(0xEF, 0x00, 0x04, 0x00)

        private const val EMPTY = 0
        private const val RECEIVING = 1
        private const val COMPLETE = 2
        private const val DELIVERED = 3
        private const val UNUSED = 4

        private const val CUR_SLOT_IDLE = -2

        /**
         * Parses the 11-byte tilt string from fragment 0, for example `+0123-0456`. Returns
         * a rotation in degrees, or null if the string is malformed.
         */
        fun parseRotation(extra: ByteArray): Float? {
            fun digits(from: Int): Int? {
                var v = 0
                for (i in from until from + 4) {
                    val c = extra[i].toInt()
                    if (c !in '0'.code..'9'.code) return null
                    v = v * 10 + (c - '0'.code)
                }
                return v
            }
            if (extra.size < EXTRA_SIZE) return null
            val xAbs = digits(1) ?: return null
            val yAbs = digits(7) ?: return null
            val x = if (extra[0] == '-'.code.toByte()) -xAbs else xAbs
            val y = if (extra[6] == '-'.code.toByte()) -yAbs else yAbs
            if (x == 0 && y == 0) return null
            return (Math.toDegrees(atan2(x.toDouble(), y.toDouble())) + 90.0).toFloat()
        }
    }

    private class Slot {
        var state = EMPTY
        var seq = 0L
        var count = 0
        var dataLen = 0
        val bitmap = IntArray(MAX_FRAGS / 32)
        val extra = ByteArray(EXTRA_SIZE)
        var hasExtra = false

        /** JPEG header, then the scan data, then EOI. */
        val jpeg = ByteArray(BlJpegHeaders.LENGTH + MAX_FRAGS * FRAG_SIZE + 2)

        fun clear() {
            state = EMPTY
            seq = 0
            count = 0
            dataLen = 0
            bitmap.fill(0)
            hasExtra = false
        }

        val isComplete: Boolean get() = bitmap.all { it == -1 }
        val bitmapWords: Int get() = (count + 31) / 32
    }

    private val slots = Array(4) { Slot() }
    private var streaming = false
    private var maxSeq = 0L
    private var camAck = 0L
    private var nextSeq = 1L
    private var deliverIdx = 0
    private var curSlot = 0
    private var legacyAck = false

    private var lastFragMs = 0L
    private var lastStartMs = Long.MIN_VALUE / 2
    private var lastAckMs = 0L
    private var lastLegacyToggleMs = Long.MIN_VALUE / 2

    @Synchronized
    fun onPacket(p: ByteArray, len: Int, now: Long): List<Frame> {
        if (len < 4 || p.u8(0) != MAGIC_IN || p.le16(2) != len) return emptyList()
        when (p.u8(1)) {
            TYPE_FRAG -> return onFrag(p, len, now)
            TYPE_CTLMSG -> if (len >= 8) {
                // Control message from the camera. The vendor app only acknowledges it.
                send(bytesOf(0xEF, 0x08, 0x08, 0x00, p.u8(4), p.u8(5), p.u8(6), p.u8(7)))
            }
        }
        return emptyList()
    }

    private fun onFrag(p: ByteArray, len: Int, now: Long): List<Frame> {
        if (len < HEADER_SIZE) return emptyList()
        val seq = p.le64(8)
        val ack = p.le64(0x18)
        val idxL = p.le32(0x20)
        val cntL = p.le32(0x24)
        val dataLenL = p.le32(0x28)
        val width = p.le16(0x2C)
        val height = p.le16(0x2E)
        val quality = p.u8(0x30)

        // Validate before touching any state.
        if (cntL !in 1..MAX_FRAGS || idxL >= cntL) return emptyList()
        if (dataLenL <= (cntL - 1) * FRAG_SIZE || dataLenL > cntL * FRAG_SIZE) return emptyList()
        val idx = idxL.toInt()
        val cnt = cntL.toInt()
        val dataLen = dataLenL.toInt()
        val n = if (idx == cnt - 1) dataLen - idx * FRAG_SIZE else FRAG_SIZE
        if (HEADER_SIZE + n > len) return emptyList()
        if (idx == 0 && len < HEADER_SIZE + EXTRA_SIZE) return emptyList()
        if (seq == 0L) return emptyList()

        camAck = ack
        if (compareUnsigned(maxSeq, seq) < 0) maxSeq = seq
        val slotIdx = ((seq - 1) and 3).toInt()
        curSlot = slotIdx
        val s = slots[slotIdx]

        val isNewFrame = (s.state == EMPTY || s.state == DELIVERED || s.state == UNUSED) &&
            (s.seq == seq - 4 || (s.seq == 0L && compareUnsigned(seq, 5) < 0))
        if (isNewFrame) {
            streaming = true
            lastFragMs = now
            s.clear()
            s.state = RECEIVING
            s.seq = seq
            s.count = cnt
            s.dataLen = dataLen
            // Bits for fragments that don't exist start out set, so "complete" is "all ones".
            s.bitmap.fill(-1)
            for (w in 0 until cnt / 32) s.bitmap[w] = 0
            if (cnt % 32 != 0) s.bitmap[cnt / 32] = -1 shl (cnt % 32)
            writeHeader(s, quality, width, height)
        } else if (!(s.state == RECEIVING && s.seq == seq)) {
            return emptyList()
        }
        lastFragMs = now
        s.bitmap[idx / 32] = s.bitmap[idx / 32] or (1 shl (idx % 32))
        System.arraycopy(p, HEADER_SIZE, s.jpeg, BlJpegHeaders.LENGTH + idx * FRAG_SIZE, n)
        if (idx == 0) {
            System.arraycopy(p, len - EXTRA_SIZE, s.extra, 0, EXTRA_SIZE)
            s.hasExtra = true
        }
        if (!s.isComplete) return emptyList()

        s.state = COMPLETE
        return deliverInOrder()
    }

    private fun writeHeader(s: Slot, quality: Int, width: Int, height: Int) {
        val header = BlJpegHeaders.forQuality(quality)
        System.arraycopy(header, 0, s.jpeg, 0, header.size)
        val o = BlJpegHeaders.SIZE_OFFSET
        s.jpeg[o] = (height ushr 8).toByte()
        s.jpeg[o + 1] = height.toByte()
        s.jpeg[o + 2] = (width ushr 8).toByte()
        s.jpeg[o + 3] = width.toByte()
        val eoi = BlJpegHeaders.LENGTH + s.dataLen
        s.jpeg[eoi] = 0xFF.toByte()
        s.jpeg[eoi + 1] = 0xD9.toByte()
    }

    /** Hands complete frames to the app in sequence order. */
    private fun deliverInOrder(): List<Frame> {
        val out = ArrayList<Frame>(1)
        val start = deliverIdx
        for (k in 0 until 4) {
            val s = slots[(start + k) % 4]
            if (s.state == COMPLETE && s.seq == nextSeq) {
                val size = BlJpegHeaders.LENGTH + s.dataLen + 2
                val rotation = if (s.hasExtra) parseRotation(s.extra) else null
                out += Frame(s.jpeg.copyOf(size), s.seq, rotation)
                s.state = DELIVERED
                nextSeq++
                deliverIdx = (deliverIdx + 1) % 4
            }
        }
        return out
    }

    /** Call about once per millisecond. Sends start commands and ACKs. */
    @Synchronized
    fun tick(now: Long) {
        if (!streaming) {
            if (now - lastStartMs > 100) {
                lastStartMs = now
                send(START_CMD)
            }
            return
        }
        val sinceFrag = now - lastFragMs
        if (sinceFrag > 2000) {
            reset(now)
            return
        }
        // No data for a second: the camera may expect the older ACK layout. Toggle it.
        // (The vendor library toggles on every 1 ms tick in this state. Once a second is enough.)
        if (sinceFrag > 1000 && now - lastLegacyToggleMs >= 1000) {
            legacyAck = !legacyAck
            lastLegacyToggleMs = now
        }
        if (now - lastAckMs > 24) {
            send(buildAck(if (sinceFrag > 19) CUR_SLOT_IDLE else curSlot))
            lastAckMs = now
        }
    }

    private fun reset(now: Long) {
        slots.forEach { it.clear() }
        streaming = false
        maxSeq = 0
        camAck = 0
        nextSeq = 1
        deliverIdx = 0
        curSlot = 0
        lastStartMs = now
        lastAckMs = now
        lastFragMs = now
    }

    /**
     * Builds the selective ACK. Each entry tells the camera which fragments of a frame we
     * already have (status 0, with a bitmap), that a frame is done (1), or that we are ready
     * for a sequence number (3).
     */
    internal fun buildAck(cur: Int): ByteArray {
        val buf = ByteArray(0x2F0)
        buf[0] = 0xEF.toByte()
        buf[1] = 2
        buf.putLe32(4, 0x01000202)
        var off = if (legacyAck) 0x10 else 0x20
        val statuses = ArrayList<Int>(5)

        fun entry(seq: Long, status: Int, bitmap: IntArray?, words: Int) {
            val entryLen = 16 + words * 4
            buf.putLe64(off, seq)
            buf.putLe32(off + 8, status.toLong())
            buf.putLe32(off + 12, entryLen.toLong())
            for (w in 0 until words) buf.putLe32(off + 16 + w * 4, bitmap!![w].toLong())
            off += entryLen
            statuses += status
        }

        for (i in 0 until 4) {
            if (i == cur) continue
            val s = slots[i]
            val recent = compareUnsigned(maxSeq - s.seq, 5) < 0 &&
                (s.state != EMPTY || compareUnsigned(maxSeq, (i + 1).toLong()) <= 0)
            if (recent) {
                if (compareUnsigned(camAck, s.seq) < 0 && s.state != EMPTY) {
                    val status = when (s.state) {
                        RECEIVING -> 0
                        COMPLETE, DELIVERED -> 1
                        UNUSED -> 2
                        else -> -1
                    }
                    entry(s.seq, status, s.bitmap, s.bitmapWords)
                }
            } else {
                entry(if (s.seq == 0L) (i + 1).toLong() else s.seq + 4, 3, null, 0)
            }
        }
        if (cur == CUR_SLOT_IDLE && statuses.all { it == 1 || it == 2 }) {
            entry(nextSeq, 3, null, 0)
        }
        if (!legacyAck) {
            // Quality parameters. The vendor app never changes the defaults.
            buf[0x18] = 10
            buf[0x19] = 75
            buf[0x1A] = 20
            buf[0x1B] = 45
            buf[0x1C] = 0 // Camera index.
        }
        buf[8] = statuses.size.toByte()
        // The vendor library sends 16 zero bytes more than it fills in. Do the same.
        val total = off + 16
        buf.putLe16(2, total)
        return buf.copyOf(total)
    }
}
