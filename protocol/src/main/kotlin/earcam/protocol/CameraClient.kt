package earcam.protocol

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Finds the camera and keeps the video running.
 *
 * All three protocol drivers start at once. The first one to deliver a valid JPEG wins and
 * the others close. If the video stops for a few seconds, the search starts again.
 *
 * @param resolveCamera returns the camera's address. It is called again before each search,
 *   so the address can change, for example after a Wi-Fi switch.
 * @param driverFactory builds the drivers to try. Tests replace it.
 */
class CameraClient(
    private val resolveCamera: () -> InetAddress,
    private val listener: Listener,
    private val driverFactory: (InetAddress) -> List<CameraDriver> = ::defaultDrivers,
    private val searchTimeoutMs: Long = 8000,
    private val lostTimeoutMs: Long = 4000,
) : AutoCloseable {

    sealed interface Status {
        data class Searching(val address: InetAddress) : Status
        data class Streaming(val protocol: Protocol, val address: InetAddress) : Status
        data class Stopped(val reason: String? = null) : Status
    }

    interface Listener {
        /** Called on a driver thread. Don't block it for long. */
        fun onFrame(frame: Frame, protocol: Protocol)
        fun onStatus(status: Status)
        /** A driver could not start, for example because a port is in use. */
        fun onWarning(message: String) {}
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    @Volatile private var running = false
    private var supervisor: Thread? = null
    private var winner: CameraDriver? = null
    private var lastFrameMs = 0L

    fun start() {
        check(!running) { "already started" }
        running = true
        supervisor = thread(name = "camera-client", isDaemon = true) {
            try {
                superviseLoop()
            } catch (_: InterruptedException) {
            }
            listener.onStatus(Status.Stopped())
        }
    }

    private fun superviseLoop() {
        while (running) {
            val address = try {
                resolveCamera()
            } catch (e: Exception) {
                listener.onWarning("Can't find camera address: ${e.message}")
                Thread.sleep(2000)
                continue
            }
            listener.onStatus(Status.Searching(address))

            val drivers = startDrivers(address)
            if (drivers.isEmpty()) {
                Thread.sleep(2000)
                continue
            }
            try {
                val found = awaitWinner()
                drivers.filter { it !== found }.forEach { it.close() }
                if (found == null) continue
                listener.onStatus(Status.Streaming(found.protocol, address))
                awaitLoss()
            } finally {
                // Also runs when close() interrupts us.
                drivers.forEach { it.close() }
                lock.withLock { winner = null }
            }
        }
    }

    private fun startDrivers(address: InetAddress): List<CameraDriver> {
        lock.withLock { winner = null }
        val started = ArrayList<CameraDriver>()
        for (driver in driverFactory(address)) {
            try {
                driver.start { frame -> onDriverFrame(driver, frame) }
                started += driver
            } catch (e: Exception) {
                driver.close()
                listener.onWarning("${driver.protocol.displayName}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return started
    }

    private fun onDriverFrame(driver: CameraDriver, frame: Frame) {
        lock.withLock {
            if (winner == null) {
                if (!running || !frame.looksLikeJpeg) return
                winner = driver
                changed.signalAll()
            } else if (winner !== driver) {
                return
            }
            lastFrameMs = monotonicMs()
        }
        listener.onFrame(frame, driver.protocol)
    }

    private fun awaitWinner(): CameraDriver? = lock.withLock {
        val deadline = monotonicMs() + searchTimeoutMs
        while (running && winner == null) {
            val left = deadline - monotonicMs()
            if (left <= 0) break
            changed.await(left, TimeUnit.MILLISECONDS)
        }
        winner
    }

    private fun awaitLoss() {
        while (running) {
            val idle = lock.withLock { monotonicMs() - lastFrameMs }
            if (idle > lostTimeoutMs) return
            Thread.sleep(250)
        }
    }

    override fun close() {
        running = false
        lock.withLock { changed.signalAll() }
        supervisor?.interrupt()
        supervisor?.join(2000)
        supervisor = null
    }

    companion object {
        fun defaultDrivers(address: InetAddress): List<CameraDriver> = listOf(
            BlDriver(InetSocketAddress(address, BlSession.CAMERA_PORT)),
            BekenTxinDriver(address),
            ViiBekenDriver(address),
        )
    }
}
