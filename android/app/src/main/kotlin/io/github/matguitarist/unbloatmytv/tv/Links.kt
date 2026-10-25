package io.github.matguitarist.unbloatmytv.tv

import android.os.Build
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.InstallResult
import io.github.matguitarist.unbloatmytv.UnbloatApp
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/** One open ADB connection to a TV. Every call blocks: [Tv] runs them off the main thread, one at a time. */
internal interface Link : AutoCloseable {
    fun shell(command: String): ShellResult
    fun push(file: File, remotePath: String)
    /** Null when it worked, else the TV's reason. */
    fun install(apk: File): String?
    fun execBytes(command: String): ByteArray
}

/** Network debugging on port 5555, through dadb. The TV asks "Allow USB debugging?" the first time. */
internal class DadbLink(private val d: Dadb) : Link {
    override fun shell(command: String): ShellResult = d.shell(command).let { ShellResult(it.exitCode, it.output, it.errorOutput) }

    override fun push(file: File, remotePath: String) {
        d.push(file, remotePath)
    }

    override fun install(apk: File): String? = when (val r = d.install(apk, "-r")) {
        is InstallResult.Failure -> r.reason.lines().lastOrNull { it.isNotBlank() }?.trim() ?: r.reason
        else -> null
    }

    override fun execBytes(command: String): ByteArray = d.open("exec:$command").use { it.source.readByteArray() }

    override fun close() = d.close()

    companion object {
        fun open(host: String, port: Int, connectTimeoutMs: Int, socketTimeoutMs: Int) =
            DadbLink(Dadb.create(host, port, AdbKeys.dadbPair, connectTimeout = connectTimeoutMs, socketTimeout = socketTimeoutMs))
    }
}

/**
 * Wireless debugging (Android 11 and newer): TLS on a port the TV picks, open only to keys paired
 * with a 6-digit code. Through libadb-android, which also does the pairing.
 */
internal class TlsLink private constructor(private val m: Manager) : Link {
    /** shell v2: stdout, stderr and the exit code come back as separate packets. */
    override fun shell(command: String): ShellResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        var exit = -1
        m.openStream("shell,v2,raw:$command").use { s ->
            val input = DataInputStream(s.openInputStream())
            val header = ByteArray(5)
            try {
                while (exit < 0) {
                    input.readFully(header)
                    val len = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val data = ByteArray(len).also(input::readFully)
                    when (header[0].toInt()) {
                        1 -> out.write(data)
                        2 -> err.write(data)
                        3 -> exit = data.firstOrNull()?.toInt()?.and(0xff) ?: 0
                    }
                }
            } catch (_: EOFException) {
                // The TV closed the stream without an exit code: the command was cut short.
            }
        }
        return ShellResult(exit, out.toString(Charsets.UTF_8.name()), err.toString(Charsets.UTF_8.name()))
    }

    /** The sync protocol, as "adb push" speaks it: SEND, DATA chunks, DONE, then OKAY or FAIL. */
    override fun push(file: File, remotePath: String) {
        m.openStream("sync:").use { s ->
            val out = s.openOutputStream()
            val input = DataInputStream(s.openInputStream())
            fun packet(id: String, length: Int, data: ByteArray = ByteArray(0), size: Int = data.size) {
                val b = ByteBuffer.allocate(8 + size).order(ByteOrder.LITTLE_ENDIAN)
                b.put(id.toByteArray(Charsets.US_ASCII)).putInt(length).put(data, 0, size)
                out.write(b.array())
                out.flush()
            }
            val target = "$remotePath,${"644".toInt(8)}".toByteArray()
            packet("SEND", target.size, target)
            file.inputStream().use { f ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = f.read(buf)
                    if (n < 0) break
                    packet("DATA", n, buf, n)
                }
            }
            packet("DONE", (file.lastModified() / 1000).toInt())
            val reply = ByteArray(8).also(input::readFully)
            val id = String(reply, 0, 4, Charsets.US_ASCII)
            if (id != "OKAY") {
                val len = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                throw IOException(String(ByteArray(len).also(input::readFully)))
            }
            runCatching { packet("QUIT", 0) }
        }
    }

    override fun install(apk: File): String? {
        val remote = "/data/local/tmp/unbloatmytv-install.apk"
        push(apk, remote)
        val r = shell("pm install -r '$remote'; rm -f '$remote'")
        return if ("Success" in r.output) null else r.all.lines().lastOrNull { it.isNotBlank() }?.trim() ?: r.all
    }

    override fun execBytes(command: String): ByteArray =
        m.openStream("exec:$command").use { it.openInputStream().readBytes() }

    // Not close(): libadb's close() also tries to destroy the private key, which is shared.
    override fun close() = m.disconnect()

    private class Manager : AbsAdbConnectionManager() {
        init {
            setApi(Build.VERSION_CODES.R) // Wireless debugging came with Android 11
            setTimeout(10, TimeUnit.SECONDS)
            setThrowOnUnauthorised(true)
        }

        override fun getPrivateKey(): PrivateKey = AdbKeys.privateKey
        override fun getCertificate(): Certificate = AdbKeys.certificate
        override fun getDeviceName(): String = AdbKeys.name
    }

    companion object {
        fun open(host: String, port: Int): TlsLink {
            val m = Manager()
            try {
                if (!m.connect(host, port)) throw IOException("no connection")
            } catch (e: Exception) {
                runCatching { m.disconnect() }
                throw e
            }
            return TlsLink(m)
        }

        /** Pairs this phone with the TV, with the code from "Pair device with pairing code". Throws if it fails. */
        fun pair(host: String, port: Int, code: String) {
            val m = Manager()
            try { m.pair(host, port, code) } finally { runCatching { m.disconnect() } }
        }
    }
}

/**
 * This phone's ADB key, made once. The same RSA key serves both kinds of connection, so the TV
 * knows the phone by one name whichever way it was allowed.
 */
internal object AdbKeys {
    private val privFile get() = File(UnbloatApp.dataDir, "adbkey")
    private val pubFile get() = File(UnbloatApp.dataDir, "adbkey.pub")

    /** What the TV lists the key as ("adb" shows the text after the key). */
    val name: String = "UnbloatMyTV@" + Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")

    val dadbPair: AdbKeyPair by lazy {
        if (!privFile.exists() || !pubFile.exists()) {
            AdbKeyPair.generate(privFile, pubFile)
            // dadb writes "unknown@unknown" after the key.
            pubFile.writeText(pubFile.readText().substringBefore(' ') + " " + name)
        }
        AdbKeyPair.read(privFile, pubFile)
    }

    /** The same key, from the PEM file dadb wrote (PKCS#8). */
    val privateKey: PrivateKey by lazy {
        dadbPair // makes the key if it isn't there yet
        val pem = privFile.readText().lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
    }

    /** TLS wants a certificate: a self-signed one around the key. The TV only looks at the key inside. */
    val certificate: Certificate by lazy {
        val priv = privateKey as RSAPrivateCrtKey
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
        val sigAlg = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val subject = X500Name("CN=$name")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(1))
            setIssuer(subject)
            setSubject(subject)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4_102_444_800_000))) // 2100
            setSignature(sigAlg)
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pub.encoded))
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(priv)
            update(tbs.encoded)
            sign()
        }
        val der = DERSequence(ASN1EncodableVector().apply { add(tbs); add(sigAlg); add(DERBitString(signature)) }).encoded
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
    }
}
