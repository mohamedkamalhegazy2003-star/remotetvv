package com.remotetv.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/** Minimal protobuf reader/writer - enough for the Android TV Remote v2 messages. */
object Pb {
    class W {
        private val o = ByteArrayOutputStream()
        fun varint(v: Long): W {
            var x = v
            while ((x and 0x7FL.inv()) != 0L) {
                o.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            o.write(x.toInt())
            return this
        }
        private fun tag(f: Int, wire: Int) = varint(((f shl 3) or wire).toLong())
        fun int(f: Int, v: Long): W { tag(f, 0); return varint(v) }
        fun int(f: Int, v: Int): W = int(f, v.toLong())
        fun bytes(f: Int, b: ByteArray): W { tag(f, 2); varint(b.size.toLong()); o.write(b); return this }
        fun str(f: Int, s: String): W = bytes(f, s.toByteArray())
        fun msg(f: Int, w: W): W = bytes(f, w.toByteArray())
        fun toByteArray(): ByteArray = o.toByteArray()
    }

    class Field(val num: Int, val value: Long, val data: ByteArray)

    private val EMPTY = ByteArray(0)

    fun parse(b: ByteArray): List<Field> {
        val res = ArrayList<Field>()
        var i = 0
        fun readVarint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val x = b[i++].toInt()
                r = r or ((x and 0x7F).toLong() shl s)
                if ((x and 0x80) == 0) break
                s += 7
            }
            return r
        }
        while (i < b.size) {
            val tag = readVarint().toInt()
            val wire = tag and 7
            val num = tag ushr 3
            when (wire) {
                0 -> res.add(Field(num, readVarint(), EMPTY))
                2 -> {
                    val n = readVarint().toInt()
                    res.add(Field(num, 0, b.copyOfRange(i, i + n)))
                    i += n
                }
                1 -> i += 8
                5 -> i += 4
                else -> return res
            }
        }
        return res
    }
}

/** Length-prefixed protobuf frames over a TLS socket. */
class Framed(private val sock: SSLSocket) {
    private val input = sock.inputStream
    private val output = sock.outputStream
    private val writeLock = Any()

    fun send(w: Pb.W) {
        val body = w.toByteArray()
        val frame = Pb.W().varint(body.size.toLong()).toByteArray() + body
        synchronized(writeLock) { output.write(frame); output.flush() }
    }

    fun read(): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Connection closed")
            len = len or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("Connection closed")
            off += n
        }
        return buf
    }

    fun close() { runCatching { sock.close() } }
}

object Atv {
    const val REMOTE_PORT = 6466
    const val PAIR_PORT = 6467
    const val ADB_PORT = 5555

    fun openTls(identity: TlsIdentity, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val s = identity.sslContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.startHandshake()
        return s
    }

    fun hexToBytes(h: String): ByteArray =
        ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun num(v: BigInteger): ByteArray {
        var h = v.toString(16)
        if (h.length % 2 == 1) h = "0$h"
        return hexToBytes(h)
    }
}

class WrongCodeException : Exception("Wrong code")

/** One-time pairing: the TV shows a 6-character code that the user types in the app. */
class Pairing(private val identity: TlsIdentity, private val host: String) {
    private var framed: Framed? = null
    private var serverCert: X509Certificate? = null

    fun begin() {
        val s = Atv.openTls(identity, host, Atv.PAIR_PORT, 8000)
        s.soTimeout = 30_000
        serverCert = s.session.peerCertificates[0] as X509Certificate
        val f = Framed(s)
        framed = f
        val encoding = Pb.W().int(1, 3).int(2, 6) // HEXADECIMAL, 6 symbols
        f.send(Pb.W().int(1, 2).int(2, 200).msg(10, Pb.W().str(1, "atvremote").str(2, "Remote TV")))
        expect(f, 11)
        f.send(Pb.W().int(1, 2).int(2, 200).msg(20, Pb.W().msg(1, encoding).int(3, 1)))
        expect(f, 20)
        f.send(Pb.W().int(1, 2).int(2, 200).msg(30, Pb.W().msg(1, encoding).int(2, 1)))
        expect(f, 31)
    }

    fun finish(code: String) {
        val f = framed ?: throw IOException("Pairing not started")
        val bytes = try {
            Atv.hexToBytes(code.trim())
        } catch (e: NumberFormatException) {
            throw WrongCodeException()
        }
        if (bytes.size != 3) throw WrongCodeException()
        val client = identity.certificate.publicKey as RSAPublicKey
        val server = serverCert!!.publicKey as RSAPublicKey
        val md = MessageDigest.getInstance("SHA-256")
        md.update(Atv.num(client.modulus))
        md.update(Atv.num(client.publicExponent))
        md.update(Atv.num(server.modulus))
        md.update(Atv.num(server.publicExponent))
        md.update(bytes, 1, bytes.size - 1)
        val hash = md.digest()
        if (hash[0] != bytes[0]) throw WrongCodeException()
        f.send(Pb.W().int(1, 2).int(2, 200).msg(40, Pb.W().bytes(1, hash)))
        expect(f, 41)
    }

    fun close() { framed?.close() }

    private fun expect(f: Framed, field: Int) {
        val m = Pb.parse(f.read())
        val status = m.firstOrNull { it.num == 2 }?.value
        if (status != null && status != 200L) throw IOException("TV refused pairing (status $status)")
        if (m.none { it.num == field }) throw IOException("Unexpected reply from TV")
    }
}

/** A live control connection to the TV (port 6466). */
class RemoteSession(private val identity: TlsIdentity, private val host: String) {
    @Volatile var ready = false
    @Volatile var deviceName = ""
    private var framed: Framed? = null

    /** Blocks, reading messages until the link dies (always ends with an exception). */
    fun run(onReady: () -> Unit) {
        val s = Atv.openTls(identity, host, Atv.REMOTE_PORT, 8000)
        s.soTimeout = 40_000 // the TV pings every few seconds; silence means the link is dead
        val f = Framed(s)
        framed = f
        try {
            while (true) {
                for (fld in Pb.parse(f.read())) {
                    when (fld.num) {
                        1 -> { // remote_configure
                            val info = Pb.parse(fld.data).firstOrNull { it.num == 2 }
                            if (info != null) {
                                deviceName = Pb.parse(info.data).firstOrNull { it.num == 1 }?.data?.toString(Charsets.UTF_8) ?: ""
                            }
                            val dev = Pb.W().str(1, "Remote TV").str(2, "RemoteTV").int(3, 1)
                                .str(4, "1").str(5, "com.remotetv.app").str(6, "1.0.0")
                            f.send(Pb.W().msg(1, Pb.W().int(1, 622).msg(2, dev)))
                        }
                        2 -> { // remote_set_active
                            f.send(Pb.W().msg(2, Pb.W().int(1, 622)))
                            if (!ready) { ready = true; onReady() }
                        }
                        3 -> throw IOException("TV rejected the connection")
                        8 -> { // ping request
                            val v = Pb.parse(fld.data).firstOrNull { it.num == 1 }?.value ?: 0L
                            f.send(Pb.W().msg(9, Pb.W().int(1, v)))
                        }
                        else -> {}
                    }
                }
            }
        } finally {
            f.close()
        }
    }

    /** direction: 1 = start long press, 2 = end long press, 3 = short press */
    fun sendKey(code: Int, direction: Int = 3) {
        val f = framed ?: throw IOException("Not connected")
        f.send(Pb.W().msg(10, Pb.W().int(1, code).int(2, direction)))
    }

    fun sendAppLink(link: String) {
        val f = framed ?: throw IOException("Not connected")
        f.send(Pb.W().msg(90, Pb.W().str(1, link)))
    }

    fun close() { framed?.close() }
}
