package com.chuankangkk.wrenchlift.ui.worker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chuankangkk.wrenchlift.measurement.MeasurementState
import com.chuankangkk.wrenchlift.measurement.ParameterDraft
import com.chuankangkk.wrenchlift.measurement.PulseDraft
import com.chuankangkk.wrenchlift.measurement.WrenchModel
import com.chuankangkk.wrenchlift.measurement.RemoteGate
import com.chuankangkk.wrenchlift.measurement.parameterDraft
import com.chuankangkk.wrenchlift.protocol.WifiWrenchParameterSettings

/**
 * 参数侧滑（§5.4）：右侧约 40% 宽，遮罩可点关闭（有改动先提示）；保存 = 确认后下发 0x10，取消不写。
 */
@Composable
fun ParameterSheet(
    visible: Boolean,
    state: MeasurementState,
    onDismiss: () -> Unit,
    onSave: (ParameterDraft) -> Unit,
    onSavePulse: (PulseDraft) -> Unit = {},
) {
    val p = palette
    val pulse = state.wrenchModel != WrenchModel.TORQUE
    var draft by remember { mutableStateOf(state.parameterDraft()) }
    var original by remember { mutableStateOf(state.parameterDraft()) }
    var pulseDraft by remember { mutableStateOf(defaultPulseDraft(state.wrenchModel)) }
    var pulseOriginal by remember { mutableStateOf(pulseDraft) }
    var confirmWrite by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(visible, state.wrenchModel) {
        if (visible) {
            draft = state.parameterDraft()
            original = draft
            if (pulseDraft.mode !in pulseModes(state.wrenchModel).map { it.first }) pulseDraft = defaultPulseDraft(state.wrenchModel)
            pulseOriginal = pulseDraft
        }
    }
    val dirty = if (pulse) pulseDraft != pulseOriginal else draft != original
    val requestClose = { if (dirty) confirmDiscard = true else onDismiss() }
    BackHandler(enabled = visible) { requestClose() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetWidth = (maxWidth * 0.40f).coerceIn(320.dp, 560.dp).coerceAtMost(maxWidth)
        AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(p.scrim).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                ) { requestClose() },
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(180)),
        ) {
            val shape = RoundedCornerShape(topStart = WrenchDimens.sheetRadius, bottomStart = WrenchDimens.sheetRadius)
            Column(
                Modifier
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(p.panel)
                    .border(1.dp, p.hairline, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("parameter_sheet"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (pulse) "扳手参数 · ${state.wrenchModel.label}" else "扳手参数", style = WrenchType.headlineStyle.copy(fontSize = 19.sp), color = p.textPrimary, maxLines = 1)
                        Text("保存后写入扳手，影响后续动作", style = WrenchType.captionStyle, color = p.textSecondary)
                    }
                }
                val formScroll = rememberScrollState()
                Column(
                    Modifier.weight(1f).bottomScrollHint(formScroll, p.panel).verticalScroll(formScroll).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pulse) {
                        PulseFields(state.wrenchModel, pulseDraft) { pulseDraft = it }
                        if (state.pulseStatus.isNotBlank()) {
                            Text(state.pulseStatus, style = WrenchType.captionStyle.copy(fontSize = 13.sp), color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        ModeSelector(draft.mode) { mode ->
                            draft = draft.copy(mode = mode, targetAngle = if (mode == WifiWrenchParameterSettings.MODE_M1) "0" else draft.targetAngle.takeIf { it != "0" } ?: "90")
                        }
                        // 单位写在分组标题里（扭矩 N·m / 转角 °），字段只写「目标 / 下限 / 上限」：
                        // 旧版把单位当后缀写进输入框，数字一长就和单位叠在一起，标签也在窄格里被裁。
                        ParamGroup("扭矩（N·m）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WrenchTextField(draft.targetTorque, { draft = draft.copy(targetTorque = it.filter(Char::isDigit).take(5)) }, "目标",
                                Modifier.weight(1f).testTag("param_target_torque"), numeric = true)
                            WrenchTextField(draft.torqueLower, { draft = draft.copy(torqueLower = it.filter(Char::isDigit).take(5)) }, "下限",
                                Modifier.weight(1f).testTag("param_torque_lower"), numeric = true)
                            WrenchTextField(draft.torqueUpper, { draft = draft.copy(torqueUpper = it.filter(Char::isDigit).take(5)) }, "上限",
                                Modifier.weight(1f).testTag("param_torque_upper"), numeric = true)
                        }
                        ParamGroup("转角（°）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WrenchTextField(draft.targetAngle, { draft = draft.copy(targetAngle = it.decimalInput()) }, "目标",
                                Modifier.weight(1f).testTag("param_target_angle"), enabled = draft.mode == WifiWrenchParameterSettings.MODE_M2, decimal = true)
                            WrenchTextField(draft.angleLower, { draft = draft.copy(angleLower = it.decimalInput()) }, "下限",
                                Modifier.weight(1f).testTag("param_angle_lower"), decimal = true)
                            WrenchTextField(draft.angleUpper, { draft = draft.copy(angleUpper = it.decimalInput()) }, "上限",
                                Modifier.weight(1f).testTag("param_angle_upper"), decimal = true)
                        }
                        if (state.parameterStatus.isNotBlank()) {
                            Text(state.parameterStatus, style = WrenchType.captionStyle.copy(fontSize = 13.sp), color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ControlButton("取消", null, { requestClose() }, Modifier.weight(1f).testTag("param_cancel"), height = WrenchDimens.touch)
                    ControlButton("保存", null, { confirmWrite = true }, Modifier.weight(1f).testTag("param_save"), ButtonTone.Accent,
                        enabled = RemoteGate.canWriteParameters(state), height = WrenchDimens.touch)
                }
                if (!RemoteGate.canWriteParameters(state)) {
                    Text("连上扳手后才能写入", style = WrenchType.captionStyle, color = p.warn, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (confirmWrite) {
        WrenchDialog(
            title = "写入参数？",
            message = "确认将参数写入扳手？写入后影响后续动作。",
            confirmText = "写入",
            onConfirm = {
                confirmWrite = false
                if (pulse) onSavePulse(pulseDraft) else onSave(draft)
            },
            onDismiss = { confirmWrite = false },
            modifier = Modifier.testTag("confirm_params"),
        )
    }
    if (confirmDiscard) {
        WrenchDialog(
            title = "放弃修改？",
            message = "参数还没保存，关闭后不会写入扳手。",
            confirmText = "放弃",
            confirmTone = ButtonTone.Neutral,
            onConfirm = {
                confirmDiscard = false
                onDismiss()
            },
            onDismiss = { confirmDiscard = false },
            dismissText = "继续编辑",
        )
    }
}

private fun pulseModes(model: WrenchModel): List<Pair<Int, String>> = when (model) {
    WrenchModel.PULSE_RC283F -> listOf(0x00 to "扭矩模式", 0x01 to "定点硬连接", 0x02 to "定点软连接")
    WrenchModel.PULSE_RCH -> listOf(0x01 to "定点硬连接", 0x02 to "定点软连接", 0x03 to "任意参数")
    WrenchModel.TORQUE -> emptyList()
}

private fun defaultPulseDraft(model: WrenchModel): PulseDraft =
    PulseDraft(mode = pulseModes(model).firstOrNull()?.first ?: 0)

/**
 * 0x50 / 0x51 字段（最新协议.docx §12/§13）；按模式只开放有效字段。
 * 单位与取值范围写在分组标题里，字段只留短标签（与 M1/M2 参数同一套排法）。
 */
@Composable
private fun PulseFields(model: WrenchModel, draft: PulseDraft, onChange: (PulseDraft) -> Unit) {
    val digits = { value: String -> value.filter(Char::isDigit).take(5) }
    ModeSelector(draft.mode, pulseModes(model)) { onChange(draft.copy(mode = it)) }
    val kEnabled = if (model == WrenchModel.PULSE_RC283F) draft.mode != 0x00 else draft.mode == 0x03
    if (model == WrenchModel.PULSE_RC283F) {
        ParamGroup("扭矩（N·m，精度 0.1）")
        WrenchTextField(draft.targetTorque, { onChange(draft.copy(targetTorque = it.decimalInput())) }, "目标", Modifier.fillMaxWidth(), decimal = true)
        ParamGroup("K 系数：K1 50–350 · K2 1–80 · K3 1–3000")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WrenchTextField(draft.k1, { onChange(draft.copy(k1 = digits(it))) }, "K1", Modifier.weight(1f), enabled = kEnabled, numeric = true)
            WrenchTextField(draft.k2, { onChange(draft.copy(k2 = digits(it))) }, "K2", Modifier.weight(1f), enabled = kEnabled, numeric = true)
            WrenchTextField(draft.k3, { onChange(draft.copy(k3 = digits(it))) }, "K3", Modifier.weight(1f), enabled = kEnabled, numeric = true)
        }
    } else {
        ParamGroup("定点：序号 1–10；设定扭矩填原值")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WrenchTextField(draft.presetIndex, { onChange(draft.copy(presetIndex = digits(it))) }, "序号", Modifier.weight(1f), enabled = draft.mode != 0x03, numeric = true)
            WrenchTextField(draft.setTorqueRaw, { onChange(draft.copy(setTorqueRaw = digits(it))) }, "设定扭矩", Modifier.weight(1f), enabled = kEnabled, numeric = true)
        }
        ParamGroup("K 系数：K1 50–300 · K2 1–80 · K3 1–20")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WrenchTextField(draft.k1, { onChange(draft.copy(k1 = digits(it))) }, "K1", Modifier.weight(1f), enabled = kEnabled, numeric = true)
            WrenchTextField(draft.k2, { onChange(draft.copy(k2 = digits(it))) }, "K2", Modifier.weight(1f), enabled = kEnabled, numeric = true)
            WrenchTextField(draft.k3, { onChange(draft.copy(k3 = digits(it))) }, "K3", Modifier.weight(1f), enabled = kEnabled, numeric = true)
        }
    }
    ParamGroup("螺栓：状态 % · 误差精度 · 个数")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WrenchTextField(draft.boltStatus, { onChange(draft.copy(boltStatus = digits(it))) }, "状态 %", Modifier.weight(1f), numeric = true)
        WrenchTextField(draft.precision, { onChange(draft.copy(precision = digits(it))) }, "精度", Modifier.weight(1f), numeric = true)
        WrenchTextField(draft.boltCount, { onChange(draft.copy(boltCount = digits(it))) }, "个数", Modifier.weight(1f), numeric = true)
    }
}

/** 参数分组标题：单位和取值范围写在这里，字段里只留短标签；太长可换两行，不省略关键范围。 */
@Composable
private fun ParamGroup(title: String) {
    Text(
        title,
        style = WrenchType.labelStyle,
        color = palette.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
    )
}

/** 模式分段（M1/M2、脉冲模式）：与设置里的扳手类型同一套分段控件，整组共用字号。 */
@Composable
private fun ModeSelector(
    mode: Int,
    options: List<Pair<Int, String>> = listOf(WifiWrenchParameterSettings.MODE_M1 to "M1 定扭矩", WifiWrenchParameterSettings.MODE_M2 to "M2 角度控制"),
    onSelect: (Int) -> Unit,
) {
    SegmentedControl(options, mode, onSelect)
}

private fun String.decimalInput(): String {
    val builder = StringBuilder()
    var dot = false
    for (char in this) {
        if (char.isDigit()) builder.append(char) else if (char == '.' && !dot) {
            builder.append(char)
            dot = true
        }
        if (builder.length >= 7) break
    }
    return builder.toString()
}
