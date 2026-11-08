package com.chuankangkk.wrenchlift.protocol

/**
 * C 协议拆帧。[onDiscard] 报告被丢掉的数据，供诊断记录：
 * `badChecksum = true` 是帧头、长度齐全但校验和不对的那一帧（随后逐字节重新找帧头）；
 * `false` 是最终没能组成任何合法帧、被丢弃的字节（含校验错帧里的字节）。
 */
class WrenchPacketParser(
    private val onDiscard: (bytes: ByteArray, badChecksum: Boolean) -> Unit = { _, _ -> },
) {
    private var buffer = ByteArray(0)

    fun append(chunk: ByteArray): List<WrenchPacket> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk

        val packets = mutableListOf<WrenchPacket>()
        var index = 0
        var skippedFrom = -1
        while (index <= buffer.size - WrenchPacket.MIN_FRAME_SIZE) {
            if (!isHeaderAt(index)) {
                if (skippedFrom < 0) skippedFrom = index
                index += 1
                continue
            }

            val payloadLength = buffer[index + 6].toInt() and 0xFF
            val totalLength = WrenchPacket.MIN_FRAME_SIZE + payloadLength
            if (index + totalLength > buffer.size) break

            val raw = buffer.copyOfRange(index, index + totalLength)
            if (Checksum.isValid(raw)) {
                if (skippedFrom >= 0) {
                    onDiscard(buffer.copyOfRange(skippedFrom, index), false)
                    skippedFrom = -1
                }
                packets += WrenchPacket(
                    address = raw[2].toInt() and 0xFF,
                    functionCode = raw[3].toInt() and 0xFF,
                    reservedHigh = raw[4].toInt() and 0xFF,
                    reservedLow = raw[5].toInt() and 0xFF,
                    payload = raw.copyOfRange(7, raw.lastIndex),
                    raw = raw,
                    checksum = raw.last().toInt() and 0xFF,
                )
                index += totalLength
            } else {
                onDiscard(raw, true)
                if (skippedFrom < 0) skippedFrom = index
                index += 1
            }
        }
        if (skippedFrom >= 0) onDiscard(buffer.copyOfRange(skippedFrom, index), false)

        buffer = buffer.copyOfRange(index, buffer.size)
        // 先解析再限长：大块数据一次到达时不会在解析前被截掉；只有长期无法成帧的垃圾才会被丢弃，
        // 且保留一帧最大长度减 1 字节，跨块的半帧不受影响。
        if (buffer.size > MAX_BUFFER_SIZE) {
            onDiscard(buffer.copyOfRange(0, buffer.size - (MAX_FRAME_SIZE - 1)), false)
            buffer = buffer.copyOfRange(buffer.size - (MAX_FRAME_SIZE - 1), buffer.size)
        }
        return packets
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    private fun isHeaderAt(index: Int): Boolean {
        val first = buffer[index].toInt() and 0xFF
        val second = buffer[index + 1].toInt() and 0xFF
        return (first == WrenchPacket.HEADER_FIRST && second == WrenchPacket.HEADER_SECOND) ||
            (first == WrenchPacket.ALT_HEADER_FIRST && second == WrenchPacket.ALT_HEADER_SECOND)
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 4096
        /** 数据长度字段 1 字节：一帧最多 8 + 255 字节。 */
        const val MAX_FRAME_SIZE = WrenchPacket.MIN_FRAME_SIZE + 0xFF
    }
}
