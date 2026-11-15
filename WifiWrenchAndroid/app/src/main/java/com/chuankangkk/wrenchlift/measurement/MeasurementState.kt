package com.chuankangkk.wrenchlift.measurement

import com.chuankangkk.wrenchlift.connection.ConnectionState
import com.chuankangkk.wrenchlift.connection.WrenchEndpoint
import com.chuankangkk.wrenchlift.data.MeasurementEntity
import com.chuankangkk.wrenchlift.protocol.WifiWrenchParameterSettings
import com.chuankangkk.wrenchlift.protocol.PulseResultFrame
import com.chuankangkk.wrenchlift.protocol.ResultFrame
import com.chuankangkk.wrenchlift.protocol.TorqueAnglePoint

data class MeasuredPoint(
    val sourcePoint: TorqueAnglePoint,
    val boltNo: Int,
    val forceKn: Double?,
    val pressureMpa: Double? = null,
    val displacementMm: Double = 0.0,
    val warningLevel: WarningLevel,
    val rawHex: String,
    /** 本点在整条记录里的序号（从 1 起，与导出「过程数据」序号一致；0 表示未知，按缓冲推算）。 */
    val seq: Long = 0L,
)

data class MeasurementState(
    val targetWifiName: String = "",
    val persistedPointCount: Long = 0,
    val exportInProgress: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val host: String = "192.168.4.1",
    val port: String = "7888",
    val session: MeasurementSession = MeasurementSession(),
    val currentBoltNo: Int = 0,
    val currentTorqueNm: Double = 0.0,
    val currentAngleRaw: Int = 0,
    val currentAngleDeg: Double = 0.0,
    val currentForceKn: Double? = null,
    val currentPressureMpa: Double? = null,
    val currentDisplacementMm: Double = 0.0,
    val employeeId: String = "--",
    val batteryPercent: Int? = null,
    val deviceSn: String = "",
    /** 本机信任的扳手编号：第一台回话的 SN。与 [deviceSn] 不一致时说明不是同一台，或有人在冒充。 */
    val deviceSnPinned: String = "",
    val deviceSnMismatch: Boolean = false,
    val currentWifiName: String = "--",
    val localIp: String = "--",
    val gatewayIp: String = "--",
    val inWrenchSubnet: Boolean = false,
    val deviceIpReachable: Boolean? = null,
    val devicePortReachable: Boolean? = null,
    val gatewayPortReachable: Boolean? = null,
    val discoveredEndpoint: String = "--",
    val networkDiagnosticSummary: String = "尚未诊断网络",
    val lastFrameReceiveTime: Long? = null,
    val lastCurvePointReceiveTime: Long? = null,
    val receivedFrameCount: Int = 0,
    val lastPacketFunction: Int? = null,
    val lastCommandLabel: String = "尚未发送命令",
    val lastSentHex: String = "",
    val parameterMode: Int = WifiWrenchParameterSettings.MODE_M2,
    val parameterTargetTorque: String = "180",
    val parameterTorqueLower: String = "200",
    val parameterTorqueUpper: String = "500",
    val parameterTargetAngle: String = "90",
    val parameterAngleUpper: String = "360",
    val parameterAngleLower: String = "1",
    val parameterStatus: String = "参数未写入",
    val saveStatus: String = "尚未保存",
    val resultStatus: String = "等待结果",
    val lastResult: ResultFrame? = null,
    val lastPulseResult: PulseResultFrame? = null,
    val lastProtocolSummary: String = "等待设备数据",
    val warningLevel: WarningLevel = WarningLevel.NORMAL,
    val warningMessage: String = "等待采集",
    val recentPoints: List<MeasuredPoint> = emptyList(),
    val recentSessions: List<MeasurementEntity> = emptyList(),
    val effectivePointCount: Int = 0,
    val lastRawHex: String = "",
    val logLines: List<String> = listOf("系统就绪，请先连接扳手 Wi-Fi，再检查连接。"),
    // ---- 正式工人页：连接阶段、远程门闩、导出与偏好 ----
    val linkPhase: LinkPhase = LinkPhase.IDLE,
    val wifiAvailable: Boolean = false,
    val autoConnect: Boolean = true,
    val activeEndpoint: WrenchEndpoint? = null,
    val lastGoodEndpoint: WrenchEndpoint? = null,
    val probeReport: ProbeReport = ProbeReport(),
    val remoteEnabled: Boolean = false,
    val motion: MotionCommand = MotionCommand.NONE,
    val controlMessage: String = "",
    val currentSessionId: Long? = null,
    val lastExport: ExportOutcome? = null,
    val exportCsvToo: Boolean = false,
    val darkTheme: Boolean = true,
    val deviceErrorCode: Int? = null,
    val deviceReverseDirection: Boolean? = null,
    val wrenchModel: WrenchModel = WrenchModel.TORQUE,
    val pulseStatus: String = "",
)

fun MeasurementState.hasWrenchMeasurementData(): Boolean =
    recentPoints.isNotEmpty() ||
        lastResult.hasActualMeasurement() ||
        lastPulseResult.hasActualMeasurement()

fun MeasurementState.canConfirmEffectivePoint(): Boolean =
    recentPoints.isNotEmpty()

fun MeasurementState.canRemoveEffectivePoint(): Boolean =
    effectivePointCount > 0

fun MeasurementState.measurementGateStatus(): String = when {
    hasWrenchMeasurementData() -> saveStatus
    receivedFrameCount > 0 -> "已收设备帧，等待真实扭矩/角度"
    else -> "等待真实扳手测量数据"
}

fun MeasurementState.exportReadinessText(): String = when {
    recentPoints.isNotEmpty() -> "可导出：${recentPoints.size} 个真实曲线点和原始 HEX"
    lastResult.hasActualMeasurement() -> "可导出：普通最终结果和原始 HEX"
    lastPulseResult.hasActualMeasurement() -> "可导出：特殊模式结果和原始 HEX"
    receivedFrameCount > 0 -> "暂不能导出：已收设备帧，等待真实扭矩/角度"
    else -> "暂不能导出：等待真实扳手测量数据"
}

fun MeasurementState.exportDestinationText(): String =
    "CSV 位置：下载/WifiWrenchExports；失败时回落到 App 专属目录"

fun MeasurementState.saveExportEvidenceText(): String = when {
    !hasWrenchMeasurementData() -> measurementGateStatus()
    saveStatus == "尚未保存" -> exportReadinessText()
    else -> saveStatus
}

private fun ResultFrame?.hasActualMeasurement(): Boolean =
    this?.actualTorqueNm != null && actualAngleDeg != null

private fun PulseResultFrame?.hasActualMeasurement(): Boolean =
    this?.actualTorqueNm != null && actualAngleDeg != null
