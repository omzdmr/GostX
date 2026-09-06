package cn.liukebin.gostx.anycast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

/** Minimal A-record resolver that bypasses broken local DNS by querying public resolvers directly. */
internal object AnycastDns {
    private val resolvers = listOf("223.5.5.5", "119.29.29.29")

    fun resolve(host: String): List<InetAddress> {
        runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (resolver in resolvers) {
            queryA(host, resolver)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        throw java.net.UnknownHostException(host)
    }

    private fun queryA(host: String, resolver: String): List<InetAddress>? {
        val id = ThreadLocalRandom.current().nextInt(0, 65536)
        val out = ByteBuffer.allocate(512)
        out.putShort(id.toShort())
        out.putShort(0x0100.toShort())
        out.putShort(1)
        out.putShort(0)
        out.putShort(0)
        out.putShort(0)
        host.trim('.').split('.').forEach { label ->
            val b = label.toByteArray(Charsets.US_ASCII)
            out.put(b.size.toByte())
            out.put(b)
        }
        out.put(0)
        out.putShort(1)
        out.putShort(1)
        val req = out.array().copyOf(out.position())

        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            socket.send(DatagramPacket(req, req.size, InetAddress.getByName(resolver), 53))
            val buf = ByteArray(1500)
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val data = buf.copyOf(pkt.length)
            if (data.size < 12) return null
            val bb = ByteBuffer.wrap(data)
            if ((bb.short.toInt() and 0xffff) != id) return null
            bb.short
            val qd = bb.short.toInt() and 0xffff
            val an = bb.short.toInt() and 0xffff
            bb.short; bb.short
            repeat(qd) { skipName(bb, data); bb.short; bb.short }
            val result = mutableListOf<InetAddress>()
            repeat(an) {
                skipName(bb, data)
                val type = bb.short.toInt() and 0xffff
                val clazz = bb.short.toInt() and 0xffff
                bb.int
                val len = bb.short.toInt() and 0xffff
                if (len < 0 || len > bb.remaining()) return@repeat
                if (type == 1 && clazz == 1 && len == 4) {
                    val addr = ByteArray(4); bb.get(addr); result += InetAddress.getByAddress(addr)
                } else bb.position(bb.position() + len)
            }
            return result
        }
    }

    private fun skipName(bb: ByteBuffer, data: ByteArray) {
        while (bb.hasRemaining()) {
            val n = bb.get().toInt() and 0xff
            if (n == 0) return
            if ((n and 0xc0) == 0xc0) { if (bb.hasRemaining()) bb.get(); return }
            if (n > bb.remaining()) return
            bb.position(bb.position() + n)
        }
    }
}
