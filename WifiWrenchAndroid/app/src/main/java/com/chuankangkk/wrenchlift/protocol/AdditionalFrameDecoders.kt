package com.chuankangkk.wrenchlift.protocol

data class AckFrame(
    val isAck: Boolean,
    val label: String,
    val rawHex: String,
)

object AckDecoder {
    fun decode(packet: WrenchPacket): AckFrame {
        require(packet.functionCode == WrenchPacket.FUNC_ACK) { "不是 0x06 ACK/NAK 帧" }
        val value = packet.payload.firstOrNull()?.toInt()?.and(0xFF)
        val isAck = value == 0x00
        return AckFrame(
            isAck = isAck,
            label = if (isAck) "应答正常" else "设备拒绝",
            rawHex = packet.rawHex(),
        )
    }
}

data class DeviceSnFrame(
    val sn: String,
    val rawHex: String,
)

object DeviceSnDecoder {
    fun decode(packet: WrenchPacket): DeviceSnFrame {
        require(packet.functionCode == WrenchPacket.FUNC_REPLY_SN) { "不是 0x26 SN 应答帧" }
        return DeviceSnFrame(
            sn = packet.payload.joinToString("") { byte ->
                val value = byte.toInt() and 0xFF
                "%X%X".format(value ushr 4, value and 0x0F)
            },
            rawHex = packet.rawHex(),
        )
    }
}

data class DeviceStatusFrame(
    val bytes: List<Int>,
    val rawHex: String,
) {
    /** 协议 0x05 字节 8-9：错误码，0 表示无错误。 */
    val errorCode: Int? = if (bytes.size >= 2) (bytes[0] shl 8) or bytes[1] else null

    /** 协议 0x05 字节 10-11：控制位。 */
    val controlBits: Int? = if (bytes.size >= 4) (bytes[2] shl 8) or bytes[3] else null

    /** 协议「Bit1：正向 0 / 反向 1」，按 Bit1 = 最低位理解；位序待真机确认，只用于诊断显示。 */
    val reverseDirection: Boolean? = controlBits?.let { (it and 0x0001) != 0 }

    val summary: String = if (bytes.isEmpty()) {
        "状态数据为空"
    } else {
        "状态数据 ${bytes.size} 字节"
    }
}

object DeviceStatusDecoder {
    fun decode(packet: WrenchPacket): DeviceStatusFrame {
        require(packet.functionCode == WrenchPacket.FUNC_STATUS) { "不是 0x05 状态帧" }
        return DeviceStatusFrame(
            bytes = packet.payload.map { it.toInt() and 0xFF },
            rawHex = packet.rawHex(),
        )
    }
}

data class NutResetFrame(
    val option: Int,
    val label: String,
    val rawHex: String,
)

object NutResetDecoder {
    fun decode(packet: WrenchPacket): NutResetFrame {
        require(packet.functionCode == WrenchPacket.FUNC_NUT_RESET) { "不是 0x23 螺母计数帧" }
        val option = packet.payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        val label = when (option) {
            0x00 -> "退出螺母计数模式"
            0x01 -> "按原螺母数重新开始计数运行"
            else -> "未知螺母计数选项"
        }
        return NutResetFrame(option = option, label = label, rawHex = packet.rawHex())
    }
}

data class GpsFrame(
    val valid: Boolean,
    val text: String,
    val rawHex: String,
)

object GpsDecoder {
    fun decode(packet: WrenchPacket): GpsFrame {
        require(packet.functionCode == WrenchPacket.FUNC_GPS) { "不是 0x44 GPS 帧" }
        val valid = packet.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x01
        val text = if (packet.payload.size > 1) {
            packet.payload.copyOfRange(1, packet.payload.size).decodeToString().trim()
        } else {
            ""
        }
        return GpsFrame(valid = valid, text = text, rawHex = packet.rawHex())
    }
}

data class PulseResultFrame(
    val status: ResultStatus,
    val employeeId: String,
    val boltNo: Int,
    val year: Int?,
    val month: Int?,
    val day: Int?,
    val hour: Int?,
    val minute: Int?,
    val second: Int?,
    val mode: Int?,
    val targetTorqueNm: Double?,
    val actualTorqueNm: Double?,
    val actualAngleDeg: Double?,
    val rawHex: String,
) {
    val modeText: String get() = modeLabel(mode)

    companion object {
        /** 协议 0x55 字节 27：参数模式（RC283F / RCH 驱动器）。 */
        fun modeLabel(mode: Int?): String = when (mode) {
            0x00 -> "RC283F 扭矩模式"
            0x01 -> "定点硬连接(H)"
            0x02 -> "定点软连接(S)"
            0x03 -> "RCH MP 任意参数"
            null -> "模式未知"
            else -> "模式 0x%02X（协议未定义）".format(mode)
        }
    }
}

object PulseResultFrameDecoder {
    fun decode(packet: WrenchPacket): PulseResultFrame {
        require(packet.functionCode == WrenchPacket.FUNC_PULSE_RESULT) { "不是 0x55 脉冲类结果帧" }
        val payload = packet.payload
        return PulseResultFrame(
            status = mapStatus(packet.reservedHigh),
            employeeId = safeAscii(payload, 0, 10),
            boltNo = safeU16(payload, 10) ?: 0,
            year = safeU16(payload, 12),
            month = safeByte(payload, 14),
            day = safeByte(payload, 15),
            hour = safeByte(payload, 16),
            minute = safeByte(payload, 17),
            second = safeByte(payload, 18),
            mode = safeByte(payload, 19),
            targetTorqueNm = safeU16(payload, 20)?.div(10.0),
            actualTorqueNm = safeU16(payload, 22)?.div(10.0),
            actualAngleDeg = safeU16(payload, 24)?.toDouble(),
            rawHex = packet.rawHex(),
        )
    }

    /**
     * 协议 0x55 字节 5 只定义了 0x00 成功 OK、0x10 失败 NG；0x15 的细分码（0x01 扭矩到达、0x13 打滑……）
     * 不适用于 0x55，其余值按「未定义状态」原样写出，不借用 0x15 的含义。
     */
    fun mapStatus(code: Int): ResultStatus = when (code and 0xFF) {
        0x00 -> ResultStatus(code, ResultKind.OK, "合格")
        0x10 -> ResultStatus(code, ResultKind.NG, "不合格")
        else -> ResultStatus(code, ResultKind.UNKNOWN, "未定义状态 0x%02X".format(code and 0xFF))
    }

    private fun safeByte(data: ByteArray, offset: Int): Int? =
        data.getOrNull(offset)?.let { it.toInt() and 0xFF }

    private fun safeU16(data: ByteArray, offset: Int): Int? =
        if (offset + 1 < data.size) readU16(data, offset) else null

    private fun safeAscii(data: ByteArray, offset: Int, length: Int): String {
        if (offset >= data.size) return ""
        val end = minOf(offset + length, data.size)
        return data.copyOfRange(offset, end).decodeToString().trimEnd('\u0000', ' ')
    }
}
