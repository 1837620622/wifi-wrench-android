package com.chuankangkk.wrenchlift.measurement

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chuankangkk.wrenchlift.BuildConfig
import com.chuankangkk.wrenchlift.connection.AdaptiveConnector
import com.chuankangkk.wrenchlift.connection.ConnectionState
import com.chuankangkk.wrenchlift.connection.EndpointPlanner
import com.chuankangkk.wrenchlift.connection.ProbeOutcome
import com.chuankangkk.wrenchlift.connection.WifiNetworkTracker
import com.chuankangkk.wrenchlift.connection.WrenchEndpoint
import com.chuankangkk.wrenchlift.connection.NetworkDiagnosticResult
import com.chuankangkk.wrenchlift.connection.NetworkDiagnostics
import com.chuankangkk.wrenchlift.connection.TcpWrenchClient
import com.chuankangkk.wrenchlift.connection.WrenchConnection
import com.chuankangkk.wrenchlift.data.MeasurementRepository
import com.chuankangkk.wrenchlift.data.TorquePointEntity
import com.chuankangkk.wrenchlift.data.WrenchDatabase
import com.chuankangkk.wrenchlift.measurement.export.ProcessRow
import com.chuankangkk.wrenchlift.measurement.export.SessionWorkbookWriter
import com.chuankangkk.wrenchlift.measurement.export.FrameEventRow
import com.chuankangkk.wrenchlift.data.FrameEventEntity
import com.chuankangkk.wrenchlift.measurement.export.WorkbookSummary
import com.chuankangkk.wrenchlift.protocol.AckDecoder
import com.chuankangkk.wrenchlift.protocol.WifiWrenchParameterSettings
import com.chuankangkk.wrenchlift.protocol.BatteryDecoder
import com.chuankangkk.wrenchlift.protocol.DeviceSnDecoder
import com.chuankangkk.wrenchlift.protocol.DeviceStatusDecoder
import com.chuankangkk.wrenchlift.protocol.GpsDecoder
import com.chuankangkk.wrenchlift.protocol.NutResetDecoder
import com.chuankangkk.wrenchlift.protocol.PulseResultFrameDecoder
import com.chuankangkk.wrenchlift.protocol.PulseResultFrame
import com.chuankangkk.wrenchlift.protocol.ResultFrameDecoder
import com.chuankangkk.wrenchlift.protocol.ResultFrame
import com.chuankangkk.wrenchlift.protocol.TorqueAnglePoint
import com.chuankangkk.wrenchlift.protocol.WrenchPacket
import com.chuankangkk.wrenchlift.protocol.WrenchPacketBuilder
import com.chuankangkk.wrenchlift.protocol.TorqueAngleDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import java.io.Writer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class MeasurementViewModel @JvmOverloads constructor(
    application: Application,
    /** 连上 Wi‑Fi 后自动探测扳手；单元测试里关掉以免依赖系统网络回调。 */
    private val autoConnectOnWifi: Boolean = true,
    private val handshakeTimeoutMillis: Long = 1_800L,
) : AndroidViewModel(application) {
    private val repository = MeasurementRepository(
        WrenchDatabase.getInstance(application).measurementDao(),
    )
    private val uncalibratedCalculator: ForceCalculator = CalibrationForceCalculator(emptyList())
    private val wifiTracker = WifiNetworkTracker(application)
    private val networkDiagnostics = NetworkDiagnostics(application, socketFactory = wifiTracker::newSocket)
    private val connectionFactory: () -> WrenchConnection = {
        TcpWrenchClient(connectTimeoutMillis = PROBE_CONNECT_TIMEOUT_MILLIS, socketFactory = wifiTracker::newSocket)
    }

    private val connectionPreferences = application.getSharedPreferences("connection_preferences", android.content.Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(MeasurementState(
        targetWifiName = connectionPreferences.getString("target_wifi_name", "").orEmpty(),
        host = connectionPreferences.getString("host", WrenchEndpoint.DEFAULT_HOST).orEmpty(),
        port = connectionPreferences.getString("port", WrenchEndpoint.DEFAULT_PORT.toString()).orEmpty(),
        lastGoodEndpoint = WrenchEndpoint.parse(
            connectionPreferences.getString(PREF_LAST_GOOD_HOST, null),
            connectionPreferences.getString(PREF_LAST_GOOD_PORT, null),
        ),
        session = MeasurementSession(
            slabNo = connectionPreferences.getString(PREF_SLAB, null) ?: MeasurementSession().slabNo,
            pointNo = connectionPreferences.getString(PREF_POINT, null) ?: MeasurementSession().pointNo,
        ),
        exportCsvToo = connectionPreferences.getBoolean(PREF_EXPORT_CSV, false),
        darkTheme = connectionPreferences.getBoolean(PREF_DARK, true),
        wrenchModel = runCatching { WrenchModel.valueOf(connectionPreferences.getString(PREF_MODEL, null) ?: "") }.getOrDefault(WrenchModel.TORQUE),
    ))
    val state: StateFlow<MeasurementState> = _state.asStateFlow()

    private var connection: WrenchConnection = TcpWrenchClient()
    private var stateJob: Job? = null
    private var packetJob: Job? = null
    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var retryCount = 0
    private var sessionId: Long? = null
    /** 用户点了「结束会话」：之后的连接要新开记录；断线自动重连不算结束，续用当前记录。 */
    private var sessionEnded = false
    private var endJob: Job? = null
    private var connectionMode: String = "未连接"
    private var lastControl: String = ""
    private var lastControlAtMillis: Long = 0L
    /**
     * 扳手的地址码（协议字节 3「每台装置的地址」，0x50 一节写明用于区分同组扳手）：收到扳手任一帧后
     * 改用它自己的地址发指令；还没收到时用协议示例的 0x01。
     */
    @Volatile private var wrenchAddress: Int = WrenchPacket.DEFAULT_ADDRESS
    private val sessionMutex = Mutex()
    private val measurementMutex = Mutex()
    private val historyLimit = MutableStateFlow(20)

    /** 通信记录按到达顺序串行入库；会话建好之前的几帧先暂存，建好后补记到该会话。 */
    private val frameLog = Channel<FrameEventEntity>(Channel.UNLIMITED)
    private val pendingFrames = ArrayDeque<FrameEventEntity>()

    init {
        viewModelScope.launch {
            for (event in frameLog) runCatching { repository.saveFrameEvents(listOf(event)) }
        }
        attachPackets(connection)
        viewModelScope.launch {
            historyLimit.collectLatest { limit ->
                repository.observeRecentSessions(limit = limit).collect { sessions ->
                    _state.update { it.copy(recentSessions = sessions) }
                }
            }
        }
        if (autoConnectOnWifi) {
            viewModelScope.launch {
                wifiTracker.wifiAvailable.collect { available -> onWifiChanged(available) }
            }
        }
    }

    private suspend fun onWifiChanged(available: Boolean) {
        _state.update { it.copy(wifiAvailable = available) }
        runCatching { refreshWifiInfo() }
        val current = _state.value
        if (available) {
            val idle = current.linkPhase in setOf(LinkPhase.IDLE, LinkPhase.NO_WIFI, LinkPhase.FAILED, LinkPhase.DROPPED)
            if (current.autoConnect && idle && connectJob?.isActive != true) {
                retryCount = 0
                delay(WIFI_SETTLE_MILLIS)
                startProbe(manual = null)
            }
        } else if (!current.connectionState.isOnline && current.linkPhase != LinkPhase.PROBING && current.autoConnect) {
            _state.update { it.copy(linkPhase = LinkPhase.NO_WIFI) }
        }
    }

    fun updateHost(value: String) {
        _state.update { it.copy(host = value) }
        connectionPreferences.edit().putString("host", value).apply()
    }

    fun updatePort(value: String) {
        val port = value.filter { char -> char.isDigit() }.take(5)
        _state.update { it.copy(port = port) }
        connectionPreferences.edit().putString("port", port).apply()
    }

    fun updateTargetWifiName(value: String) {
        val name = value.take(64)
        _state.update { it.copy(targetWifiName = name) }
        connectionPreferences.edit().putString("target_wifi_name", name).apply()
    }

    fun updateSlabNo(value: String) {
        val slab = value.take(32)
        _state.update { it.copy(session = it.session.copy(slabNo = slab)) }
        connectionPreferences.edit().putString(PREF_SLAB, slab).apply()
    }

    fun updatePointNo(value: String) {
        val point = value.take(32)
        _state.update { it.copy(session = it.session.copy(pointNo = point)) }
        connectionPreferences.edit().putString(PREF_POINT, point).apply()
    }

    fun setExportCsvToo(enabled: Boolean) {
        _state.update { it.copy(exportCsvToo = enabled) }
        connectionPreferences.edit().putBoolean(PREF_EXPORT_CSV, enabled).apply()
    }

    fun setDarkTheme(enabled: Boolean) {
        _state.update { it.copy(darkTheme = enabled) }
        connectionPreferences.edit().putBoolean(PREF_DARK, enabled).apply()
    }

    fun setWrenchModel(model: WrenchModel) {
        _state.update { it.copy(wrenchModel = model) }
        connectionPreferences.edit().putString(PREF_MODEL, model.name).apply()
    }

    /** 脉冲类扳手参数（0x50 RC283F / 0x51 RCH）。只在设置里选了对应扳手类型时由参数侧滑调用。 */
    fun writePulseParameters(draft: PulseDraft) {
        val current = _state.value
        if (!RemoteGate.canWriteParameters(current)) {
            _state.update { it.copy(pulseStatus = "未连上扳手，参数未写入") }
            return
        }
        val packet = runCatching {
            fun Int?.need(name: String) = this ?: throw IllegalArgumentException("请填写$name")
            when (current.wrenchModel) {
                WrenchModel.PULSE_RC283F -> WrenchPacketBuilder.pulseParameterSetRc283f(
                    com.chuankangkk.wrenchlift.protocol.Rc283fPulseSettings(
                        productId = "",
                        stationName = "",
                        employeeId = knownEmployeeId(current),
                        toolSerial = "",
                        boltModel = "",
                        mode = draft.mode,
                        targetTorqueNm = draft.targetTorque.toDoubleOrNull() ?: throw IllegalArgumentException("请填写目标扭矩"),
                        k1 = if (draft.mode == 0) 0 else draft.k1.toIntOrNull().need("K1"),
                        k2 = if (draft.mode == 0) 0 else draft.k2.toIntOrNull().need("K2"),
                        k3 = if (draft.mode == 0) 0 else draft.k3.toIntOrNull().need("K3"),
                        boltStatusPercent = draft.boltStatus.toIntOrNull() ?: 0,
                        errorPrecision = draft.precision.toIntOrNull() ?: 0,
                        boltCount = draft.boltCount.toIntOrNull() ?: 0,
                    ),
                    address = wrenchAddress,
                )
                WrenchModel.PULSE_RCH -> WrenchPacketBuilder.pulseParameterSetRch(
                    com.chuankangkk.wrenchlift.protocol.RchPulseSettings(
                        productId = "",
                        stationName = "",
                        employeeId = knownEmployeeId(current),
                        toolSerial = "",
                        boltModel = "",
                        mode = draft.mode,
                        presetIndex = draft.presetIndex.toIntOrNull() ?: 1,
                        k1 = if (draft.mode == 3) draft.k1.toIntOrNull().need("K1") else 0,
                        k2 = if (draft.mode == 3) draft.k2.toIntOrNull().need("K2") else 0,
                        k3 = if (draft.mode == 3) draft.k3.toIntOrNull().need("K3") else 0,
                        setTorqueRaw = if (draft.mode == 3) draft.setTorqueRaw.toIntOrNull().need("设定扭矩") else 0,
                        boltStatusPercent = draft.boltStatus.toIntOrNull() ?: 0,
                        errorPrecision = draft.precision.toIntOrNull() ?: 0,
                        boltCount = draft.boltCount.toIntOrNull() ?: 0,
                    ),
                    address = wrenchAddress,
                )
                WrenchModel.TORQUE -> throw IllegalArgumentException("当前扳手类型为定扭矩，请用 0x10 参数")
            }
        }.getOrElse { error ->
            _state.update { it.copy(pulseStatus = error.message ?: "参数超出协议范围") }
            appendLog("脉冲参数被拦截：${error.message}")
            return
        }
        sendCommand(packet, LABEL_PARAMS) {
            it.copy(pulseStatus = "已发送 ${current.wrenchModel.functionLabel} 参数", controlMessage = "参数已发送")
        }
    }

    fun clearLastExport() {
        _state.update { it.copy(lastExport = null) }
    }

    fun clearControlMessage() {
        _state.update { it.copy(controlMessage = "") }
    }

    fun updateParameterMode(mode: Int) {
        if (mode != WifiWrenchParameterSettings.MODE_M1 && mode != WifiWrenchParameterSettings.MODE_M2) return
        _state.update {
            it.copy(
                parameterMode = mode,
                parameterTargetAngle = when {
                    mode == WifiWrenchParameterSettings.MODE_M1 -> "0"
                    it.parameterTargetAngle == "0" -> "90"
                    else -> it.parameterTargetAngle
                },
            )
        }
    }

    fun updateParameterTargetTorque(value: String) {
        _state.update { it.copy(parameterTargetTorque = value.sanitizeUIntText()) }
    }

    fun updateParameterTorqueLower(value: String) {
        _state.update { it.copy(parameterTorqueLower = value.sanitizeUIntText()) }
    }

    fun updateParameterTorqueUpper(value: String) {
        _state.update { it.copy(parameterTorqueUpper = value.sanitizeUIntText()) }
    }

    fun updateParameterTargetAngle(value: String) {
        _state.update { it.copy(parameterTargetAngle = value.sanitizeDecimalText()) }
    }

    fun updateParameterAngleUpper(value: String) {
        _state.update { it.copy(parameterAngleUpper = value.sanitizeDecimalText()) }
    }

    fun updateParameterAngleLower(value: String) {
        _state.update { it.copy(parameterAngleLower = value.sanitizeDecimalText()) }
    }

    /** 正式入口：后台自适应探测（缓存 → 默认 192.168.4.1:7888 → 候选 → 子网短扫）。 */
    fun connectTcp() = startProbe(manual = null)

    /** 高级诊断：按手动填写的 host:port 优先连接，失败后仍走自适应探测。 */
    fun connectManualEndpoint() {
        val manual = WrenchEndpoint.parse(_state.value.host, _state.value.port)
        if (manual == null) {
            _state.update { it.copy(networkDiagnosticSummary = "地址或端口格式不对") }
            return
        }
        startProbe(manual)
    }

    /** 记录页「开始测量」：只有一台扳手，直接自动连接并建新记录（不需要填任何字段）。 */
    fun startMeasurement() {
        if (_state.value.connectionState.isOnline) return
        retryCount = 0
        if (connectJob?.isActive == true) {
            _state.update { it.copy(autoConnect = true) }
            return
        }
        startProbe(manual = null)
    }

    private fun startProbe(manual: WrenchEndpoint?) {
        if (connectJob?.isActive == true || _state.value.connectionState.isOnline) return
        retryJob?.cancel()
        _state.update {
            it.copy(
                connectionState = ConnectionState.Connecting,
                linkPhase = LinkPhase.PROBING,
                autoConnect = true,
                probeReport = ProbeReport(summary = "正在探测"),
            )
        }
        connectJob = viewModelScope.launch {
            try {
                // 等上一次「结束会话」完整收尾（断开旧连接、写入摘要）之后再连，避免两边互相断开对方的新连接。
                endJob?.join()
                measurementMutex.withLock {
                    stateJob?.cancel()
                    packetJob?.cancelAndJoin()
                    if (sessionEnded) {
                        // 用户已结束上一条记录：这次连接是新的一条记录，曲线与读数从头开始。
                        sessionId = null
                        sessionEnded = false
                        _state.value = _state.value.freshSessionState()
                    }
                    // 否则是断线重连 / Wi‑Fi 恢复 / 手动连接：续用当前记录，曲线保留，断线段在图上标为「数据间断」。
                }
                runCatching { connection.disconnect() }
                val outcome = probeEndpoints(manual)
                when (outcome) {
                    is ProbeOutcome.Ready -> adoptConnection(outcome.connection, outcome.endpoint, handshake = true, tried = outcome.tried)
                    is ProbeOutcome.TcpOnly -> adoptConnection(outcome.connection, outcome.endpoint, handshake = false, tried = outcome.tried)
                    is ProbeOutcome.Failed -> onProbeFailed(outcome.tried, "没有找到能回话的扳手")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onProbeFailed(emptyList(), error.message ?: "连接失败")
            }
        }
    }

    private suspend fun probeEndpoints(manual: WrenchEndpoint?): ProbeOutcome {
        val lastGood = _state.value.lastGoodEndpoint
        val savedManual = WrenchEndpoint.parse(
            connectionPreferences.getString("host", null),
            connectionPreferences.getString("port", null),
        )
        var info: NetworkDiagnosticResult? = null
        suspend fun networkInfo(): NetworkDiagnosticResult =
            info ?: networkDiagnostics.diagnose(WrenchEndpoint.DEFAULT_HOST, WrenchEndpoint.DEFAULT_PORT, checkPorts = false)
                .also { result ->
                    info = result
                    applyNetworkInfo(result)
                }
        val connector = AdaptiveConnector(
            connectionFactory = connectionFactory,
            handshakeTimeoutMillis = handshakeTimeoutMillis,
            onProgress = { endpoint ->
                _state.update { it.copy(probeReport = it.probeReport.copy(summary = "正在尝试 $endpoint")) }
            },
        )
        val outcome = connector.probe(
            primary = (listOfNotNull(manual) + EndpointPlanner.primary(lastGood)).distinct(),
            candidates = { tried ->
                val network = networkInfo()
                val planned = EndpointPlanner.candidates(lastGood, savedManual, network.gatewayIp, network.localIp)
                    .filterNot { it in tried }
                networkDiagnostics.openEndpoints(planned, timeout = CANDIDATE_CHECK_TIMEOUT_MILLIS)
            },
            subnetScan = { tried ->
                val network = networkInfo()
                val ports = (listOfNotNull(lastGood?.port, savedManual?.port) + EndpointPlanner.CANDIDATE_PORTS).distinct()
                networkDiagnostics.scanSubnet(network.localIp, ports, exclude = tried)
            },
            attach = { candidate -> attachPackets(candidate) },
        )
        if (info == null) runCatching { networkInfo() }
        appendLog("自适应探测：试过 ${outcome.tried.joinToString()}")
        return outcome
    }

    private suspend fun adoptConnection(
        newConnection: WrenchConnection,
        endpoint: WrenchEndpoint,
        handshake: Boolean,
        tried: List<WrenchEndpoint>,
    ) {
        connection = newConnection
        connectionMode = "TCP"
        stateJob?.cancel()
        stateJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            newConnection.connectionState.collect { connectionState ->
                _state.update { it.copy(connectionState = connectionState) }
                if (connectionState !is ConnectionState.Connected) onLinkLost()
            }
        }
        val id = ensureSession("TCP")
        _state.update {
            it.copy(
                activeEndpoint = endpoint,
                linkPhase = if (handshake) LinkPhase.READY else LinkPhase.WAITING_REPLY,
                currentSessionId = id,
                remoteEnabled = false,
                motion = MotionCommand.NONE,
                probeReport = ProbeReport(
                    tried = tried,
                    selected = endpoint,
                    handshake = handshake,
                    summary = if (handshake) "握手成功 $endpoint" else "TCP 已连 $endpoint，等待扳手首帧",
                ),
                lastCommandLabel = "连接检查和设备编号查询",
                lastSentHex = WrenchPacketBuilder.querySn(address = wrenchAddress).rawHex(),
            )
        }
        if (handshake) rememberLastGood(endpoint)
        if (!newConnection.connectionState.value.isOnline) {
            // 建链与接管之间 socket 已断：按断线处理，不显示「已连上」。
            onLinkLost()
            return
        }
        if (!handshake) {
            // TCP 能连但还没回话：补发一次心跳和查 SN，首帧到达时 handlePacket 会确认握手。
            runCatching {
                val heartbeat = WrenchPacketBuilder.heartbeat(address = wrenchAddress)
                val querySn = WrenchPacketBuilder.querySn(address = wrenchAddress)
                newConnection.send(heartbeat)
                logFrame("发", heartbeat, "连接保活（TCP 已通、等待首帧）")
                newConnection.send(querySn)
                logFrame("发", querySn, "查询设备 SN")
            }
        }
        appendLog(if (handshake) "已连上扳手（$endpoint）" else "已接通 $endpoint，等待扳手回话")
    }

    private fun rememberLastGood(endpoint: WrenchEndpoint) {
        retryCount = 0
        connectionPreferences.edit()
            .putString(PREF_LAST_GOOD_HOST, endpoint.host)
            .putString(PREF_LAST_GOOD_PORT, endpoint.port.toString())
            .apply()
        _state.update { it.copy(lastGoodEndpoint = endpoint) }
    }

    private fun onProbeFailed(tried: List<WrenchEndpoint>, reason: String) {
        _state.update {
            it.copy(
                connectionState = ConnectionState.Error(reason),
                linkPhase = if (it.wifiAvailable || !autoConnectOnWifi) LinkPhase.FAILED else LinkPhase.NO_WIFI,
                probeReport = ProbeReport(tried = tried, summary = "$reason（试过 ${tried.size} 个端点）"),
            )
        }
        appendLog("连接失败：$reason")
        scheduleRetry()
    }

    private fun onLinkLost() {
        val current = _state.value
        if (current.linkPhase != LinkPhase.READY && current.linkPhase != LinkPhase.WAITING_REPLY) return
        _state.update { it.copy(linkPhase = LinkPhase.DROPPED, remoteEnabled = false) }
        appendLog("连接已断开")
        viewModelScope.launch {
            measurementMutex.withLock {
                sessionId?.let { id ->
                    val latest = _state.value
                    // 断线不等于结束：记录仍可续用，状态保持「采集中」，直到用户结束会话。
                    persistSessionSummary(id, latest.lastResult?.status?.label ?: "采集中", latest.lastResult, latest.lastPulseResult)
                }
            }
        }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!_state.value.autoConnect || retryCount >= MAX_AUTO_RETRIES) return
        if (autoConnectOnWifi && !_state.value.wifiAvailable) return
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(RETRY_DELAY_MILLIS)
            retryCount += 1
            startProbe(manual = null)
        }
    }

    /** 结束会话：只断开本机会话与采集，不会自动停止扳手（SAFE-05）。可选先发 0x01 停止。 */
    fun endSession(stopWrenchFirst: Boolean = false) {
        // 只结束「点结束时」已经存在的连接任务；结束期间若用户又点了连接，新任务不能被这里取消。
        val connectAtEnd = connectJob
        retryJob?.cancel()
        _state.update { it.copy(autoConnect = false) }
        endJob = viewModelScope.launch {
            var stopSent = false
            if (stopWrenchFirst && _state.value.connectionState.isOnline) {
                stopSent = runCatching { connection.send(WrenchPacketBuilder.stop(address = wrenchAddress)) }.isSuccess
                logFrame("发", WrenchPacketBuilder.stop(address = wrenchAddress), if (stopSent) "结束前停止扳手" else "结束前停止扳手 · 发送失败")
                if (stopSent) {
                    _state.update { it.copy(motion = MotionCommand.STOPPED, lastCommandLabel = "停止扳手", lastSentHex = WrenchPacketBuilder.stop(address = wrenchAddress).rawHex()) }
                    appendLog("结束前已发送停止扳手")
                    delay(150)
                }
            }
            connectAtEnd?.cancelAndJoin()
            connection.disconnect()
            measurementMutex.withLock {
                sessionId?.let { id ->
                    val current = _state.value
                    persistSessionSummary(id, current.lastResult?.status?.label ?: "采集结束", current.lastResult, current.lastPulseResult)
                }
                sessionEnded = sessionId != null
            }
            _state.update {
                // 结束期间如果用户已经又点了连接，不覆盖新连接的状态（以底层连接的实时状态为准，不用可能滞后的 UI 副本）。
                val reconnecting = connectJob?.isActive == true || connection.connectionState.value is ConnectionState.Connected
                if (reconnecting) {
                    it
                } else {
                    it.copy(
                        linkPhase = LinkPhase.IDLE,
                        remoteEnabled = false,
                        controlMessage = if (stopSent) "已发送停止，并结束会话" else "会话已结束（扳手不会因此自动停止）",
                    )
                }
            }
            appendLog("会话已结束")
        }
    }

    /** 兼容调试控制台：断开 = 结束会话（不发停止）。 */
    fun disconnect() = endSession(stopWrenchFirst = false)

    fun openWifiSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }.onSuccess {
            _state.update {
                it.copy(
                    networkDiagnosticSummary = "已打开系统 Wi-Fi 设置，请手动连接扳手热点后返回 App 检查连接。",
                    lastProtocolSummary = "等待手动连接扳手 Wi-Fi",
                )
            }
            appendLog("已打开系统 Wi-Fi 设置")
        }.onFailure { throwable ->
            _state.update {
                it.copy(networkDiagnosticSummary = "无法打开系统 Wi-Fi 设置：${throwable.message ?: "未知错误"}")
            }
            appendLog("打开系统 Wi-Fi 设置失败：${throwable.message ?: "未知错误"}")
        }
    }

    /** 0x11 开远程。工人显式操作，连接成功后不会自动发送（SAFE-02）。 */
    fun enableRemoteControl() {
        if (!RemoteGate.canToggleRemote(_state.value)) {
            controlFeedback("未连上扳手，无法开启远程")
            return
        }
        sendCommand(WrenchPacketBuilder.enableRemoteControl(address = wrenchAddress), LABEL_REMOTE_ON) {
            it.copy(remoteEnabled = true, motion = MotionCommand.NONE, controlMessage = "远程已启用")
        }
    }

    /** 0x11 关远程；若 App 最后发的是正/反转，先补发 0x01 停止再关。 */
    fun disableRemoteControl() {
        val current = _state.value
        if (!current.connectionState.isOnline) {
            _state.update { it.copy(remoteEnabled = false) }
            return
        }
        viewModelScope.launch {
            if (current.motion == MotionCommand.FORWARD || current.motion == MotionCommand.REVERSE) {
                val stop = WrenchPacketBuilder.stop(address = wrenchAddress)
                if (runCatching { connection.send(stop) }.isSuccess) {
                    logFrame("发", stop, "关远程前停止扳手")
                    _state.update { it.copy(motion = MotionCommand.STOPPED, lastCommandLabel = LABEL_STOP, lastSentHex = stop.rawHex()) }
                    appendLog("关远程前已发送停止")
                }
            }
            sendCommand(WrenchPacketBuilder.disableRemoteControl(address = wrenchAddress), LABEL_REMOTE_OFF) {
                it.copy(remoteEnabled = false, controlMessage = "远程已关闭")
            }
        }
    }

    /** 0x01 正转：必须已开远程；界面另有二次确认（SAFE-01/03）。 */
    fun startForward() = move(MotionCommand.FORWARD)

    /** 0x01 反转：必须已开远程；界面另有二次确认。 */
    fun startReverse() = move(MotionCommand.REVERSE)

    private fun move(direction: MotionCommand) {
        val current = _state.value
        val label = if (direction == MotionCommand.FORWARD) LABEL_FORWARD else LABEL_REVERSE
        if (!current.connectionState.isOnline) {
            controlFeedback("未连上扳手")
            return
        }
        if (!RemoteGate.canMove(current)) {
            controlFeedback("请先启用远程控制")
            appendLog("已拦截$label：远程未启用，不发送")
            return
        }
        val packet = if (direction == MotionCommand.FORWARD) WrenchPacketBuilder.startForward(address = wrenchAddress) else WrenchPacketBuilder.startReverse(address = wrenchAddress)
        sendCommand(packet, label) {
            it.copy(motion = direction, controlMessage = if (direction == MotionCommand.FORWARD) "已发送正转" else "已发送反转")
        }
    }

    /** 0x01 停止：任何时候都可点；离线时只能提示到扳手上停止。 */
    fun stopWrench() {
        if (!RemoteGate.canSendStop(_state.value)) {
            controlFeedback("未连上扳手，停止发不出去，请直接在扳手上停止")
            return
        }
        sendCommand(WrenchPacketBuilder.stop(address = wrenchAddress), LABEL_STOP) {
            it.copy(motion = MotionCommand.STOPPED, controlMessage = "已发送停止")
        }
    }

    private fun controlFeedback(message: String) {
        _state.update { it.copy(controlMessage = message) }
    }

    /** 参数侧滑「保存」：确认后把草稿写入状态并下发 0x10。 */
    fun writeParameters(draft: ParameterDraft) {
        _state.update {
            it.copy(
                parameterMode = draft.mode,
                parameterTargetTorque = draft.targetTorque.sanitizeUIntText(),
                parameterTorqueLower = draft.torqueLower.sanitizeUIntText(),
                parameterTorqueUpper = draft.torqueUpper.sanitizeUIntText(),
                parameterTargetAngle = if (draft.mode == WifiWrenchParameterSettings.MODE_M1) "0" else draft.targetAngle.sanitizeDecimalText(),
                parameterAngleUpper = draft.angleUpper.sanitizeDecimalText(),
                parameterAngleLower = draft.angleLower.sanitizeDecimalText(),
            )
        }
        writeParameterSettings()
    }

    fun writeParameterSettings() {
        if (!RemoteGate.canWriteParameters(_state.value)) {
            _state.update { it.copy(parameterStatus = "未连上扳手，参数未写入") }
            return
        }
        val settings = buildParameterSettings() ?: return
        val statusText =
            "已发送 ${settings.modeLabel()}：目标扭矩 ${settings.targetTorqueNm} Nm，目标角度 %.1f°".format(settings.targetAngleDeg)
        sendCommand(WrenchPacketBuilder.parameterSet(settings, address = wrenchAddress), LABEL_PARAMS) {
            it.copy(
                parameterStatus = statusText,
                controlMessage = "参数已发送",
            )
        }
    }

    fun sendHeartbeat() {
        viewModelScope.launch {
            runDiagnosticsInternal()
            if (_state.value.connectionState is ConnectionState.Connected) {
                sendCommand(WrenchPacketBuilder.heartbeat(address = wrenchAddress), "检查连接")
            }
        }
    }

    /** 高级诊断：检查手动端点与候选端口可达性（只做报告，不改缓存）。 */
    fun runDiagnostics() {
        viewModelScope.launch { runDiagnosticsInternal() }
    }

    private suspend fun runDiagnosticsInternal() {
        val current = _state.value
        val endpoint = WrenchEndpoint.parse(current.host, current.port) ?: WrenchEndpoint.DEFAULT
        diagnoseNetwork(endpoint.host, endpoint.port, checkPorts = true)
    }

    fun refreshWifiInfoAsync() {
        viewModelScope.launch { runCatching { refreshWifiInfo() } }
    }

    private suspend fun refreshWifiInfo() {
        applyNetworkInfo(networkDiagnostics.diagnose(WrenchEndpoint.DEFAULT_HOST, WrenchEndpoint.DEFAULT_PORT, checkPorts = false))
    }

    fun saveCurrentRecord() {
        viewModelScope.launch {
            val current = _state.value
            if (!current.hasWrenchMeasurementData()) {
                val message = "尚未收到真实扳手测量数据，不能保存记录"
                _state.update { it.copy(saveStatus = message) }
                appendLog(message)
                return@launch
            }
            val activeSessionId = sessionId ?: ensureSession(connectionMode)
            persistSessionSummary(
                activeSessionId = activeSessionId,
                resultStatus = current.resultStatus,
                result = current.lastResult,
                pulseResult = current.lastPulseResult,
            )
            _state.update { it.copy(saveStatus = "当前记录已写入本机数据库") }
            appendLog("会话摘要已保存")
        }
    }

    fun loadMoreHistory() { historyLimit.value = (historyLimit.value + 20).coerceAtMost(100_000) }

    fun newRecord() {
        if (_state.value.connectionState.isOnline || _state.value.connectionState is ConnectionState.Connecting) return
        viewModelScope.launch {
            measurementMutex.withLock {
                packetJob?.cancelAndJoin()
                sessionId = null
                sessionEnded = false
                val old = _state.value
                _state.value = old.freshSessionState().copy(
                    connectionState = old.connectionState,
                    linkPhase = old.linkPhase,
                    saveStatus = "新记录已就绪，请检查测点后连接",
                )
            }
        }
    }

    fun exportCurrentCsv() {
        val id = sessionId
        if (id == null) {
            _state.update { it.copy(saveStatus = "暂无测量记录可导出") }
            return
        }
        exportSessionCsv(id)
    }

    /** 正式导出：当前记录 → xlsx（含原生 Excel 图）；设置里勾选后同时导出 CSV。 */
    fun exportCurrent() {
        val id = sessionId
        if (id == null) {
            _state.update { it.copy(lastExport = ExportOutcome(false, "还没有测量数据可导出")) }
            return
        }
        exportSession(id)
    }

    fun exportSession(id: Long) {
        viewModelScope.launch {
            if (_state.value.exportInProgress) return@launch
            _state.update { it.copy(exportInProgress = true, lastExport = null) }
            try {
                val outcome = withContext(Dispatchers.IO) { exportWorkbook(id) }
                _state.update { it.copy(lastExport = outcome, saveStatus = "${outcome.message} · ${outcome.displayPath}") }
                appendLog("Excel 已导出：${outcome.displayPath}")
                if (_state.value.exportCsvToo) exportSessionCsvBlocking(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        lastExport = ExportOutcome(false, "导出失败，请重试或检查存储空间", displayPath = error.message.orEmpty()),
                        saveStatus = "Excel 导出失败：${error.message}",
                    )
                }
                appendLog("Excel 导出失败：${error.message}")
            } finally {
                _state.update { it.copy(exportInProgress = false) }
            }
        }
    }

    private suspend fun exportWorkbook(id: Long): ExportOutcome {
        // 等当前帧完整入库后冻结上界；之后收到的点留待下一次导出。
        val (record, throughId, total) = measurementMutex.withLock {
            val record = repository.getSession(id) ?: error("记录不存在")
            val throughId = repository.lastPointId(id) ?: 0L
            Triple(record, throughId, if (throughId > 0) repository.countPointsThrough(id, throughId) else 0L)
        }
        val eventsThrough = repository.lastFrameEventId(id) ?: 0L
        require(throughId > 0 || record.actualTorqueNm != null) { "记录尚无测量数据" }
        val summary = WorkbookSummary(
            recordId = record.id, deviceSn = record.deviceSn,
            startTimeMillis = record.startTime, endTimeMillis = record.endTime, resultStatus = record.resultStatus,
            targetTorqueNm = record.targetTorqueNm, actualTorqueNm = record.actualTorqueNm,
            targetAngleDeg = record.targetAngleDeg, actualAngleDeg = record.actualAngleDeg,
            torqueLowerNm = record.torqueLowerNm, torqueUpperNm = record.torqueUpperNm,
            angleLowerDeg = record.angleLowerDeg, angleUpperDeg = record.angleUpperDeg,
            appVersion = BuildConfig.VERSION_NAME,
            resultRawHex = record.wrenchResultRawHex,
        )
        val fileName = MeasurementCsvExporter.recordFileName(record.id, record.startTime, "xlsx")
        val target = openExportTarget(fileName, ExportOutcome.XLSX_MIME)
        try {
            target.output.use { output ->
                val frameEvents: suspend (suspend (FrameEventRow) -> Unit) -> Unit = { emit ->
                    var cursor = 0L
                    var index = 0L
                    while (cursor < eventsThrough) {
                        val page = repository.frameEventsPage(id, cursor, eventsThrough)
                        if (page.isEmpty()) break
                        for (event in page) {
                            index += 1
                            emit(FrameEventRow(index, event.timestampMillis, event.direction, event.functionCode, event.summary, event.rawHex))
                        }
                        cursor = page.last().id
                    }
                }
                SessionWorkbookWriter.write(output, summary, total, frameEvents) { emit ->
                    var cursor = 0L
                    var index = 0L
                    while (cursor < throughId) {
                        val page = repository.pointsPage(id, cursor, throughId)
                        if (page.isEmpty()) break
                        for (point in page) {
                            index += 1
                            emit(
                                ProcessRow(
                                    index = index, receivedAtMillis = point.timestampMillis, boltNo = point.boltNo,
                                    torqueNm = point.torqueNm, angleDeg = point.angleDeg, angleRaw = point.angleRaw,
                                    frameLabel = MeasurementCsvExporter.frameLabelFromRawHex(point.rawHex), rawHex = point.rawHex,
                                ),
                            )
                        }
                        cursor = page.last().id
                    }
                }
            }
            target.commit()
        } catch (error: Throwable) {
            target.abort()
            throw error
        }
        return ExportOutcome(
            success = true,
            message = "导出完成（含图表）",
            displayPath = target.displayPath,
            shareUri = target.shareUri?.toString(),
        )
    }

    private class ExportTarget(
        val output: OutputStream,
        val displayPath: String,
        val shareUri: Uri?,
        val commit: () -> Unit,
        val abort: () -> Unit,
    )

    /** Android 10+ 写系统下载目录；否则或失败时写 App 专属 Documents，并经 FileProvider 分享。 */
    private fun openExportTarget(fileName: String, mime: String): ExportTarget {
        val application = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = application.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_DIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            val stream = uri?.let { runCatching { resolver.openOutputStream(it) }.getOrNull() }
            if (uri != null && stream != null) {
                return ExportTarget(
                    output = stream,
                    displayPath = "下载/$EXPORT_DIR/$fileName",
                    shareUri = uri,
                    commit = { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) },
                    abort = { runCatching { resolver.delete(uri, null, null) } },
                )
            }
            uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
        val documentRoot = application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(application.filesDir, "Documents")
        val exportDir = File(documentRoot, EXPORT_DIR)
        check(exportDir.isDirectory || exportDir.mkdirs()) { "无法创建导出目录" }
        val file = File(exportDir, fileName)
        val shareUri = runCatching {
            FileProvider.getUriForFile(application, "${application.packageName}.exports", file)
        }.getOrNull()
        return ExportTarget(
            output = file.outputStream(),
            displayPath = "App专属目录/$EXPORT_DIR/$fileName",
            shareUri = shareUri,
            commit = {},
            abort = { file.delete() },
        )
    }

    /** 设置里勾选「同时导出 CSV」时随 xlsx 一起导出；失败只记状态，不影响已完成的 xlsx。 */
    private suspend fun exportSessionCsvBlocking(id: Long) {
        val result = runCatching { withContext(Dispatchers.IO) { writeSessionCsv(id) } }
        val text = result.getOrElse { "CSV 导出失败：${it.message}" }
        _state.update {
            it.copy(
                saveStatus = text,
                lastExport = it.lastExport?.let { outcome ->
                    if (result.isSuccess) outcome.copy(message = outcome.message + "，CSV 也已导出") else outcome.copy(message = outcome.message + "；CSV 未导出")
                },
            )
        }
    }

    fun exportSessionCsv(id: Long) {
        viewModelScope.launch {
            if (_state.value.exportInProgress) return@launch
            _state.update { it.copy(exportInProgress = true) }
            try {
                val result = withContext(Dispatchers.IO) { writeSessionCsv(id) }
                _state.update { it.copy(saveStatus = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(saveStatus = "CSV 导出失败：${error.message}") }
            } finally {
                _state.update { it.copy(exportInProgress = false) }
            }
        }
    }

    private suspend fun writeSessionCsv(id: Long): String {
                    // 等当前帧完整入库后冻结上界；之后收到的点留待下一次导出。
                    val snapshot = measurementMutex.withLock {
                        val record = repository.getSession(id) ?: error("记录不存在")
                        record to (repository.lastPointId(id) ?: 0L)
                    }
                    val (record, throughId) = snapshot
                    require(throughId > 0 || record.actualTorqueNm != null) { "记录尚无测量数据" }
                    val metadata = MeasurementState(
                        session = MeasurementSession(record.projectName, record.lineName, record.sectionName,
                            record.slabNo, record.pointNo, record.operatorName, record.deviceSn),
                        deviceSn = record.deviceSn, resultStatus = record.resultStatus,
                        host = "", port = "", currentWifiName = "", localIp = "", gatewayIp = "",
                        currentTorqueNm = record.actualTorqueNm ?: record.finalTorqueNm ?: 0.0,
                        currentAngleDeg = record.actualAngleDeg ?: record.finalAngleDeg ?: 0.0,
                        currentForceKn = record.finalForceKn,
                        lastPacketFunction = if (throughId > 0) WrenchPacket.FUNC_TORQUE_ANGLE else WrenchPacket.FUNC_RESULT,
                        lastRawHex = record.wrenchResultRawHex.orEmpty(),
                    )
                    val fileName = MeasurementCsvExporter.recordFileName(record.id, record.startTime, "csv")
                    var rowCount = 0
                    val write: suspend (Writer) -> Unit = { writer ->
                        MeasurementCsvExporter.writeHeader(writer)
                        var cursor = 0L
                        rowCount = 0
                        while (cursor < throughId) {
                            val page = repository.pointsPage(id, cursor, throughId)
                            if (page.isEmpty()) break
                            for (point in page) {
                                val measured = MeasuredPoint(
                                    sourcePoint = TorqueAnglePoint(point.torqueNm, point.angleRaw, point.angleDeg, point.timestampMillis),
                                    boltNo = point.boltNo, forceKn = point.forceKn, pressureMpa = point.pressureMpa,
                                    displacementMm = point.displacementMm, warningLevel = WarningLevel.WATCH, rawHex = point.rawHex,
                                )
                                MeasurementCsvExporter.writePoint(writer,
                                    metadata.copy(recentPoints = listOf(measured),
                                        lastPacketFunction = point.rawHex.split(' ', limit = 5).getOrNull(3)?.toIntOrNull(16)), ++rowCount, measured)
                            }
                            cursor = page.last().id
                        }
                        if (rowCount == 0) MeasurementCsvExporter.writeSummary(writer, metadata)
                    }
                    val application = getApplication<Application>()
                    val path = exportCsvToDownloads(application, fileName, write)
                        ?: exportCsvToAppDocuments(application, fileName, write)
                    return "CSV 已导出：$path · $rowCount 个过程点"
    }

    private suspend fun exportCsvToDownloads(application: Application, fileName: String, write: suspend (Writer) -> Unit): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = application.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/WifiWrenchExports")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val output = resolver.openOutputStream(uri) ?: error("无法打开下载目录")
            output.bufferedWriter(Charsets.UTF_8).use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            "下载/WifiWrenchExports/$fileName"
        } catch (cancelled: CancellationException) {
            resolver.delete(uri, null, null)
            throw cancelled
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private suspend fun exportCsvToAppDocuments(application: Application, fileName: String, write: suspend (Writer) -> Unit): String {
        val documentRoot = application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(application.filesDir, "Documents")
        val exportDir = File(documentRoot, "WifiWrenchExports")
        check(exportDir.isDirectory || exportDir.mkdirs()) { "无法创建导出目录" }
        val csvFile = File(exportDir, fileName)
        try {
            csvFile.bufferedWriter(Charsets.UTF_8).use { write(it) }
        } catch (error: Exception) {
            csvFile.delete()
            throw error
        }
        return "App专属目录/${csvFile.name}"
    }

    fun confirmEffectivePoint() {
        viewModelScope.launch {
            val activeSessionId = sessionId ?: return@launch
            if (!_state.value.canConfirmEffectivePoint()) {
                _state.update { it.copy(saveStatus = "没有可确认的采样点") }
                appendLog("参考点标记被跳过：还没有真实采样点")
                return@launch
            }
            repository.markLatestPointEffective(activeSessionId)
            val count = repository.countEffectivePoints(activeSessionId)
            _state.update {
                it.copy(
                    effectivePointCount = count,
                    saveStatus = "已标记参考点 $count（未确认，待分析）",
                )
            }
            appendLog("最新采样点已标记为参考点（未确认）")
        }
    }

    fun removeLatestEffectivePoint() {
        viewModelScope.launch {
            val activeSessionId = sessionId ?: return@launch
            if (!_state.value.canRemoveEffectivePoint()) {
                _state.update { it.copy(saveStatus = "当前没有可删除的参考点") }
                appendLog("参考点删除被跳过：当前没有标记")
                return@launch
            }
            repository.unmarkLatestEffectivePoint(activeSessionId)
            val count = repository.countEffectivePoints(activeSessionId)
            _state.update {
                it.copy(
                    effectivePointCount = count,
                    saveStatus = if (count > 0) {
                        "已删除最近参考点，剩余 $count"
                    } else {
                        "已删除参考点标记，当前无标记"
                    },
                )
            }
            appendLog("已删除最近一个参考点标记；原始采样点保留")
        }
    }

    private fun sendCommand(
        packet: WrenchPacket,
        label: String,
        onSuccessState: ((MeasurementState) -> MeasurementState)? = null,
    ) {
        viewModelScope.launch {
            lastControl = label
            lastControlAtMillis = System.currentTimeMillis()
            runCatching {
                connection.send(packet)
            }.onSuccess {
                logFrame("发", packet, label)
                _state.update {
                    val updated = it.copy(
                        lastCommandLabel = label,
                        lastSentHex = packet.rawHex(),
                    )
                    onSuccessState?.invoke(updated) ?: updated
                }
                appendLog("$label 已发送：${packet.rawHex()}")
            }.onFailure { throwable ->
                logFrame("发", packet, "$label · 发送失败：${throwable.message ?: "未知错误"}")
                if (label == LABEL_PARAMS) {
                    _state.update { it.copy(parameterStatus = "参数发送失败：${throwable.message ?: "未知错误"}") }
                }
                _state.update { it.copy(controlMessage = "$label 没发出去，请检查连接") }
                appendLog("$label 发送失败：${throwable.message ?: "未知错误"}")
            }
        }
    }

    /** 扳手上一次回传的员工号（0x12 / 0x15）；未知时返回空串，不编造。 */
    private fun knownEmployeeId(state: MeasurementState): String =
        state.employeeId.takeIf { it.isNotBlank() && it != "--" } ?: ""

    private fun buildParameterSettings(): WifiWrenchParameterSettings? {
        val current = _state.value
        val targetTorque = current.parameterTargetTorque.toIntOrNull()
        val torqueLower = current.parameterTorqueLower.toIntOrNull()
        val torqueUpper = current.parameterTorqueUpper.toIntOrNull()
        val targetAngle = if (current.parameterMode == WifiWrenchParameterSettings.MODE_M1) {
            0.0
        } else {
            current.parameterTargetAngle.toDoubleOrNull()
        }
        val angleUpper = current.parameterAngleUpper.toDoubleOrNull()
        val angleLower = current.parameterAngleLower.toDoubleOrNull()
        if (
            targetTorque == null ||
            torqueLower == null ||
            torqueUpper == null ||
            targetAngle == null ||
            angleUpper == null ||
            angleLower == null
        ) {
            _state.update { it.copy(parameterStatus = "参数不完整，请填写扭矩和角度") }
            appendLog("参数写入被拦截：存在空值或非法数字")
            return null
        }
        return runCatching {
            WifiWrenchParameterSettings(
                // 不写入编造的标识：员工号只沿用扳手上一次回传的值，其余标识留空（全零）。
                productId = "",
                stationName = "",
                employeeId = knownEmployeeId(current),
                toolSerial = "",
                controllerSerial = "",
                mode = current.parameterMode,
                targetTorqueNm = targetTorque,
                torqueLowerNm = torqueLower,
                torqueUpperNm = torqueUpper,
                targetAngleDeg = targetAngle,
                angleUpperDeg = angleUpper,
                angleLowerDeg = angleLower,
            )
        }.onFailure { throwable ->
            _state.update { it.copy(parameterStatus = throwable.message ?: "参数超出协议范围") }
            appendLog("参数写入被拦截：${throwable.message ?: "参数超出协议范围"}")
        }.getOrNull()
    }

    private suspend fun diagnoseNetwork(
        host: String,
        port: Int,
        checkPorts: Boolean,
    ): NetworkDiagnosticResult {
        val result = networkDiagnostics.diagnose(host, port, checkPorts)
        applyNetworkInfo(result)
        appendLog("网络诊断：${result.summary}")
        return result
    }

    private fun applyNetworkInfo(result: NetworkDiagnosticResult) {
        _state.update {
            it.copy(
                currentWifiName = result.wifiName,
                localIp = result.localIp,
                gatewayIp = result.gatewayIp,
                inWrenchSubnet = result.inWrenchSubnet,
                deviceIpReachable = result.deviceIpReachable ?: it.deviceIpReachable,
                devicePortReachable = result.devicePortReachable ?: it.devicePortReachable,
                gatewayPortReachable = result.gatewayPortReachable ?: it.gatewayPortReachable,
                discoveredEndpoint = if (result.discoveredEndpoint != "--") result.discoveredEndpoint else it.discoveredEndpoint,
                networkDiagnosticSummary = result.summary,
            )
        }
    }

    /** 让 VM 处理该连接的所有入站帧（探测候选时也接，SN/电量等不丢）。先订阅，再建链。 */
    private fun attachPackets(target: WrenchConnection) {
        packetJob?.cancel()
        connection = target
        packetJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            target.incomingPackets.collect { packet ->
                wrenchAddress = packet.address
                try {
                    // 0x15 要在 0.5 秒内回 0x17，否则扳手会把结果另存：能解析就先确认，再排队入库。
                    val resultAck = if (packet.functionCode == WrenchPacket.FUNC_RESULT &&
                        runCatching { ResultFrameDecoder.decode(packet) }.isSuccess
                    ) {
                        runCatching { target.send(WrenchPacketBuilder.resultAck(address = wrenchAddress)) }.also { ack ->
                            logFrame("发", WrenchPacketBuilder.resultAck(address = wrenchAddress), if (ack.isSuccess) "结果确认 0x17（校验通过即回）" else "结果确认 0x17 · 发送失败")
                        }
                    } else {
                        null
                    }
                    measurementMutex.withLock { handlePacket(packet, resultAck) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _state.update {
                        it.copy(lastProtocolSummary = "协议解析失败：${throwable.message ?: "未知错误"}")
                    }
                    appendLog("协议解析失败，已保留原始 HEX：${throwable.message ?: "未知错误"}")
                }
            }
        }
    }

    private suspend fun handlePacket(packet: WrenchPacket, resultAck: Result<Unit>? = null) {
        val now = System.currentTimeMillis()
        val waiting = _state.value
        if (waiting.linkPhase == LinkPhase.WAITING_REPLY && waiting.connectionState.isOnline) {
            // 首个合法 C 协议帧到达：握手确认，记住端点。
            waiting.activeEndpoint?.let { endpoint ->
                rememberLastGood(endpoint)
                _state.update {
                    it.copy(
                        linkPhase = LinkPhase.READY,
                        probeReport = it.probeReport.copy(handshake = true, summary = "握手成功 $endpoint"),
                    )
                }
                appendLog("扳手已回话，握手成功（$endpoint）")
            }
        }
        _state.update {
            it.copy(
                receivedFrameCount = it.receivedFrameCount + 1,
                lastPacketFunction = packet.functionCode,
                lastFrameReceiveTime = now,
                lastRawHex = packet.rawHex(),
                lastProtocolSummary = "收到设备数据",
            )
        }
        when (packet.functionCode) {
            WrenchPacket.FUNC_ACK -> {
                val ack = AckDecoder.decode(packet)
                // 0x06 不带被应答的功能码：只把 NAK 记到最近 3 秒内发出的操作上，避免心跳等的应答误关远程。
                val recent = lastControl.isNotBlank() && now - lastControlAtMillis <= NAK_ATTRIBUTION_MILLIS
                _state.update {
                    val rejected = !ack.isAck && recent
                    it.copy(
                        lastProtocolSummary = "设备应答 ${ack.label}",
                        remoteEnabled = if (rejected && lastControl == LABEL_REMOTE_ON) false else it.remoteEnabled,
                        motion = if (rejected && (lastControl == LABEL_FORWARD || lastControl == LABEL_REVERSE)) MotionCommand.NONE else it.motion,
                        controlMessage = if (rejected && lastControl.isNotBlank()) "扳手没有接受：$lastControl" else it.controlMessage,
                    )
                }
                logFrame("收", packet, (if (ack.isAck) "ACK " else "NAK ") + ack.label + (if (recent) "（3 s 内操作：$lastControl）" else "（3 s 内无操作，不归属）"))
                appendLog("收到设备应答：${ack.label}")
            }
            WrenchPacket.FUNC_TORQUE_ANGLE -> handleTorqueAngle(packet, now)
            WrenchPacket.FUNC_RESULT -> handleResult(packet, now, resultAck)
            WrenchPacket.FUNC_TIME_REQUEST -> {
                logFrame("收", packet, "扳手请求对时 0x21")
                val sync = WrenchPacketBuilder.timeSync(address = wrenchAddress)
                runCatching {
                    connection.send(sync)
                }.onSuccess {
                    logFrame("发", sync, "回复对时 0x22")
                    _state.update { it.copy(lastProtocolSummary = "扳手请求校时，已自动回复") }
                    appendLog("扳手请求时间校准，已自动回复")
                }.onFailure { throwable ->
                    _state.update { it.copy(lastProtocolSummary = "扳手请求校时，回复失败") }
                    appendLog("扳手请求时间校准，回复失败：${throwable.message ?: "未知错误"}")
                }
            }
            WrenchPacket.FUNC_REPLY_SN -> {
                val sn = DeviceSnDecoder.decode(packet)
                val known = _state.value.deviceSn.takeIf { it.isNotBlank() && it != "未知设备" && it != "--" }
                if (known != null && known != sn.sn && sessionId != null) {
                    // 换了一台扳手：上一台的记录收尾，新记录只含这台扳手的数据。
                    sessionId?.let { id -> persistSessionSummary(id, _state.value.lastResult?.status?.label ?: "采集结束", _state.value.lastResult, _state.value.lastPulseResult) }
                    sessionId = null
                    sessionEnded = false
                    _state.update { old ->
                        old.freshSessionState().copy(
                            connectionState = old.connectionState,
                            linkPhase = old.linkPhase,
                            deviceSn = sn.sn,
                            session = old.session.copy(deviceSn = sn.sn),
                            lastProtocolSummary = "换了扳手（SN ${sn.sn}），已开始新记录",
                        )
                    }
                    appendLog("检测到换了扳手（SN $known → ${sn.sn}），已开新记录")
                }
                _state.update {
                    it.copy(
                        deviceSn = sn.sn,
                        session = it.session.copy(deviceSn = sn.sn),
                        lastProtocolSummary = "设备 SN ${sn.sn}",
                    )
                }
                logFrame("收", packet, "设备 SN ${sn.sn}")
                appendLog("收到设备编号：${sn.sn}")
                sessionId?.let { repository.updateDeviceSn(it, sn.sn) }
            }
            WrenchPacket.FUNC_BATTERY -> {
                BatteryDecoder.decodePercent(packet)?.let { percent ->
                    _state.update {
                        it.copy(
                            batteryPercent = percent,
                            lastProtocolSummary = "电量 $percent%",
                        )
                    }
                    logFrame("收", packet, "电量 $percent%")
                    appendLog("收到电池电量 $percent%")
                }
            }
            WrenchPacket.FUNC_STATUS -> {
                val status = DeviceStatusDecoder.decode(packet)
                _state.update {
                    it.copy(
                        lastProtocolSummary = "设备状态 ${status.summary}",
                        deviceErrorCode = status.errorCode ?: it.deviceErrorCode,
                        deviceReverseDirection = status.reverseDirection ?: it.deviceReverseDirection,
                    )
                }
                logFrame(
                    "收", packet,
                    "错误码 ${status.errorCode ?: "--"} · 控制位 ${status.controlBits?.let { "0x%04X".format(it) } ?: "--"}" +
                        (status.reverseDirection?.let { if (it) " · Bit1=1（反向，位序待真机确认）" else " · Bit1=0（正向）" } ?: ""),
                )
                appendLog("收到设备状态：${status.summary}")
            }
            WrenchPacket.FUNC_NUT_RESET -> {
                val nutReset = NutResetDecoder.decode(packet)
                _state.update { it.copy(lastProtocolSummary = nutReset.label) }
                logFrame("收", packet, nutReset.label)
                appendLog("收到螺母计数：${nutReset.label}")
            }
            WrenchPacket.FUNC_GPS -> {
                val gps = GpsDecoder.decode(packet)
                val summary = if (gps.valid) "GPS 有效 ${gps.text}" else "GPS 无效 ${gps.text}"
                _state.update { it.copy(lastProtocolSummary = summary) }
                logFrame("收", packet, summary)
                appendLog("收到位置数据：$summary")
            }
            WrenchPacket.FUNC_PULSE_RESULT -> handlePulseResult(packet, now)
            else -> {
                logFrame("收", packet, "未识别功能码，原始 HEX 已保存")
                appendLog("收到未识别设备数据，原始内容已保存")
            }
        }
    }

    private suspend fun handleTorqueAngle(packet: WrenchPacket, now: Long) {
        val decoded = TorqueAngleDecoder.decode(packet, nowMillis = now)
        val activeSessionId = sessionId ?: ensureSession(connectionMode)
        val calculator = uncalibratedCalculator
        val newMeasuredPoints = mutableListOf<MeasuredPoint>()
        val historyBefore = _state.value.recentPoints.map { it.sourcePoint }.toMutableList()
        var latestCalculation: ForceCalculationResult? = null
        val entities = ArrayList<TorquePointEntity>(decoded.points.size)
        // 本帧第一个点的记录序号：入库前的记录点数 + 1（互斥锁内，计数稳定；结果帧也计入点数，与导出一致）。
        val seqBase = _state.value.persistedPointCount

        decoded.points.forEachIndexed { index, point ->
            val result = calculator.calculate(point, historyBefore)
            val displacementMm = 0.0
            val measuredPoint = MeasuredPoint(
                sourcePoint = point,
                boltNo = decoded.boltNo,
                forceKn = result.forceKn,
                pressureMpa = result.pressureMpa,
                displacementMm = displacementMm,
                warningLevel = result.warningLevel,
                rawHex = decoded.rawHex,
                seq = seqBase + index + 1,
            )
            latestCalculation = result
            historyBefore += point
            newMeasuredPoints += measuredPoint
            entities.add(
                TorquePointEntity(
                    sessionId = activeSessionId,
                    timestampMillis = point.timestampMillis,
                    boltNo = decoded.boltNo,
                    torqueNm = point.torqueNm,
                    angleRaw = point.angleRaw,
                    angleDeg = point.angleDeg,
                    forceKn = result.forceKn,
                    pressureMpa = result.pressureMpa,
                    displacementMm = displacementMm,
                    isEffectivePoint = false,
                    rawHex = decoded.rawHex,
                ),
            )
        }
        repository.savePoints(entities)

        val latest = newMeasuredPoints.lastOrNull() ?: return
        val latestResult = latestCalculation ?: return
        _state.update { old ->
            val merged = TorqueAngleWindow.append(old.recentPoints, newMeasuredPoints)
            old.copy(
                currentBoltNo = decoded.boltNo,
                employeeId = decoded.employeeId.ifBlank { "--" },
                currentTorqueNm = latest.sourcePoint.torqueNm,
                currentAngleRaw = latest.sourcePoint.angleRaw,
                currentAngleDeg = latest.sourcePoint.angleDeg,
                currentForceKn = latest.forceKn,
                currentPressureMpa = latest.pressureMpa,
                currentDisplacementMm = latest.displacementMm,
                warningLevel = latest.warningLevel,
                warningMessage = latestResult.message,
                lastFrameReceiveTime = now,
                lastCurvePointReceiveTime = now,
                persistedPointCount = old.persistedPointCount + newMeasuredPoints.size,
                recentPoints = merged,
                saveStatus = "已入库 ${old.persistedPointCount + newMeasuredPoints.size} 点",
            )
        }
    }

    private suspend fun handleResult(packet: WrenchPacket, now: Long, preAck: Result<Unit>?) {
        val result = ResultFrameDecoder.decode(packet)
        logFrame(
            "收", packet,
            "结果 ${result.status.label}（状态 ${"0x%02X".format(result.status.code ?: 0)}）· ${result.modeText} · " +
                "${result.torqueField2Name} ${result.actualTorqueNm.formatLog()} N·m",
        )
        val ackResult = preAck ?: runCatching {
            connection.send(WrenchPacketBuilder.resultAck(address = wrenchAddress))
        }.also { ack ->
            logFrame("发", WrenchPacketBuilder.resultAck(address = wrenchAddress), if (ack.isSuccess) "结果确认 0x17" else "结果确认 0x17 · 发送失败")
        }
        val activeSessionId = sessionId ?: ensureSession(connectionMode)
        recordResultMeasurementPoint(
            activeSessionId = activeSessionId,
            boltNo = result.boltNo,
            torqueNm = result.actualTorqueNm,
            angleDeg = result.actualAngleDeg,
            now = now,
            rawHex = result.rawHex,
        )
        _state.update {
            it.copy(
                resultStatus = result.status.label,
                currentBoltNo = result.boltNo,
                employeeId = result.employeeId.ifBlank { it.employeeId },
                currentTorqueNm = result.actualTorqueNm ?: it.currentTorqueNm,
                currentAngleDeg = result.actualAngleDeg ?: it.currentAngleDeg,
                currentAngleRaw = result.actualAngleDeg?.roundToInt() ?: it.currentAngleRaw,
                lastFrameReceiveTime = now,
                lastResult = result,
                motion = if (it.motion == MotionCommand.FORWARD || it.motion == MotionCommand.REVERSE) MotionCommand.STOPPED else it.motion,
                controlMessage = "本次运行结束：${result.status.label}",
                lastProtocolSummary = "扳手结果（${result.modeText}）：${result.torqueField1Name} ${result.targetTorqueNm.formatLog()} Nm，" +
                    "${result.torqueField2Name} ${result.actualTorqueNm.formatLog()} Nm",
            ).withSyncedResultSettings(result)
        }
        persistSessionSummary(activeSessionId, result.status.label, result, pulseResult = null)
        val ackText = if (ackResult.isSuccess) {
            "已自动确认"
        } else {
            "确认发送失败：${ackResult.exceptionOrNull()?.message ?: "未知错误"}"
        }
        appendLog(
            "收到最终结果：${result.status.label}（${result.modeText}）；${result.torqueField1Name}/${result.torqueField2Name} " +
                "${result.targetTorqueNm.formatLog()}/${result.actualTorqueNm.formatLog()} Nm；" +
                "${result.angleField1Name}/${result.angleField2Name} ${result.targetAngleDeg.formatLog()}/${result.actualAngleDeg.formatLog()}°；$ackText",
        )
    }

    private suspend fun handlePulseResult(packet: WrenchPacket, now: Long) {
        val result = PulseResultFrameDecoder.decode(packet)
        logFrame("收", packet, "脉冲类结果 ${result.status.label} · ${result.modeText} · 实际扭矩 ${result.actualTorqueNm.formatLog()} N·m")
        val activeSessionId = sessionId ?: ensureSession(connectionMode)
        recordResultMeasurementPoint(
            activeSessionId = activeSessionId,
            boltNo = result.boltNo,
            torqueNm = result.actualTorqueNm,
            angleDeg = result.actualAngleDeg,
            now = now,
            rawHex = result.rawHex,
        )
        _state.update {
            it.copy(
                resultStatus = result.status.label,
                currentBoltNo = result.boltNo,
                employeeId = result.employeeId.ifBlank { it.employeeId },
                currentTorqueNm = result.actualTorqueNm ?: it.currentTorqueNm,
                currentAngleDeg = result.actualAngleDeg ?: it.currentAngleDeg,
                currentAngleRaw = result.actualAngleDeg?.roundToInt() ?: it.currentAngleRaw,
                lastFrameReceiveTime = now,
                lastPulseResult = result,
                motion = if (it.motion == MotionCommand.FORWARD || it.motion == MotionCommand.REVERSE) MotionCommand.STOPPED else it.motion,
                controlMessage = "本次运行结束：${result.status.label}",
                lastProtocolSummary = "扳手脉冲结果已同步：目标扭矩 ${result.targetTorqueNm.formatLog()} Nm",
            )
        }
        persistSessionSummary(activeSessionId, result.status.label, result = null, pulseResult = result)
        appendLog(
            "收到特殊模式结果：${result.status.label}；目标/实际扭矩 " +
                "${result.targetTorqueNm.formatLog()}/${result.actualTorqueNm.formatLog()} Nm；" +
                "实际角度 ${result.actualAngleDeg.formatLog()}°",
        )
    }

    private suspend fun recordResultMeasurementPoint(
        activeSessionId: Long,
        boltNo: Int,
        torqueNm: Double?,
        angleDeg: Double?,
        now: Long,
        rawHex: String,
    ) {
        if (torqueNm == null || angleDeg == null) return
        val sourcePoint = TorqueAnglePoint(
            torqueNm = torqueNm,
            angleRaw = angleDeg.roundToInt(),
            angleDeg = angleDeg,
            timestampMillis = now,
        )
        val result = uncalibratedCalculator.calculate(
            sourcePoint,
            _state.value.recentPoints.map { it.sourcePoint },
        )
        val measuredPoint = MeasuredPoint(
            sourcePoint = sourcePoint,
            boltNo = boltNo,
            forceKn = result.forceKn,
            pressureMpa = result.pressureMpa,
            displacementMm = 0.0,
            warningLevel = result.warningLevel,
            rawHex = rawHex,
        )
        repository.savePoint(
            TorquePointEntity(
                sessionId = activeSessionId,
                timestampMillis = sourcePoint.timestampMillis,
                boltNo = boltNo,
                torqueNm = sourcePoint.torqueNm,
                angleRaw = sourcePoint.angleRaw,
                angleDeg = sourcePoint.angleDeg,
                forceKn = result.forceKn,
                pressureMpa = result.pressureMpa,
                displacementMm = measuredPoint.displacementMm,
                isEffectivePoint = false,
                rawHex = rawHex,
            ),
        )
        _state.update { old ->
            // 结果帧的实际转角口径未经真机确认（可能从阈值扭矩起算），只入库、不并进实时曲线，
            // 以免在过程曲线上造出假点或假断点；过程曲线与转折候选只用 0x12。
            old.copy(
                currentBoltNo = boltNo,
                currentTorqueNm = sourcePoint.torqueNm,
                currentAngleRaw = sourcePoint.angleRaw,
                currentAngleDeg = sourcePoint.angleDeg,
                currentForceKn = result.forceKn,
                currentPressureMpa = result.pressureMpa,
                currentDisplacementMm = measuredPoint.displacementMm,
                warningLevel = result.warningLevel,
                warningMessage = result.message,
                persistedPointCount = old.persistedPointCount + 1,
                saveStatus = "已保存扳手结果点，原始结果帧已入库",
            )
        }
    }

    private suspend fun persistSessionSummary(
        activeSessionId: Long,
        resultStatus: String,
        result: ResultFrame?,
        pulseResult: PulseResultFrame?,
    ) {
        val current = _state.value
        repository.updateSessionSummary(
            sessionId = activeSessionId,
            resultStatus = resultStatus,
            finalTorqueNm = result?.actualTorqueNm ?: pulseResult?.actualTorqueNm ?: current.currentTorqueNm,
            finalAngleDeg = result?.actualAngleDeg ?: pulseResult?.actualAngleDeg ?: current.currentAngleDeg,
            finalForceKn = current.currentForceKn,
            finalPressureMpa = current.currentPressureMpa,
            finalDisplacementMm = current.currentDisplacementMm,
            wrenchResultType = when {
                result != null -> "普通结果"
                pulseResult != null -> "脉冲结果"
                else -> null
            },
            wrenchResultCode = result?.status?.code ?: pulseResult?.status?.code,
            wrenchResultLabel = result?.status?.label ?: pulseResult?.status?.label,
            wrenchEmployeeId = result?.employeeId ?: pulseResult?.employeeId,
            wrenchBoltNo = result?.boltNo ?: pulseResult?.boltNo,
            wrenchMode = result?.mode ?: pulseResult?.mode,
            targetTorqueNm = result?.targetTorqueNm ?: pulseResult?.targetTorqueNm,
            actualTorqueNm = result?.actualTorqueNm ?: pulseResult?.actualTorqueNm,
            targetAngleDeg = result?.targetAngleDeg,
            actualAngleDeg = result?.actualAngleDeg ?: pulseResult?.actualAngleDeg,
            torqueUpperNm = result?.torqueUpperNm,
            torqueLowerNm = result?.torqueLowerNm,
            angleUpperDeg = result?.angleUpperDeg,
            angleLowerDeg = result?.angleLowerDeg,
            wrenchResultRawHex = result?.rawHex ?: pulseResult?.rawHex,
        )
    }

    private suspend fun ensureSession(mode: String): Long = sessionMutex.withLock {
        sessionId?.let { return it }
        val current = _state.value.session
        val id = repository.createSession(
            projectName = current.projectName,
            lineName = current.lineName,
            sectionName = current.sectionName,
            slabNo = current.slabNo,
            pointNo = current.pointNo,
            operatorName = current.operatorName,
            deviceSn = current.deviceSn,
            connectionMode = mode,
        )
        sessionId = id
        synchronized(pendingFrames) {
            pendingFrames.forEach { frameLog.trySend(it.copy(sessionId = id)) }
            pendingFrames.clear()
        }
        return id
    }

    /** 记一条通信记录（0x12 逐点已在过程数据里，不记）。 */
    private fun logFrame(direction: String, packet: WrenchPacket, summary: String) {
        if (packet.functionCode == WrenchPacket.FUNC_TORQUE_ANGLE) return
        val id = sessionId
        val event = FrameEventEntity(
            sessionId = id ?: 0L, timestampMillis = System.currentTimeMillis(), direction = direction,
            functionCode = packet.functionCode, summary = summary, rawHex = packet.rawHex(),
        )
        if (id != null) {
            frameLog.trySend(event)
        } else {
            synchronized(pendingFrames) {
                pendingFrames.addLast(event)
                while (pendingFrames.size > MAX_PENDING_FRAMES) pendingFrames.removeFirst()
            }
        }
    }

    private fun appendLog(message: String) {
        _state.update {
            it.copy(logLines = (it.logLines + message).takeLast(12))
        }
    }

    private fun MeasurementState.freshSessionState(): MeasurementState = MeasurementState(
        targetWifiName = targetWifiName, host = host, port = port,
        session = session.copy(deviceSn = "未知设备"), recentSessions = recentSessions,
        connectionState = connectionState, linkPhase = linkPhase, wifiAvailable = wifiAvailable,
        autoConnect = autoConnect, lastGoodEndpoint = lastGoodEndpoint, probeReport = probeReport,
        currentWifiName = currentWifiName, localIp = localIp, gatewayIp = gatewayIp, inWrenchSubnet = inWrenchSubnet,
        networkDiagnosticSummary = networkDiagnosticSummary,
        exportCsvToo = exportCsvToo, darkTheme = darkTheme, wrenchModel = wrenchModel,
    )

    override fun onCleared() {
        retryJob?.cancel()
        connection.close()
        wifiTracker.close()
        super.onCleared()
    }

    companion object {
        const val PROBE_CONNECT_TIMEOUT_MILLIS = 2_000
        const val CANDIDATE_CHECK_TIMEOUT_MILLIS = 700
        const val RETRY_DELAY_MILLIS = 3_000L
        const val WIFI_SETTLE_MILLIS = 600L
        const val MAX_AUTO_RETRIES = 3
        const val EXPORT_DIR = "WifiWrenchExports"

        const val PREF_LAST_GOOD_HOST = "last_good_host"
        const val PREF_LAST_GOOD_PORT = "last_good_port"
        const val PREF_SLAB = "slab_no"
        const val PREF_POINT = "point_no"
        const val PREF_EXPORT_CSV = "export_csv_too"
        const val PREF_DARK = "ui_dark"
        const val PREF_MODEL = "wrench_model"

        const val LABEL_REMOTE_ON = "开启远程"
        const val LABEL_REMOTE_OFF = "关闭远程"
        const val LABEL_FORWARD = "正转"
        const val LABEL_REVERSE = "反转"
        const val LABEL_STOP = "停止扳手"
        const val LABEL_PARAMS = "写入扳手参数"
        /** 0x06 应答只算到这段时间内发出的操作上。 */
        const val NAK_ATTRIBUTION_MILLIS = 3_000L
        /** 会话建好之前最多暂存这么多条通信记录。 */
        const val MAX_PENDING_FRAMES = 64
    }
}

/** 参数侧滑里的草稿；取消不写入，保存经确认后才下发。 */
data class ParameterDraft(
    val mode: Int,
    val targetTorque: String,
    val torqueLower: String,
    val torqueUpper: String,
    val targetAngle: String,
    val angleUpper: String,
    val angleLower: String,
)

fun MeasurementState.parameterDraft(): ParameterDraft = ParameterDraft(
    mode = parameterMode,
    targetTorque = parameterTargetTorque,
    torqueLower = parameterTorqueLower,
    torqueUpper = parameterTorqueUpper,
    targetAngle = parameterTargetAngle,
    angleUpper = parameterAngleUpper,
    angleLower = parameterAngleLower,
)

private fun Double?.formatLog(): String = this?.let { "%.1f".format(it) } ?: "--"

internal fun String.sanitizeUIntText(maxLength: Int = 5): String =
    filter { it.isDigit() }.take(maxLength)

internal fun String.sanitizeDecimalText(maxLength: Int = 7): String {
    val builder = StringBuilder()
    var dotSeen = false
    for (char in this) {
        when {
            char.isDigit() -> builder.append(char)
            char == '.' && !dotSeen -> {
                builder.append(char)
                dotSeen = true
            }
        }
        if (builder.length >= maxLength) break
    }
    return builder.toString()
}

private fun WifiWrenchParameterSettings.modeLabel(): String =
    if (mode == WifiWrenchParameterSettings.MODE_M1) "M1 定扭矩" else "M2 角度控制"

private fun MeasurementState.withSyncedResultSettings(result: ResultFrame): MeasurementState {
    // M2 的 28-29 是「预设扭矩实际值」（实测），不是设定值：不能回填到参数的目标扭矩里。
    val targetTorque = result.settingTorqueNm?.roundToInt()?.toString() ?: parameterTargetTorque
    // M1 的 32-33 协议固定 00 00，不是设定值：只有 M2 才把「目标角度设置值」同步回参数，免得把用户填的 M2 目标角度冲成 0。
    val settingAngle = result.targetAngleDeg?.takeIf { result.isM2 }
    val targetAngle = settingAngle?.formatInput() ?: parameterTargetAngle
    val torqueUpper = result.torqueUpperNm?.roundToInt()?.toString() ?: parameterTorqueUpper
    val torqueLower = result.torqueLowerNm?.roundToInt()?.toString() ?: parameterTorqueLower
    val angleUpper = result.angleUpperDeg?.formatInput() ?: parameterAngleUpper
    val angleLower = result.angleLowerDeg?.formatInput() ?: parameterAngleLower
    val hasAnySetting =
        result.settingTorqueNm != null ||
            settingAngle != null ||
            result.torqueUpperNm != null ||
            result.torqueLowerNm != null ||
            result.angleUpperDeg != null ||
            result.angleLowerDeg != null
    return if (hasAnySetting) {
        copy(
            parameterTargetTorque = targetTorque,
            parameterTargetAngle = targetAngle,
            parameterTorqueUpper = torqueUpper,
            parameterTorqueLower = torqueLower,
            parameterAngleUpper = angleUpper,
            parameterAngleLower = angleLower,
            parameterStatus = "已从扳手最终结果同步目标设置",
        )
    } else {
        this
    }
}

private fun Double.formatInput(): String =
    // 输入框按 toDoubleOrNull 解析：固定用「.」作小数点，不随系统语言写成「,」。
    if (abs(this - roundToInt()) < 1e-9) roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, this)
