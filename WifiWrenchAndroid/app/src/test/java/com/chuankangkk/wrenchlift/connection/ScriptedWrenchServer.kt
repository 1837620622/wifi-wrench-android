package com.chuankangkk.wrenchlift.connection

import com.chuankangkk.wrenchlift.protocol.WrenchPacket
import com.chuankangkk.wrenchlift.protocol.WrenchPacketBuilder
import com.chuankangkk.wrenchlift.protocol.WrenchPacketParser
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 仅测试用的本机 TCP 扳手：按 C 协议拆包记录收到的帧；[replies] 为 true 时对 0x25 回 0x26 SN，
 * 为 false 时只接受 TCP、不回任何帧（模拟端口开着但不是扳手/扳手未回话）。
 */
class ScriptedWrenchServer(private val replies: Boolean = true) : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    val endpoint = WrenchEndpoint("127.0.0.1", port)
    val received = CopyOnWriteArrayList<WrenchPacket>()
    val accepted = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var client: Socket? = null
    /** 查 SN 时回复的 5 字节压缩 BCD；测试里改它可模拟「换了一台扳手」。 */
    @Volatile var snPayload: ByteArray = byteArrayOf(0x20, 0x26, 0x11, 0x05, 0x01)

    init {
        thread(isDaemon = true, name = "scripted-wrench-$port") {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    accepted.incrementAndGet()
                    client = socket
                    thread(isDaemon = true) { serve(socket) }
                }
            } catch (_: SocketException) {
            }
        }
    }

    /** 模拟扳手自己的地址码（协议字节 3）；默认与协议示例一致为 0x01。 */
    @Volatile var address: Int = WrenchPacket.DEFAULT_ADDRESS

    private fun serve(socket: Socket) {
        val parser = WrenchPacketParser()
        val buffer = ByteArray(1024)
        try {
            val input = socket.getInputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                parser.append(buffer.copyOfRange(0, count)).forEach { packet ->
                    received += packet
                    if (replies && packet.functionCode == WrenchPacket.FUNC_QUERY_SN) {
                        send(WrenchPacketBuilder.build(WrenchPacket.FUNC_REPLY_SN, snPayload, address = address))
                    }
                }
            }
        } catch (_: SocketException) {
        } finally {
            runCatching { socket.close() }
        }
    }

    fun send(packet: WrenchPacket) {
        val socket = client ?: error("尚无客户端连接")
        synchronized(this) {
            socket.getOutputStream().apply {
                write(packet.toFrame())
                flush()
            }
        }
    }

    fun functionCodes(): List<Int> = received.map { it.functionCode }

    /** 只断开当前客户端（模拟 Wi‑Fi 抖动）；服务端仍在监听，App 重连时会再次 accept。 */
    fun dropClient() {
        runCatching { client?.close() }
    }

    override fun close() {
        runCatching { client?.close() }
        runCatching { server.close() }
    }

    companion object {
        /** 一个立即拒绝连接的本机端口。 */
        fun closedEndpoint(): WrenchEndpoint {
            val socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val port = socket.localPort
            socket.close()
            return WrenchEndpoint("127.0.0.1", port)
        }
    }
}
