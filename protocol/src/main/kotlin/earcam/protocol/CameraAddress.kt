package earcam.protocol

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Guesses the camera's IP address on desktop systems.
 *
 * The camera is the Wi-Fi access point, so it is normally the default gateway. A machine can
 * have several gateways, for example Ethernet plus Wi-Fi. In that case the one that looks most
 * like a camera wins: 192.168.169.x (BL cameras), then 192.168.1.x (Beken), then any other
 * 192.168.x.x. Without any candidate this falls back to 192.168.169.1, like the vendor app.
 */
object CameraAddress {
    val DEFAULT: InetAddress = InetAddress.getByName("192.168.169.1")

    fun guess(): InetAddress = pickBest(gatewayCandidates() + localSubnetCandidates()) ?: DEFAULT

    /** Picks the address that looks most like a camera, or null if the list is empty. */
    fun pickBest(candidates: List<InetAddress>): InetAddress? =
        candidates.distinct().minByOrNull { rank(it.hostAddress) }

    private fun rank(ip: String): Int = when {
        ip.startsWith("192.168.169.") -> 0
        ip.startsWith("192.168.1.") -> 1
        ip.startsWith("192.168.") -> 2
        ip.startsWith("192.") -> 3
        else -> 4
    }

    /** IPv4 default gateways from the OS routing table. */
    fun gatewayCandidates(): List<InetAddress> {
        val os = System.getProperty("os.name").lowercase()
        val ips = try {
            when {
                os.contains("linux") -> parseLinuxRoutes(File("/proc/net/route").readText())
                os.contains("win") -> parseWindowsRoutes(run("route", "print", "-4"))
                else -> parseBsdRoutes(run("netstat", "-rn", "-f", "inet")) // macOS
            }
        } catch (_: Exception) {
            emptyList()
        }
        return ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    /**
     * If this machine has an address in a camera subnet, the camera is usually at .1.
     * The vendor app makes the same guess when the phone's IP contains "192.168.169.".
     */
    private fun localSubnetCandidates(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .filter { it.startsWith("192.168.169.") }
            .map { InetAddress.getByName("192.168.169.1") }
    } catch (_: Exception) {
        emptyList()
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        return out
    }

    /** /proc/net/route: `Iface Destination Gateway Flags ...` with little-endian hex addresses. */
    internal fun parseLinuxRoutes(text: String): List<String> =
        text.lines().drop(1).mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 3 || f[1] != "00000000" || f[2] == "00000000") return@mapNotNull null
            val g = f[2].toLongOrNull(16) ?: return@mapNotNull null
            "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
        }

    /** `route print -4`: `0.0.0.0  0.0.0.0  <gateway>  <interface>  <metric>`. */
    internal fun parseWindowsRoutes(text: String): List<String> =
        text.lines().mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size >= 3 && f[0] == "0.0.0.0" && f[1] == "0.0.0.0" && IPV4.matches(f[2])) f[2] else null
        }

    /** `netstat -rn`: `default  <gateway>  <flags>  <iface>`. */
    internal fun parseBsdRoutes(text: String): List<String> =
        text.lines().mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size >= 2 && (f[0] == "default" || f[0] == "0.0.0.0") && IPV4.matches(f[1])) f[1] else null
        }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
