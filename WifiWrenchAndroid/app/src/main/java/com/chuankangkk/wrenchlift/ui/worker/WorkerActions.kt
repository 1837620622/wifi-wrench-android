package com.chuankangkk.wrenchlift.ui.worker

import com.chuankangkk.wrenchlift.measurement.MeasurementViewModel
import com.chuankangkk.wrenchlift.measurement.ParameterDraft
import com.chuankangkk.wrenchlift.measurement.PulseDraft
import com.chuankangkk.wrenchlift.measurement.WrenchModel

/** 正式工人页可触发的动作。界面只调这里，不直接拼协议帧。 */
data class WorkerActions(
    val connect: () -> Unit = {},
    val endSession: (stopWrenchFirst: Boolean) -> Unit = {},
    val startMeasurement: () -> Unit = {},
    val enableRemote: () -> Unit = {},
    val disableRemote: () -> Unit = {},
    val forward: () -> Unit = {},
    val reverse: () -> Unit = {},
    val stop: () -> Unit = {},
    val writeParameters: (ParameterDraft) -> Unit = {},
    val export: () -> Unit = {},
    val exportSession: (Long) -> Unit = {},
    val clearExport: () -> Unit = {},
    val clearControlMessage: () -> Unit = {},
    val openWifiSettings: () -> Unit = {},
    val loadMoreHistory: () -> Unit = {},
    val setWifiName: (String) -> Unit = {},
    val setHost: (String) -> Unit = {},
    val setPort: (String) -> Unit = {},
    val connectManual: () -> Unit = {},
    val runDiagnostics: () -> Unit = {},
    val setExportCsvToo: (Boolean) -> Unit = {},
    val setDarkTheme: (Boolean) -> Unit = {},
    val refreshWifi: () -> Unit = {},
    val setWrenchModel: (WrenchModel) -> Unit = {},
    val writePulseParameters: (PulseDraft) -> Unit = {},
    val exportDiagnostics: () -> Unit = {},
    val markProblem: () -> Unit = {},
    val clearDiagnostics: () -> Unit = {},
    val setSlabNo: (String) -> Unit = {},
    val setPointNo: (String) -> Unit = {},
) {
    companion object {
        fun from(vm: MeasurementViewModel) = WorkerActions(
            connect = vm::connectTcp,
            endSession = vm::endSession,
            startMeasurement = vm::startMeasurement,
            enableRemote = vm::enableRemoteControl,
            disableRemote = vm::disableRemoteControl,
            forward = vm::startForward,
            reverse = vm::startReverse,
            stop = vm::stopWrench,
            writeParameters = vm::writeParameters,
            setSlabNo = vm::updateSlabNo,
            setPointNo = vm::updatePointNo,
            export = vm::exportCurrent,
            exportSession = vm::exportSession,
            clearExport = vm::clearLastExport,
            clearControlMessage = vm::clearControlMessage,
            openWifiSettings = vm::openWifiSettings,
            loadMoreHistory = vm::loadMoreHistory,
            setWifiName = vm::updateTargetWifiName,
            setHost = vm::updateHost,
            setPort = vm::updatePort,
            connectManual = vm::connectManualEndpoint,
            runDiagnostics = vm::runDiagnostics,
            setExportCsvToo = vm::setExportCsvToo,
            setDarkTheme = vm::setDarkTheme,
            refreshWifi = vm::refreshWifiInfoAsync,
            setWrenchModel = vm::setWrenchModel,
            writePulseParameters = vm::writePulseParameters,
            exportDiagnostics = vm::exportDiagnostics,
            markProblem = vm::markProblem,
            clearDiagnostics = vm::clearDiagnostics,
        )
    }
}
