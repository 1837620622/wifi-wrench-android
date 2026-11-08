package com.chuankangkk.wrenchlift.measurement

import com.chuankangkk.wrenchlift.connection.WrenchEndpoint

/** 连接阶段（工人只看白话状态，端点细节只进高级诊断）。 */
enum class LinkPhase {
    /** 没有在连：刚结束会话，或还没开始。 */
    IDLE,

    /** 想连但系统没连 Wi‑Fi。 */
    NO_WIFI,

    /** 后台自适应探测中。 */
    PROBING,

    /** TCP 已连且收到合法 C 协议帧。 */
    READY,

    /** TCP 已连但扳手还没回话（握手待确认）。 */
    WAITING_REPLY,

    /** 本轮所有候选端点都没连通。 */
    FAILED,

    /** 连上后又断了。 */
    DROPPED,
}

/** 扳手类型：决定参数侧滑下发 0x10（定扭矩）还是 0x50 / 0x51（脉冲、离合、冲击）。默认定扭矩。 */
enum class WrenchModel(val label: String, val functionLabel: String, val shortLabel: String) {
    TORQUE("定扭矩扳手", "0x10", "定扭矩"),
    PULSE_RC283F("脉冲扳手（RC283F 驱动）", "0x50", "脉冲 RC283F"),
    PULSE_RCH("脉冲扳手（RCH 驱动）", "0x51", "脉冲 RCH"),
}

/** 脉冲类参数草稿（文本输入，保存时校验）。 */
data class PulseDraft(
    val mode: Int,
    val targetTorque: String = "",
    val k1: String = "",
    val k2: String = "",
    val k3: String = "",
    val presetIndex: String = "1",
    val setTorqueRaw: String = "",
    val boltStatus: String = "0",
    val precision: String = "0",
    val boltCount: String = "0",
)

/** App 最后一次成功发出的运动指令。协议没有可靠的实时方向回读，这里只代表「App 发了什么」。 */
enum class MotionCommand { NONE, FORWARD, REVERSE, STOPPED }

/** 设计规范 §8 远程门闩：远程=关 → 正/反转禁用；任意状态停止可用；结束会话 ≠ 停止。 */
object RemoteGate {
    fun canToggleRemote(state: MeasurementState): Boolean = state.connectionState.isOnline

    fun canMove(state: MeasurementState): Boolean = state.connectionState.isOnline && state.remoteEnabled

    /** 停止按钮始终可点；离线时点了也只能提示到扳手上停。 */
    fun canSendStop(state: MeasurementState): Boolean = state.connectionState.isOnline

    fun canWriteParameters(state: MeasurementState): Boolean = state.connectionState.isOnline
}

/** 工人可见的白话文案（主流程不出现端口/IP/TCP/协议码）。 */
object WorkerText {
    fun link(state: MeasurementState): String = when (state.linkPhase) {
        LinkPhase.READY -> "已连上扳手"
        LinkPhase.WAITING_REPLY -> "已接通，等扳手回话"
        LinkPhase.PROBING -> "正在连接扳手…"
        LinkPhase.NO_WIFI -> "请先在系统设置连接扳手 Wi‑Fi"
        LinkPhase.FAILED -> "连接失败"
        LinkPhase.DROPPED -> "已断开"
        LinkPhase.IDLE -> "未连接扳手"
    }

    /** 顶栏状态条用的短句（可点：失败/断开 → 重试，未连 Wi‑Fi → 去连接）。 */
    fun linkShort(state: MeasurementState): String = when (state.linkPhase) {
        LinkPhase.READY -> "已连上扳手"
        LinkPhase.WAITING_REPLY -> "等扳手回话"
        LinkPhase.PROBING -> "正在连接扳手…"
        LinkPhase.NO_WIFI -> "未连扳手 Wi‑Fi"
        LinkPhase.FAILED -> "连接失败 · 重试"
        LinkPhase.DROPPED -> "已断开 · 重试"
        LinkPhase.IDLE -> "未连接 · 点此连接"
    }

    /** 窄屏/大字体顶栏用的最短说法。 */
    fun linkTiny(state: MeasurementState): String = when (state.linkPhase) {
        LinkPhase.READY -> "已连上"
        LinkPhase.WAITING_REPLY -> "等回话"
        LinkPhase.PROBING -> "连接中"
        LinkPhase.NO_WIFI -> "未连 Wi‑Fi"
        LinkPhase.FAILED -> "失败·重试"
        LinkPhase.DROPPED -> "断开·重试"
        LinkPhase.IDLE -> "点此连接"
    }

    /** 读数格式：协议过程值是整数时不显示无意义的 .0。 */
    fun readout(value: Double?, hasData: Boolean): String {
        if (!hasData || value == null || !value.isFinite()) return "--"
        return if (kotlin.math.abs(value - Math.rint(value)) < 1e-9) "%.0f".format(java.util.Locale.US, value)
        else "%.1f".format(java.util.Locale.US, value)
    }

    /** 失败/断开时补一句可操作的提示。 */
    fun linkHint(state: MeasurementState): String? = when (state.linkPhase) {
        LinkPhase.FAILED, LinkPhase.DROPPED -> "请确认仍在扳手 Wi‑Fi 下"
        LinkPhase.NO_WIFI -> "连好后会自动接上"
        LinkPhase.WAITING_REPLY -> "请确认扳手已开机"
        else -> null
    }

    fun remote(state: MeasurementState): String = if (state.remoteEnabled) "远程已启用" else "远程未启用"

    /** 能不能转、正在干什么。 */
    fun motion(state: MeasurementState): String = when {
        !state.connectionState.isOnline && state.motion in setOf(MotionCommand.FORWARD, MotionCommand.REVERSE) ->
            "连接已断，扳手可能仍在转，请在扳手上停止"
        !state.connectionState.isOnline -> "连上扳手后才能操作"
        !state.remoteEnabled -> "先开远程，才能转动"
        state.motion == MotionCommand.FORWARD -> "正转中"
        state.motion == MotionCommand.REVERSE -> "反转中"
        state.motion == MotionCommand.STOPPED -> "已停止"
        else -> "可以转动"
    }

    /** 窄控制柱 / 大字体用的短说法（意思不变，只更短）。 */
    fun motionShort(state: MeasurementState): String = when {
        !state.connectionState.isOnline && state.motion in setOf(MotionCommand.FORWARD, MotionCommand.REVERSE) -> "请在扳手上停止"
        !state.connectionState.isOnline -> "未连接扳手"
        !state.remoteEnabled -> "先开远程"
        state.motion == MotionCommand.FORWARD -> "正转中"
        state.motion == MotionCommand.REVERSE -> "反转中"
        state.motion == MotionCommand.STOPPED -> "已停止"
        else -> "可以转动"
    }

    fun deviceErrorShort(state: MeasurementState): String? =
        state.deviceErrorCode?.takeIf { it != 0 }?.let { "报错 $it" }

    /** 0x05 错误码非 0 时给工人一句话；具体码值只作参考，含义以厂家为准。 */
    fun deviceError(state: MeasurementState): String? =
        state.deviceErrorCode?.takeIf { it != 0 }?.let { "扳手报错（代码 $it）" }

    fun battery(state: MeasurementState): String = state.batteryPercent?.let { "电量 $it%" } ?: "电量 --"

    fun sn(state: MeasurementState): String =
        state.deviceSn.takeIf { it.isNotBlank() && it != "未知设备" }?.let { "SN $it" } ?: "SN --"

    /** 曲线新鲜度：与心跳无关，只看最近一次过程点的接收时间。 */
    fun freshness(state: MeasurementState, nowMillis: Long): Freshness {
        val last = state.lastCurvePointReceiveTime ?: return Freshness.Waiting
        if (!state.connectionState.isOnline) return Freshness.Retained
        val ageSeconds = ((nowMillis - last).coerceAtLeast(0L)) / 1000L
        return if (ageSeconds <= FRESH_SECONDS) Freshness.Live else Freshness.Delayed(ageSeconds)
    }

    const val FRESH_SECONDS = 3L
}

sealed interface Freshness {
    data object Waiting : Freshness
    data object Live : Freshness
    data class Delayed(val seconds: Long) : Freshness
    data object Retained : Freshness
}

/** 高级诊断里可以看到的探测记录。 */
data class ProbeReport(
    val tried: List<WrenchEndpoint> = emptyList(),
    val selected: WrenchEndpoint? = null,
    val handshake: Boolean = false,
    val summary: String = "尚未探测",
)

/** 导出结果：工人只看「导出完成（含图表）」，路径与分享在对话框里。 */
data class ExportOutcome(
    val success: Boolean,
    val message: String,
    val displayPath: String = "",
    val shareUri: String? = null,
    val mimeType: String = XLSX_MIME,
) {
    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val ZIP_MIME = "application/zip"
    }
}
