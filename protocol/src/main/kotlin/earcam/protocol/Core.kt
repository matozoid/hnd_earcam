package earcam.protocol

/** The three wire protocols the vendor app supports. Each matches a different camera chipset. */
enum class Protocol(val displayName: String) {
    /** UDP port 8800 with selective ACKs. Usually at 192.168.169.1. */
    BL("BL"),

    /** UDP ports 44506 (video) and 52219 (sensor), found by a broadcast on 46526. */
    BEKEN_TXIN("Beken TXIN"),

    /** UDP port 7080, with sensor data in every video packet. */
    VII_BEKEN("Vii Beken"),
}

/**
 * One complete video frame.
 *
 * @property jpeg a complete JPEG file (SOI to EOI)
 * @property seq increasing frame counter
 * @property rotationDeg device orientation reported by the camera's tilt sensor, in
 *   degrees, or null if unknown. Apps use it only when "auto-rotate" is turned on.
 */
class Frame(
    val jpeg: ByteArray,
    val seq: Long,
    val rotationDeg: Float?,
) {
    /** True if the data starts with a JPEG SOI marker. */
    val looksLikeJpeg: Boolean
        get() = jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()
}

/** A driver for one protocol. Drivers own their sockets and threads. */
interface CameraDriver : AutoCloseable {
    val protocol: Protocol

    /**
     * Opens the sockets and starts talking to the camera. Frames go to [onFrame] on a driver
     * thread. Throws if a socket can't be opened, for example when a port is already in use.
     */
    fun start(onFrame: (Frame) -> Unit)

    /** Asks the camera to stop, then closes all sockets. Safe to call more than once. */
    override fun close()
}

internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

internal fun ByteArray.u8(off: Int): Int = this[off].toInt() and 0xFF

internal fun ByteArray.le16(off: Int): Int = u8(off) or (u8(off + 1) shl 8)

internal fun ByteArray.le32(off: Int): Long =
    (u8(off).toLong()) or (u8(off + 1).toLong() shl 8) or
        (u8(off + 2).toLong() shl 16) or (u8(off + 3).toLong() shl 24)

internal fun ByteArray.le64(off: Int): Long = le32(off) or (le32(off + 4) shl 32)

internal fun ByteArray.putLe16(off: Int, v: Int) {
    this[off] = v.toByte()
    this[off + 1] = (v ushr 8).toByte()
}

internal fun ByteArray.putLe32(off: Int, v: Long) {
    for (i in 0 until 4) this[off + i] = (v ushr (8 * i)).toByte()
}

internal fun ByteArray.putLe64(off: Int, v: Long) {
    for (i in 0 until 8) this[off + i] = (v ushr (8 * i)).toByte()
}

internal fun bytesOf(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
