package io.github.matguitarist.unbloatmytv.tv

import io.github.matguitarist.unbloatmytv.UnbloatApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ShellResult(val exitCode: Int, val output: String, val error: String) {
    val ok: Boolean get() = exitCode == 0
    val all: String get() = "$output\n$error".trim()
}

/**
 * One TV, reached over the network with ADB, with no adb program: network debugging on port 5555
 * (the TV asks "Allow USB debugging?"), or Wireless debugging on the port it picks, after pairing
 * with a code. Commands run one at a time.
 */
class Tv private constructor(val host: String, port: Int, @Volatile private var link: Link) {
    var port: Int = port
        private set
    val serial: String get() = "$host:$port"
    /** Wireless debugging (paired with a code): its port changes whenever it is turned on again. */
    val isWireless: Boolean get() = port != PORT
    private val lock = Mutex()

    /**
     * Runs a shell command and returns its output. A dropped connection is opened again once.
     * A reply can take at most [READ_TIMEOUT_MS] between two pieces of output: long silent commands
     * go through [shellLong].
     */
    suspend fun shell(command: String): ShellResult =
        try { use { it.shell(command) } } catch (e: Exception) { ShellResult(-1, "", e.message ?: e.javaClass.simpleName) }

    /** Copies a file into the TV, e.g. to its temp folder. Throws when it can't. */
    suspend fun push(file: File, remotePath: String) {
        use { it.push(file, remotePath) }
    }

    /** Installs or updates an app. Null when it worked, else the TV's reason. */
    suspend fun install(apk: File): String? =
        try { use { it.install(apk) } } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    /**
     * A command's raw output, e.g. a screenshot. Not queued behind other commands: the connection
     * carries several at once, so the remote's keys don't wait for the picture. Null when it fails.
     */
    suspend fun execBytes(command: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { link.execBytes(command) }.getOrNull()
    }

    /** Runs [block] on the connection, one at a time. A dropped connection is opened again once. */
    private suspend fun <T> use(block: (Link) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                block(link)
            } catch (e: Exception) {
                // The TV restarted adbd, or the Wi-Fi blinked: one fresh connection, then give up.
                runCatching { link.close() }
                reopen()
                block(link)
            }
        }
    }

    /** A new connection to the same TV. Wireless debugging may have moved to another port meanwhile. */
    private suspend fun reopen(connectTimeoutMs: Int = 5_000) {
        if (isWireless) {
            val p = Mdns.connectPort(UnbloatApp.instance, host) ?: port
            link = TlsLink.open(host, p)
            port = p
        } else {
            link = DadbLink.open(host, port, connectTimeoutMs, READ_TIMEOUT_MS)
        }
    }

    /** For commands that stay silent for minutes: a dot every 20 s keeps the connection's read timeout away. */
    suspend fun shellLong(command: String): ShellResult =
        shell("($command) >/dev/null 2>&1 & p=\$!; while kill -0 \$p 2>/dev/null; do echo .; sleep 20; done; wait \$p")

    /** Restart the TV and wait until it has finished starting, up to [timeoutMs]. */
    suspend fun rebootAndWait(timeoutMs: Long = 4 * 60_000): Boolean {
        runCatching { withContext(Dispatchers.IO) { link.shell("reboot") } }
        delay(8_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val back = lock.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        runCatching { link.close() }
                        reopen(connectTimeoutMs = 3_000)
                        link.shell("getprop sys.boot_completed").output.trim() == "1"
                    }.getOrDefault(false)
                }
            }
            if (back) return true
            delay(3_000)
        }
        return false
    }

    fun close() {
        runCatching { link.close() }
    }

    companion object {
        const val PORT = 5555
        /** Also how long the TV's "Allow USB debugging?" question can wait for an answer. */
        private const val READ_TIMEOUT_MS = 120_000

        /**
         * Connects and checks that the TV accepts this phone. On port 5555 the TV asks "Allow USB
         * debugging?" the first time: this waits for the answer, up to two minutes. Any other port is
         * Wireless debugging, which only lets in a paired phone ([WirelessNotPaired] otherwise).
         * Throws with a short reason when it can't connect.
         */
        suspend fun connect(host: String, port: Int = PORT): Tv = withContext(Dispatchers.IO) {
            if (port == PORT) {
                val link = DadbLink.open(host, port, 5_000, READ_TIMEOUT_MS)
                try {
                    // The first command opens the connection, which is when the TV asks.
                    val r = link.shell("echo ok")
                    if (!r.output.contains("ok")) throw IOException(r.error.ifBlank { "no answer" })
                } catch (e: Exception) {
                    runCatching { link.close() }
                    throw e
                }
                return@withContext Tv(host, port, link)
            }
            // The port typed or remembered may be stale: Wireless debugging picks a new one each time.
            val ports = listOfNotNull(port, Mdns.connectPort(UnbloatApp.instance, host, 3_000)).distinct()
            var last: Exception? = null
            for (p in ports) {
                try {
                    val link = TlsLink.open(host, p)
                    if (link.shell("echo ok").output.contains("ok")) return@withContext Tv(host, p, link)
                    link.close()
                } catch (e: Exception) {
                    last = e
                }
            }
            throw WirelessNotPaired(last)
        }

        /** Pairs with the code from "Pair device with pairing code". Throws when the TV refuses. */
        suspend fun pair(host: String, port: Int, code: String) = withContext(Dispatchers.IO) {
            TlsLink.pair(host, port, code)
        }
    }
}

/** Wireless debugging didn't let the phone in: not paired yet, or the address is out of date. */
class WirelessNotPaired(cause: Exception?) : IOException("not paired", cause)
