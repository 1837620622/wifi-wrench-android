package com.chuankangkk.wrenchlift.protocol

data class ResultFrame(
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
    val targetAngleDeg: Double?,
    val actualAngleDeg: Double?,
    val torqueUpperNm: Double?,
    val torqueLowerNm: Double?,
    val angleUpperDeg: Double?,
    val angleLowerDeg: Double?,
    val rawHex: String,
) {
    /**
     * 协议 0x15：M1（0x01）与 M2（0x02）下同一位置的字段含义不同——
     * 28-29 M1 目标扭矩值 / M2 预设扭矩实际值；30-31 M1 目标扭矩实际值 / M2 最终扭矩实际值；
     * 32-33 M1 固定 0 / M2 目标角度设置值；34-35 M1 角度实际值 / M2 目标角度实际值；
     * 36-39 M1 目标扭矩上/下限 / M2 最终扭矩上/下限；40-43 M1 角度上/下限 / M2 目标角度上/下限。
     */
    val isM2: Boolean get() = mode == MODE_M2

    /** 结果帧里能读到的扭矩**设定值**：只有 M1 的 28-29 是目标扭矩；M2 的 28-29 是实测值，不能当设定。 */
    val settingTorqueNm: Double? get() = if (isM2) null else targetTorqueNm

    val modeText: String get() = modeLabel(mode)
    val torqueField1Name: String get() = if (isM2) "预设扭矩实际值" else "目标扭矩"
    val torqueField2Name: String get() = if (isM2) "最终扭矩实际值" else "目标扭矩实际值"
    val angleField1Name: String get() = if (isM2) "目标角度设置值" else "目标角度（M1 为 0）"
    val angleField2Name: String get() = if (isM2) "目标角度实际值" else "角度实际值"
    val torqueLimitName: String get() = if (isM2) "最终扭矩" else "目标扭矩"
    val angleLimitName: String get() = if (isM2) "目标角度" else "角度"

    companion object {
        const val MODE_M1 = 0x01
        const val MODE_M2 = 0x02

        fun modeLabel(mode: Int?): String = when (mode) {
            MODE_M1 -> "M1 扭矩模式"
            MODE_M2 -> "M2 扭矩+角度模式"
            null -> "模式未知"
            else -> "模式 0x%02X（协议未定义）".format(mode)
        }
    }
}

enum class ResultKind {
    OK,
    NG,
    UNKNOWN,
}

data class ResultStatus(
    val code: Int?,
    val kind: ResultKind,
    val label: String,
)

object ResultFrameDecoder {
    fun decode(packet: WrenchPacket): ResultFrame {
        require(packet.functionCode == WrenchPacket.FUNC_RESULT) { "不是 0x15 运行结果帧" }
        val payload = packet.payload
        val status = mapStatus(packet.reservedHigh)

        return ResultFrame(
            status = status,
            employeeId = safeAscii(payload, 0, 10),
            boltNo = safeU16(payload, 10) ?: 0,
            year = safeU16(payload, 12),
            month = safeByte(payload, 14),
            day = safeByte(payload, 15),
            hour = safeByte(payload, 16),
            minute = safeByte(payload, 17),
            second = safeByte(payload, 18),
            mode = safeByte(payload, 19),
            targetTorqueNm = safeU16(payload, 20)?.toDouble(),
            actualTorqueNm = safeU16(payload, 22)?.toDouble(),
            targetAngleDeg = safeU16(payload, 24)?.toDouble(),
            actualAngleDeg = safeU16(payload, 26)?.toDouble(),
            torqueUpperNm = safeU16(payload, 28)?.toDouble(),
            torqueLowerNm = safeU16(payload, 30)?.toDouble(),
            angleUpperDeg = safeU16(payload, 32)?.toDouble(),
            angleLowerDeg = safeU16(payload, 34)?.toDouble(),
            rawHex = packet.rawHex(),
        )
    }

    fun mapStatus(code: Int): ResultStatus = when (code and 0xFF) {
        0x00 -> ResultStatus(code, ResultKind.OK, "合格")
        0x01 -> ResultStatus(code, ResultKind.OK, "扭矩到达")
        0x02 -> ResultStatus(code, ResultKind.OK, "位置到达")
        0x10 -> ResultStatus(code, ResultKind.NG, "不合格")
        0x11 -> ResultStatus(code, ResultKind.NG, "扭矩偏低")
        0x12 -> ResultStatus(code, ResultKind.NG, "扭矩偏高")
        0x13 -> ResultStatus(code, ResultKind.NG, "疑似打滑")
        0x14 -> ResultStatus(code, ResultKind.NG, "角度偏小")
        0x15 -> ResultStatus(code, ResultKind.NG, "角度偏大")
        0x16 -> ResultStatus(code, ResultKind.NG, "重复拧紧")
        // 协议示例帧的状态字节是 0xFF（不在状态表里）：照原值写出，不猜成合格或不合格。
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
