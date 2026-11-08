package com.chuankangkk.wrenchlift.measurement

import com.chuankangkk.wrenchlift.protocol.WrenchPacket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MeasurementCsvExporter {
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun buildFileName(state: MeasurementState, nowMillis: Long = System.currentTimeMillis()): String {
        val slab = state.session.slabNo.sanitizeFilePart()
        val point = state.session.pointNo.sanitizeFilePart()
        return "WifiWrench_${slab}_${point}_${fileTimeFormat.format(Date(nowMillis))}.csv"
    }

    /** 记录导出文件名：只有一台扳手，按记录号 + 开始时间命名，如 `WifiWrench_R12_20261104_234612.xlsx`。 */
    fun recordFileName(recordId: Long, startMillis: Long, extension: String, slabNo: String = "", pointNo: String = ""): String {
        val place = listOf(slabNo, pointNo).map { it.sanitizeFilePart() }.filter { it.isNotBlank() }.joinToString("_")
        val placePart = if (place.isBlank()) "" else "_$place"
        return "WifiWrench_R${recordId}${placePart}_${synchronized(fileTimeFormat) { fileTimeFormat.format(Date(startMillis)) }}.$extension"
    }

    fun buildCsv(state: MeasurementState): String {
        require(state.hasWrenchMeasurementData()) {
            "没有真实扳手测量数据，不能导出 CSV"
        }
        val builder = StringBuilder()
        builder.append('\uFEFF')
        appendRow(builder, headers)
        val points = state.recentPoints
        if (points.isEmpty()) {
            appendRow(builder, state.summaryRow())
        } else {
            points.forEachIndexed { index, point ->
                appendRow(builder, state.pointRow(index + 1, point))
            }
        }
        return builder.toString()
    }

    fun writeHeader(output: Appendable) {
        output.append('\uFEFF')
        appendRow(output, headers)
    }

    fun writePoint(output: Appendable, state: MeasurementState, index: Int, point: MeasuredPoint) =
        appendRow(output, state.pointRow(index, point, includeTransientStatus = false))

    fun writeSummary(output: Appendable, state: MeasurementState) = appendRow(output, state.summaryRow())

    /** 原始 HEX 第 4 字节为功能码；用于导出里的「帧类型」列。 */
    fun frameLabelFromRawHex(rawHex: String): String =
        rawHex.split(' ', limit = 5).getOrNull(3)?.toIntOrNull(16)?.exportFunctionLabel() ?: ""

    private fun appendRow(builder: Appendable, values: List<String>) {
        values.joinTo(builder, separator = ",") { it.csvCell() }
        builder.append('\n')
    }

    private fun MeasurementState.summaryRow(): List<String> = listOf(
        session.projectName,
        session.lineName,
        session.sectionName,
        session.slabNo,
        session.pointNo,
        session.operatorName,
        deviceSn.ifBlank { session.deviceSn },
        employeeId,
        resultStatus,
        dataOrigin().dashboardLabel,
        currentWifiName,
        localIp,
        gatewayIp,
        host,
        port,
        "0",
        currentBoltNo.toString(),
        lastFrameReceiveTime?.toString().orEmpty(),
        currentTorqueNm.formatNumber(),
        currentAngleDeg.formatNumber(),
        currentForceKn.formatNullable(),
        currentPressureMpa.formatNullable(),
        currentDisplacementMm.formatNumber(),
        warningLevel.exportLabel(),
        warningMessage,
        lastPacketFunction?.exportFunctionLabel() ?: "",
        lastRawHex,
    )

    private fun MeasurementState.pointRow(index: Int, point: MeasuredPoint, includeTransientStatus: Boolean = true): List<String> = listOf(
        session.projectName,
        session.lineName,
        session.sectionName,
        session.slabNo,
        session.pointNo,
        session.operatorName,
        deviceSn.ifBlank { session.deviceSn },
        employeeId,
        resultStatus,
        dataOrigin().dashboardLabel,
        currentWifiName,
        localIp,
        gatewayIp,
        host,
        port,
        index.toString(),
        point.boltNo.toString(),
        point.sourcePoint.timestampMillis.toString(),
        point.sourcePoint.torqueNm.formatNumber(),
        point.sourcePoint.angleDeg.formatNumber(),
        point.forceKn.formatNullable(),
        point.pressureMpa.formatNullable(),
        if (includeTransientStatus) point.displacementMm.formatNumber() else "",
        if (includeTransientStatus) point.warningLevel.exportLabel() else "",
        if (includeTransientStatus) warningMessage else "",
        lastPacketFunction?.exportFunctionLabel() ?: "",
        point.rawHex,
    )

    private fun String.csvCell(): String {
        // 文本元数据不能被 Excel 当作公式执行；数值列由程序格式化，不含公式前缀。
        val safe = if (trimStart().firstOrNull() in listOf('=', '+', '@') || startsWith("\t") || startsWith("\r") || (trimStart().startsWith("-") && toDoubleOrNull() == null)) "'" + this else this
        val escaped = safe.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun String.sanitizeFilePart(): String =
        trim()
            .ifBlank { "未填写" }
            .map { char ->
                if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
            }
            .joinToString("")
            .take(32)

    private fun Double.formatNumber(): String = "%.4f".format(Locale.US, this)

    private fun Double?.formatNullable(): String = this?.formatNumber() ?: ""

    private fun WarningLevel.exportLabel(): String = when (this) {
        WarningLevel.NORMAL -> "正常"
        WarningLevel.WATCH -> "观察"
        WarningLevel.WARNING -> "预警"
        WarningLevel.ERROR -> "危险"
    }

    /** 功能码的中文类型名（导出「帧类型」「通信记录」共用）。 */
    fun functionLabel(code: Int): String = code.exportFunctionLabel()

    private fun Int.exportFunctionLabel(): String = when (this) {
        WrenchPacket.FUNC_STOP_RUN -> "扳手动作"
        WrenchPacket.FUNC_STATUS -> "设备状态"
        WrenchPacket.FUNC_ACK -> "设备应答"
        WrenchPacket.FUNC_BATTERY -> "电量回传"
        WrenchPacket.FUNC_REMOTE_CONTROL -> "手机控制"
        WrenchPacket.FUNC_PARAM_SET -> "参数设置"
        WrenchPacket.FUNC_TORQUE_ANGLE -> "扭矩转角过程"
        WrenchPacket.FUNC_RESULT -> "最终结果"
        WrenchPacket.FUNC_RESULT_CONFIRM -> "结果确认"
        WrenchPacket.FUNC_TIME_REQUEST -> "请求校时"
        WrenchPacket.FUNC_TIME_SYNC -> "时间校准"
        WrenchPacket.FUNC_NUT_RESET -> "螺母计数"
        WrenchPacket.FUNC_QUERY_SN -> "查询编号"
        WrenchPacket.FUNC_REPLY_SN -> "设备编号"
        WrenchPacket.FUNC_HEARTBEAT -> "连接保活"
        WrenchPacket.FUNC_GPS -> "位置数据"
        WrenchPacket.FUNC_PULSE_PARAM_SET_RC283F,
        WrenchPacket.FUNC_PULSE_PARAM_SET_RCH -> "特殊模式参数"
        WrenchPacket.FUNC_PULSE_RESULT -> "特殊模式结果"
        else -> "未识别数据"
    }

    private val headers = listOf(
        "项目",
        "线路",
        "区间",
        "板号",
        "点位",
        "操作员",
        "设备SN",
        "工号",
        "结果状态",
        "数据来源",
        "当前WiFi",
        "本机IP",
        "默认网关",
        "设备IP",
        "TCP端口",
        "序号",
        "螺栓号",
        "时间戳",
        "扭矩Nm",
        "转角deg",
        "顶升力kN",
        "压力MPa",
        "位移mm",
        "预警级别",
        "现场判断",
        "最近帧类型",
        "原始HEX",
    )
}
