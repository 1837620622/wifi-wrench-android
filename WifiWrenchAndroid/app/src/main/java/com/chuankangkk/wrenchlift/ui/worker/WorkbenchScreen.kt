package com.chuankangkk.wrenchlift.ui.worker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chuankangkk.wrenchlift.measurement.Freshness
import com.chuankangkk.wrenchlift.measurement.LinkPhase
import com.chuankangkk.wrenchlift.measurement.MeasurementState
import com.chuankangkk.wrenchlift.measurement.MotionCommand
import com.chuankangkk.wrenchlift.measurement.RemoteGate
import com.chuankangkk.wrenchlift.measurement.TorqueAngleView
import com.chuankangkk.wrenchlift.measurement.analysis.WorkingCondition
import com.chuankangkk.wrenchlift.measurement.TorqueAngleWindow
import com.chuankangkk.wrenchlift.measurement.WorkerText
import com.chuankangkk.wrenchlift.measurement.hasWrenchMeasurementData
import kotlinx.coroutines.delay

/**
 * 正式工人工作台（设计规范 §4 单屏分区）：A 顶栏 · B 左读数 · C 中主图 · D 右控制柱。
 * 只有一台扳手：连上 Wi‑Fi 直接用，不填板号/测点。
 * 主台无纵向滚动；参数走右侧侧滑；返回 = 结束会话并回到记录页（不等于停止扳手）。
 */
@Composable
fun WorkbenchScreen(
    state: MeasurementState,
    actions: WorkerActions,
    onLeave: () -> Unit,
    nowMillis: Long = rememberNowMillis(),
) {
    val p = palette
    var confirmMotion by remember { mutableStateOf<MotionCommand?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showParams by rememberSaveable { mutableStateOf(false) }
    var chartMode by rememberSaveable { mutableStateOf(ChartMode.TORQUE_ANGLE) }
    // 缓冲区首点在本记录里的序号：图上「#序号」与导出「过程数据」序号列一一对应。
    val firstSeq = (state.persistedPointCount - state.recentPoints.size + 1).coerceAtLeast(1)
    val view = remember(state.recentPoints, firstSeq) { TorqueAngleWindow.view(state.recentPoints, firstSeq = firstSeq) }
    val sessionActive = state.connectionState.isOnline || state.linkPhase in setOf(LinkPhase.PROBING, LinkPhase.READY, LinkPhase.WAITING_REPLY, LinkPhase.DROPPED, LinkPhase.FAILED, LinkPhase.NO_WIFI)
    val requestLeave = { if (sessionActive) confirmLeave = true else onLeave() }
    BackHandler(enabled = !showParams) { requestLeave() }

    LaunchedEffect(state.controlMessage) {
        if (state.controlMessage.isNotBlank()) {
            delay(6_000)
            actions.clearControlMessage()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(p.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val compact = maxHeight < 430.dp
        // 系统栏可见 / 分屏 / 大字体时的矮屏：压缩顶栏与间距，按钮按 48dp 起算，保证 6 个控制键都在屏内；
        // 极矮时控制柱放不下状态行，报错/操作反馈改到读数柱底行。
        val short = maxHeight < 340.dp
        val tiny = maxHeight < 300.dp
        // 以 480dp 高的横屏手机为基准，平板/大屏按高度等比放大主读数（相对关系不变）。
        val readoutScale = (maxHeight.value / 480f).coerceIn(1f, 1.8f)
        Column(Modifier.fillMaxSize().padding(horizontal = WrenchDimens.screenEdge)) {
            WorkbenchTopBar(state, actions, onBack = requestLeave, short = short)
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = if (compact) 6.dp else 8.dp, bottom = if (short) 4.dp else if (compact) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(WrenchDimens.gap),
            ) {
                BoxWithConstraints(Modifier.weight(0.24f).fillMaxHeight()) {
                    // 读数柱越高，数字越大（宽度不够时 FitNumber 再缩），保证主读数占柱高的主要部分。
                    val columnScale = (maxHeight.value / 260f).coerceIn(1f, 2.4f).coerceAtLeast(readoutScale)
                    ReadoutPanel(state, view, nowMillis, columnScale, compact, Modifier.fillMaxSize(), short = short, tiny = tiny)
                }
                val live = WorkerText.freshness(state, nowMillis) == Freshness.Live
                ChartPanel(state, view, chartMode, { chartMode = it }, compact, short, live, Modifier.weight(0.51f).fillMaxHeight())
                ControlPanel(
                    state = state,
                    actions = actions,
                    compact = compact,
                    short = short,
                    tiny = tiny,
                    onForward = { confirmMotion = MotionCommand.FORWARD },
                    onReverse = { confirmMotion = MotionCommand.REVERSE },
                    onParams = { showParams = true },
                    modifier = Modifier.weight(0.25f).fillMaxHeight(),
                )
            }
        }
        ParameterSheet(
            visible = showParams,
            state = state,
            onDismiss = { showParams = false },
            onSave = { draft ->
                actions.writeParameters(draft)
                showParams = false
            },
            onSavePulse = { draft ->
                actions.writePulseParameters(draft)
                showParams = false
            },
        )
    }

    confirmMotion?.let { direction ->
        val forward = direction == MotionCommand.FORWARD
        WrenchDialog(
            title = if (forward) "确认正转？" else "确认反转？",
            message = if (forward) "确认以正转方向驱动扳手？请确认现场安全。" else "确认以反转方向驱动扳手？请确认现场安全。",
            confirmText = if (forward) "正转" else "反转",
            onConfirm = {
                confirmMotion = null
                if (forward) actions.forward() else actions.reverse()
            },
            onDismiss = { confirmMotion = null },
            modifier = Modifier.testTag("confirm_motion"),
        )
    }

    if (confirmLeave) {
        val mayBeMoving = state.remoteEnabled || state.motion == MotionCommand.FORWARD || state.motion == MotionCommand.REVERSE
        WrenchDialog(
            title = "结束会话？",
            message = "结束仅断开本机会话与采集，不会自动停止扳手。是否仍要结束？",
            confirmText = if (mayBeMoving) "直接结束" else "结束",
            confirmTone = ButtonTone.Neutral,
            onConfirm = {
                confirmLeave = false
                actions.endSession(false)
                onLeave()
            },
            onDismiss = { confirmLeave = false },
            extra = if (mayBeMoving && state.connectionState.isOnline) {
                {
                    ControlButton("先停止扳手再结束", WrenchIcon.Stop, onClick = {
                        confirmLeave = false
                        actions.endSession(true)
                        onLeave()
                    }, tone = ButtonTone.Danger, height = WrenchDimens.touch)
                }
            } else {
                null
            },
            modifier = Modifier.testTag("confirm_leave"),
        )
    }

    ExportDialogs(state, actions)
}

@Composable
fun rememberNowMillis(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

// ---------------- A 顶栏 ----------------

/** 顶栏：分格显示 SN · 电量 · 通信 · 远程 · 已记录，最右「返回」。只有一台扳手，不显示板号/测点。 */
@Composable
private fun WorkbenchTopBar(state: MeasurementState, actions: WorkerActions, onBack: () -> Unit, short: Boolean = false) {
    val p = palette
    val battery = state.batteryPercent
    val linkAction: (() -> Unit)? = when (state.linkPhase) {
        LinkPhase.FAILED, LinkPhase.DROPPED, LinkPhase.IDLE -> actions.connect
        LinkPhase.NO_WIFI -> actions.openWifiSettings
        else -> null
    }
    val barHeight = if (short) WrenchDimens.touch else WrenchDimens.topBarHeight
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = if (short) 2.dp else 6.dp)
            .heightIn(min = barHeight)
            .clip(WrenchDimens.panelShape)
            .background(p.panel)
            .border(1.dp, p.hairline, WrenchDimens.panelShape)
            .testTag("top_bar"),
    ) {
        // 窄屏或大字体：值用最短说法、SN 缩字号，保证各格都看得见不被截断。
        val tight = maxWidth / LocalDensity.current.fontScale < 700.dp
        Row(Modifier.fillMaxWidth().heightIn(min = barHeight).padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusCell("SN", state.deviceSn.takeIf { it.isNotBlank() && it != "未知设备" } ?: "--", Modifier.weight(1.15f), tight = tight, small = tight, dense = short)
            CellDivider()
            StatusCell(
                "电量", battery?.let { "$it%" } ?: "--", Modifier.weight(0.8f), tight = tight, dense = short,
                valueColor = if (battery != null && battery < 20) p.danger else p.textPrimary,
                icon = if (tight) null else { { LineIcon(WrenchIcon.Battery, if (battery != null && battery < 20) p.danger else p.textSecondary, size = 16.dp, level = (battery ?: 0) / 100f) } },
            )
            CellDivider()
            StatusCell(
                "通信", if (tight) WorkerText.linkTiny(state) else WorkerText.linkShort(state), Modifier.weight(if (tight) 1.05f else 1.3f).testTag("link_status"),
                valueColor = linkColor(state.linkPhase), dot = linkColor(state.linkPhase), onClick = linkAction, tight = tight, dense = short,
            )
            CellDivider()
            // 远程：已开 = 青绿；已连上但未开 = 琥珀（提醒工人先开远程才能转）；未连接 = 灰（不是警告，只是不可用）。
            val remoteColor = when {
                state.remoteEnabled -> p.accent
                state.connectionState.isOnline -> p.warn
                else -> p.textSecondary
            }
            StatusCell(
                "远程", if (state.remoteEnabled) "已启用" else "未启用", Modifier.weight(0.9f).testTag("remote_status"),
                valueColor = remoteColor, dot = remoteColor, tight = tight, dense = short,
            )
            CellDivider()
            // 本次已入库点数：工人能看到数据确实在记录（导出即这些点的全量）。
            StatusCell("已记录", "${state.persistedPointCount} 点", Modifier.weight(0.95f).testTag("stored_count"), tight = tight, dense = short)
            Box(Modifier.padding(start = 4.dp)) {
                BackChip(onBack, Modifier.testTag("back"), tight = tight)
            }
        }
    }
}

@Composable
private fun StatusCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = palette.textPrimary,
    emphasize: Boolean = false,
    dot: Color? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    tight: Boolean = false,
    small: Boolean = false,
    dense: Boolean = false,
) {
    val p = palette
    Column(
        modifier
            .heightIn(min = WrenchDimens.touch)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = if (tight) 6.dp else 10.dp, vertical = if (dense) 1.dp else 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = WrenchType.labelStyle.copy(lineHeight = if (dense) 14.sp else 17.sp), color = p.textSecondary, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(if (tight) 6.dp else 8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(if (tight) 3.dp else 5.dp))
            }
            if (icon != null) {
                icon()
                Spacer(Modifier.width(4.dp))
            }
            val base = when {
                small -> WrenchType.valueStyle.copy(fontSize = 14.sp)
                emphasize -> WrenchType.valueStyle.copy(fontSize = 17.sp)
                else -> WrenchType.valueStyle
            }
            // 值放不下（窄屏 + 大字体时的 10 位 SN）就缩字号，不出省略号。
            FitText(value, base.copy(fontFeatureSettings = "tnum", lineHeight = if (dense) 18.sp else base.lineHeight), color = valueColor, minSize = 11.sp)
        }
    }
}

@Composable
private fun CellDivider() {
    Box(Modifier.width(1.dp).height(26.dp).background(palette.hairline))
}

@Composable
private fun BackChip(onBack: () -> Unit, modifier: Modifier = Modifier, tight: Boolean = false) {
    val p = palette
    // 触控区保持 48dp 高；可见边框上下各内缩 4dp，矮屏顶栏也正好 48dp 时不会和顶栏边框贴成一条、像被裁掉。
    Row(
        modifier
            .heightIn(min = WrenchDimens.touch)
            .clickable(role = Role.Button, onClick = onBack)
            .padding(vertical = 4.dp)
            .clip(WrenchDimens.buttonShape)
            .border(1.dp, p.hairline, WrenchDimens.buttonShape)
            .padding(start = if (tight) 4.dp else 8.dp, end = if (tight) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(WrenchIcon.Back, p.textPrimary, size = 20.dp)
        Spacer(Modifier.width(2.dp))
        Text("返回", style = WrenchType.buttonStyle, color = p.textPrimary)
    }
}

@Composable
fun linkColor(phase: LinkPhase): Color {
    val p = palette
    return when (phase) {
        LinkPhase.READY -> p.ok
        LinkPhase.WAITING_REPLY, LinkPhase.PROBING -> p.warn
        LinkPhase.FAILED, LinkPhase.DROPPED, LinkPhase.NO_WIFI -> p.danger
        LinkPhase.IDLE -> p.textSecondary
    }
}

// ---------------- B 左读数 ----------------

@Composable
private fun ReadoutPanel(
    state: MeasurementState,
    view: TorqueAngleView,
    nowMillis: Long,
    scale: Float,
    compact: Boolean,
    modifier: Modifier,
    short: Boolean = false,
    tiny: Boolean = false,
) {
    val p = palette
    val hasData = state.hasWrenchMeasurementData()
    val freshness = WorkerText.freshness(state, nowMillis)
    val live = freshness == Freshness.Live || freshness is Freshness.Delayed
    // 实时 → 断开/延迟时读数颜色渐变变暗（300ms），提示「这是保留值」。
    val torqueColor by animateColorAsState(
        when {
            live -> p.accent
            hasData -> p.accent.copy(alpha = 0.6f)
            else -> p.textTertiary
        },
        tween(300), label = "torque_color",
    )
    val angleColor by animateColorAsState(if (live) p.textPrimary else if (hasData) p.textSecondary else p.textTertiary, tween(300), label = "angle_color")
    Panel(modifier.testTag("readouts"), padding = PaddingValues(horizontal = 14.dp, vertical = if (short) 6.dp else if (compact) 10.dp else 14.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // 两个大数字按本区高度再算一次上限：宽度、高度都放得下才用，数字绝不被标签/峰值行压住。
            val fontScale = LocalDensity.current.fontScale
            val showPeak = maxHeight > 130.dp
            val captionDp = 17f * fontScale * (if (showPeak) 3 else 2)
            val budget = (maxHeight.value - captionDp - 14f).coerceAtLeast(40f)
            val ratio = WrenchType.title.value / WrenchType.display.value
            val heightCapSp = budget / (fontScale * 1.18f * (1f + ratio))
            val displaySize = minOf(WrenchType.display.value * scale, heightCapSp).coerceAtLeast(22f).sp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                Column(Modifier.testTag("readout_torque")) {
                    ReadoutLabel("扭矩")
                    FitNumberWithUnit(WorkerText.readout(state.currentTorqueNm, hasData), "N·m", displaySize, torqueColor, Modifier.fillMaxWidth())
                    if (showPeak) {
                        val peak = view.currentPeak?.torque
                        Text(
                            "峰值 " + (if (hasData && peak != null) "${WorkerText.readout(peak, true)} N·m" else "--"),
                            style = WrenchType.labelStyle, color = p.textSecondary, maxLines = 1,
                        )
                    }
                }
                Hairline()
                Column(Modifier.testTag("readout_angle")) {
                    ReadoutLabel("转角")
                    FitNumberWithUnit(WorkerText.readout(state.currentAngleDeg, hasData), "°", displaySize * ratio, angleColor, Modifier.fillMaxWidth())
                }
            }
        }
        Hairline(Modifier.padding(vertical = if (short) 4.dp else 8.dp))
        // 极矮屏时控制柱没有状态行：报错 / 操作反馈在这里顶替新鲜度行。这里一行很窄，报错用短说法（「报错 18」），不被省略成「扳手报…」。
        val shortError = WorkerText.deviceErrorShort(state)
        val notice = if (tiny) shortError ?: state.controlMessage.takeIf { it.isNotBlank() } else null
        if (notice != null) {
            Text(notice, style = WrenchType.labelStyle, color = if (shortError != null) p.danger else p.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("control_message"))
        } else {
            FreshnessLine(freshness, hasData)
        }
        if (!short) {
            val result = state.lastResult
            val resultText = when {
                result != null -> "结果 ${result.status.label}" + (result.actualTorqueNm?.let { " · ${WorkerText.readout(it, true)} N·m" } ?: "")
                state.lastPulseResult != null -> "结果 ${state.lastPulseResult.status.label}"
                else -> "结果 --"
            }
            Text(resultText, style = WrenchType.labelStyle, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ReadoutLabel(text: String) {
    Text(text, style = WrenchType.labelStyle.copy(lineHeight = 17.sp), color = palette.textSecondary, maxLines = 1)
}

@Composable
private fun FreshnessLine(freshness: Freshness, hasData: Boolean) {
    val p = palette
    val (text, color) = when (freshness) {
        Freshness.Live -> "实时更新" to p.ok
        is Freshness.Delayed -> "数据延迟 ${freshness.seconds} 秒" to p.warn
        Freshness.Retained -> "已断开 · 保留值" to p.textSecondary
        Freshness.Waiting -> (if (hasData) "保留值" else "等待数据") to p.textTertiary
    }
    // 实时更新时圆点缓慢呼吸；延迟/断开时静止。
    val dotAlpha = if (freshness == Freshness.Live) {
        val transition = rememberInfiniteTransition(label = "fresh")
        transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "fresh_dot").value
    } else {
        1f
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("freshness")) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = color.alpha * dotAlpha)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = WrenchType.labelStyle, color = color, maxLines = 1)
    }
}

// ---------------- C 中主图 ----------------

@Composable
private fun ChartPanel(
    state: MeasurementState,
    view: TorqueAngleView,
    mode: ChartMode,
    onMode: (ChartMode) -> Unit,
    compact: Boolean,
    short: Boolean,
    live: Boolean,
    modifier: Modifier,
) {
    val (emptyTitle, emptyHint) = when {
        state.connectionState.isOnline && !state.remoteEnabled -> "等待扳手数据" to "开远程并转动后自动画线"
        state.connectionState.isOnline -> "等待扳手数据" to "扳手转动后自动画线"
        else -> WorkerText.link(state) to (WorkerText.linkHint(state) ?: "连上扳手后自动画线")
    }
    Panel(modifier, padding = PaddingValues(start = 8.dp, end = 8.dp, top = if (compact) 6.dp else 8.dp, bottom = 6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 宽：页签按内容宽靠左；窄 / 大字体：页签平分整行，字号按实测文字宽度收，不截断。
            // 右侧工况徽标只在放得下时出现（按字体缩放后的宽度判断），窄屏让给页签；明细都在导出。
            val headerWidth = maxWidth
            val fontScale = LocalDensity.current.fontScale
            val wide = headerWidth / fontScale >= 400.dp
            val condition = view.condition?.takeIf { !view.isEmpty && it.current != null }
            val badgeWidth = CONDITION_BADGE_WIDTH * fontScale.coerceAtMost(1.3f)
            val showBadge = condition != null && headerWidth / fontScale >= 300.dp
            val tabsWidth = if (showBadge && !wide) headerWidth - badgeWidth - 8.dp else headerWidth
            val height = if (short) 36.dp else 40.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(if (wide) Modifier else Modifier.width(tabsWidth)) {
                    ChartTabs(mode, onMode, fill = !wide, width = tabsWidth, height = height)
                }
                if (showBadge && condition != null) {
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    ConditionBadge(condition, live, Modifier.widthIn(max = badgeWidth).height(height))
                }
            }
        }
        LiveTorqueChart(
            view = view,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp),
            emptyTitle = emptyTitle,
            emptyHint = emptyHint,
            // 只有 M1 结果帧里才有目标扭矩设定值；M2 的同一字段是预设扭矩实际值，不画成目标线。
            targetTorqueNm = state.lastResult?.settingTorqueNm?.takeIf { it > 0 },
            mode = mode,
            live = live,
        )
    }
}

private val CONDITION_BADGE_WIDTH = 128.dp

/**
 * 当前运行段末点工况（空程 / 加载 / 停转保压 / 卸载 …）+ 疑似打滑次数；颜色只分正常 / 注意 / 异常三档。
 * 非实时（断开、延迟）时整体变暗，表示保留值。
 */
@Composable
private fun ConditionBadge(condition: WorkingCondition.Result, live: Boolean, modifier: Modifier = Modifier) {
    val p = palette
    val phase = condition.current ?: return
    val color = when (phase) {
        WorkingCondition.Phase.LOADING -> p.accent
        WorkingCondition.Phase.STALL, WorkingCondition.Phase.UNLOADING -> p.warn
        WorkingCondition.Phase.TORQUE_DROP, WorkingCondition.Phase.FREE_SPIN -> p.danger
        else -> p.textSecondary
    }.let { if (live) it else it.copy(alpha = 0.6f) }
    val slips = condition.count(WorkingCondition.EventKind.SLIP)
    val text = if (slips > 0) "${phase.label} · 打滑 $slips" else phase.label
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(p.panelSunken)
            .padding(horizontal = 10.dp)
            .testTag("condition_badge"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (slips > 0) p.danger else color))
        Spacer(Modifier.width(6.dp))
        FitText(text, WrenchType.labelStyle.copy(fontWeight = FontWeight.Medium), color = if (slips > 0) p.danger else color, minSize = 10.sp)
    }
}

/** 主图关系切换：扭矩–转角（默认）/ 斜率–转角。 */
@Composable
private fun ChartTabs(selected: ChartMode, onSelect: (ChartMode) -> Unit, fill: Boolean, width: Dp, height: Dp) {
    val p = palette
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // 平分时：各页签共用一个字号，按实测最宽页签（选中态半粗）逐级缩到放得下，最小 10sp。
    // 量和画用同一个 TextStyle；中文宽度差 1px 就会把末字挤到第二行被吞掉，所以还要 softWrap=false。
    fun tabStyle(sp: Float, on: Boolean) = TextStyle(
        fontSize = sp.sp, lineHeight = (sp + 4f).sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
    )
    val fontSp = remember(fill, width, density) {
        if (!fill) return@remember 15f
        val availPx = with(density) { ((width - 6.dp) / ChartMode.entries.size - 8.dp).toPx() }
        var sp = 15f
        while (sp > 10f && ChartMode.entries.maxOf { measurer.measure(it.tab, tabStyle(sp, true), maxLines = 1, softWrap = false).size.width } > availPx) sp -= 0.5f
        sp
    }
    Row(
        Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(p.panelSunken)
            .padding(3.dp)
            .selectableGroup()
            .testTag("chart_tabs"),
    ) {
        ChartMode.entries.forEach { mode ->
            val on = mode == selected
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) p.accentSoft else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(mode) })
                    .padding(horizontal = if (fill) 4.dp else 16.dp)
                    .testTag("chart_mode_${mode.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mode.tab, style = tabStyle(fontSp, on),
                    color = if (on) p.accent else p.textSecondary, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

// ---------------- D 右控制柱 ----------------

@Composable
private fun ControlPanel(
    state: MeasurementState,
    actions: WorkerActions,
    compact: Boolean,
    short: Boolean = false,
    tiny: Boolean = false,
    onForward: () -> Unit,
    onReverse: () -> Unit,
    onParams: () -> Unit,
    modifier: Modifier,
) {
    val p = palette
    val canMove = RemoteGate.canMove(state)
    val online = state.connectionState.isOnline
    val gap: Dp = if (short) 6.dp else if (compact) 8.dp else WrenchDimens.controlGap
    val deviceError = WorkerText.deviceError(state)
    // 状态行：报错 > 操作反馈（写参数、远程开关等，6 秒后自动收起）> 运动状态。
    val feedback = state.controlMessage.takeIf { it.isNotBlank() }
    val motionText = deviceError ?: feedback ?: WorkerText.motion(state)
    val motionShortText = WorkerText.deviceErrorShort(state) ?: feedback ?: WorkerText.motionShort(state)
    val motionColor = when {
        deviceError != null -> p.danger
        feedback != null -> p.textPrimary
        !online -> p.textTertiary
        !state.remoteEnabled -> p.warn
        state.motion == MotionCommand.FORWARD || state.motion == MotionCommand.REVERSE -> p.accent
        else -> p.ok
    }
    val forwardActive = state.motion == MotionCommand.FORWARD && canMove
    val reverseActive = state.motion == MotionCommand.REVERSE && canMove
    Panel(modifier.testTag("controls"), padding = PaddingValues(if (short) 6.dp else if (compact) 8.dp else 10.dp)) {
        // 「远程控制」表头条：整行可点，开关在右（0x11）。
        val headerColor by animateColorAsState(if (state.remoteEnabled) p.accentSoft else p.panelSunken, tween(200), label = "remote_header")
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(headerColor),
        ) {
            val narrow = maxWidth < 200.dp
            ToggleRow(
                title = if (narrow) "远程" else "远程控制",
                checked = state.remoteEnabled,
                onCheckedChange = { wanted -> if (wanted) actions.enableRemote() else actions.disableRemote() },
                enabled = RemoteGate.canToggleRemote(state),
                icon = if (narrow) null else WrenchIcon.Remote,
                titleColor = if (state.remoteEnabled) p.accent else p.textPrimary,
                modifier = Modifier.fillMaxWidth().testTag("remote_toggle"),
            )
        }
        if (!tiny) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 控制柱窄或大字体时换短说法，保证一行放下不出省略号。
                val narrow = maxWidth / LocalDensity.current.fontScale < 190.dp
                AnimatedContent(if (narrow) motionShortText else motionText, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "motion") { text ->
                    Text(
                        text, style = WrenchType.labelStyle, color = motionColor, maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = if (short) 2.dp else 5.dp).testTag("motion_status"),
                    )
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
        }
        // 按控制柱实际可用高度决定：一列大按钮（够高）或两两并排（矮屏 / 大字体）；按钮随高度放大，绝不裁切。
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val avail = maxHeight
            val singleMin = WrenchDimens.motionButton * 2 + WrenchDimens.stopButton + WrenchDimens.touch * 2 + gap * 4
            val exportEnabled = !state.exportInProgress && state.hasWrenchMeasurementData()
            if (avail >= singleMin) {
                val unit = ((avail - gap * 4) / 4.85f).coerceIn(WrenchDimens.motionButton, 84.dp)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                    ControlButton("正转", WrenchIcon.RotateForward, onForward, Modifier.fillMaxWidth().testTag("forward"), ButtonTone.Accent, enabled = canMove, filled = forwardActive, height = unit)
                    ControlButton("反转", WrenchIcon.RotateReverse, onReverse, Modifier.fillMaxWidth().testTag("reverse"), ButtonTone.Accent, enabled = canMove, filled = reverseActive, height = unit)
                    ControlButton("停止扳手", WrenchIcon.Stop, actions.stop, Modifier.fillMaxWidth().testTag("stop"), ButtonTone.Danger, enabled = true, filled = true,
                        height = (unit * 1.15f).coerceAtLeast(WrenchDimens.stopButton))
                    ControlButton("参数", WrenchIcon.Sliders, onParams, Modifier.fillMaxWidth().testTag("params"), height = (unit * 0.85f).coerceAtLeast(WrenchDimens.touch))
                    ControlButton(if (state.exportInProgress) "导出中…" else "导出 Excel", WrenchIcon.Export, actions.export, Modifier.fillMaxWidth().testTag("export"),
                        enabled = exportEnabled, height = (unit * 0.85f).coerceAtLeast(WrenchDimens.touch))
                }
            } else {
                // Material 按钮最小触控 48dp：按 48 起算，够高就放大到 60（戴手套好按，也不留大片空白）。
                val minUnit = WrenchDimens.touch
                val unit = ((avail - gap * 2) / 3.02f).coerceIn(minUnit, 60.dp)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        ControlButton("正转", null, onForward, Modifier.weight(1f).testTag("forward"), ButtonTone.Accent, enabled = canMove, filled = forwardActive, height = unit)
                        ControlButton("反转", null, onReverse, Modifier.weight(1f).testTag("reverse"), ButtonTone.Accent, enabled = canMove, filled = reverseActive, height = unit)
                    }
                    ControlButton("停止扳手", WrenchIcon.Stop, actions.stop, Modifier.fillMaxWidth().testTag("stop"), ButtonTone.Danger, enabled = true, filled = true, height = unit * 1.08f)
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        ControlButton("参数", null, onParams, Modifier.weight(1f).testTag("params"), height = (unit * 0.92f).coerceAtLeast(minUnit))
                        ControlButton(if (state.exportInProgress) "导出中" else "导出", null, actions.export, Modifier.weight(1f).testTag("export"),
                            enabled = exportEnabled, height = (unit * 0.92f).coerceAtLeast(minUnit))
                    }
                }
            }
        }
    }
}
