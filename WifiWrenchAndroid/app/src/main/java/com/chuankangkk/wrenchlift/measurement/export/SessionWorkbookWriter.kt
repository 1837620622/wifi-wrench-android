package com.chuankangkk.wrenchlift.measurement.export

import com.chuankangkk.wrenchlift.measurement.MeasurementCsvExporter
import com.chuankangkk.wrenchlift.measurement.TorqueAngleWindow
import com.chuankangkk.wrenchlift.measurement.analysis.CandidateConsistency
import com.chuankangkk.wrenchlift.measurement.analysis.LoadingAnalysis
import com.chuankangkk.wrenchlift.measurement.analysis.WorkingCondition
import com.chuankangkk.wrenchlift.protocol.HexUtils
import com.chuankangkk.wrenchlift.protocol.PulseResultFrameDecoder
import com.chuankangkk.wrenchlift.protocol.ResultFrame
import com.chuankangkk.wrenchlift.protocol.ResultFrameDecoder
import com.chuankangkk.wrenchlift.protocol.ResultKind
import com.chuankangkk.wrenchlift.protocol.WrenchPacket
import com.chuankangkk.wrenchlift.protocol.WrenchPacketParser
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** 一个全量过程点（来自数据库分页，不经过界面显示窗口）。 */
data class ProcessRow(
    val index: Long,
    val receivedAtMillis: Long,
    val boltNo: Int,
    val torqueNm: Double,
    val angleDeg: Double,
    val angleRaw: Int,
    val frameLabel: String,
    val rawHex: String,
) {
    /** 0x12 过程帧（原始 HEX 第 4 字节为功能码）；结果帧等只列在「过程数据」里，不进图表/统计/候选。 */
    val isProcess: Boolean
        get() = rawHex.split(' ', limit = 5).getOrNull(3)?.toIntOrNull(16)?.let { it == 0x12 } ?: true

    /** 0x12 帧里的点数：长度字节（第 7 字节）= 12 + 4·n。解析不了返回 null。 */
    val pointsInFrame: Int?
        get() {
            if (!isProcess) return null
            val length = rawHex.split(' ', limit = 8).getOrNull(6)?.toIntOrNull(16) ?: return null
            return ((length - 12) / 4).takeIf { length >= 12 }
        }
}

/**
 * 单台扳手的一条记录：不含板号/测点等现场台账字段。
 *
 * 结果帧字段按协议原位保存：0x15 的 28-29 / 32-33 在 M1、M2 下含义不同（M1 目标扭矩 / 目标角度 0；
 * M2 预设扭矩实际值 / 目标角度设置值），导出时按 [resultRawHex] 解出功能码与模式再给字段名。
 */
data class WorkbookSummary(
    val recordId: Long,
    val deviceSn: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    val resultStatus: String,
    val targetTorqueNm: Double?,
    val actualTorqueNm: Double?,
    val targetAngleDeg: Double?,
    val actualAngleDeg: Double?,
    val torqueLowerNm: Double?,
    val torqueUpperNm: Double?,
    val angleLowerDeg: Double?,
    val angleUpperDeg: Double?,
    val appVersion: String,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    /** 最后一个结果帧（0x15 / 0x55）的原始 HEX；旧记录可能没有。 */
    val resultRawHex: String? = null,
    /** 采集前填写、采集中锁定的板号与测点。 */
    val slabNo: String = "",
    val pointNo: String = "",
)

/** 一条通信记录（收 / 发的非 0x12 帧与关键操作），来自数据库。 */
data class FrameEventRow(
    val index: Long,
    val atMillis: Long,
    /** 「收」或「发」。 */
    val direction: String,
    val functionCode: Int?,
    val summary: String,
    val rawHex: String,
)

data class WorkbookStats(val processRows: Long, val chartRows: Int, val chartCount: Int)

/**
 * 生成真 `.xlsx`（Office Open XML）：
 * - 「过程数据」全量写入，不截断；另带派生列：运行段、帧号、帧内序号、段内时间/转角、相对有效加载起点转角、
 *   帧间转速、接收间隔、工况、事件；
 * - 「运行段」逐段列出点数、转角范围、转速、峰值、单直线斜率、最佳两段位置、转折候选（未验证）或未识别原因、工况摘要；
 * - 「工况分段」「工况事件」：每段的空程 / 加载 / 停转保压 / 卸载 / 回退 / 空转等阶段与疑似打滑、间断、回退事件；
 * - 「结果帧」：每个 0x15 / 0x55 按协议逐字段解出（M1 / M2 字段名分开写），并对照所属运行段的 0x12 峰值；
 * - 「拟合明细」「候选扫描」给出转折候选算法的中间量，便于复核和调阈值；
 * - 「通信记录」：收发的非 0x12 帧（应答、状态、对时、电量、结果、控制指令等）；
 * - 「图表数据」按序号分桶保留每桶最大/最小扭矩，段与段之间留空行（图上断开，不跨段连线）；
 * - 「图表」页内嵌 Excel 原生图表：扭矩–转角（叠加转折候选）、斜率–转角、扭矩–转角与扭矩–时间（按工况着色）、
 *   各段叠加、残差改善–候选位置、转角/转速/接收间隔–接收时间，若有结果帧再加结果扭矩对照。
 *   图表、统计、候选、工况只用 0x12 过程帧。
 * 流式写 ZIP，不在内存里拼整份文件；过程点读两遍（第一遍分段分析，第二遍逐行写出带工况的过程数据）。
 */
object SessionWorkbookWriter {
    const val MAX_CHART_ROWS = 6_000
    const val EXCEL_MAX_DATA_ROWS = 1_048_575L

    const val SHEET_SUMMARY = "概要"
    const val SHEET_CHARTS = "图表"
    const val SHEET_DATA = "过程数据"
    const val SHEET_CHART_DATA = "图表数据"
    const val SHEET_RUNS = "运行段"
    const val SHEET_FIT = "拟合明细"
    const val SHEET_SCAN = "候选扫描"
    const val SHEET_PHASES = "工况分段"
    const val SHEET_EVENTS = "工况事件"
    const val SHEET_RESULTS = "结果帧"
    const val SHEET_COMM = "通信记录"
    /** 单段超过这么多点不做候选分析（内存与耗时上限）；数据照常全量导出。 */
    const val MAX_ANALYSIS_POINTS = 200_000
    /** 「拟合明细」「候选扫描」各自最多写这么多行；超出的运行段只在「运行段」页给汇总。 */
    const val MAX_DETAIL_ROWS = 100_000
    /** 叠加图 / 扫描图最多画最近几段（每段一条线，太多看不清；明细表不受此限）。 */
    const val MAX_OVERLAY_RUNS = 8
    /** 帧级时间序列（转速、接收间隔）图表点上限。 */
    const val MAX_FRAME_ROWS = 3_000
    /** 有过程点时固定的图表数（无数据的图写明「未计算」，不省略，部件数固定）。 */
    const val PROCESS_CHART_COUNT = 10
    /** 「结果帧」「工况事件」「通信记录」各自最多写这么多行。 */
    const val MAX_LIST_ROWS = 100_000

    private const val COLOR_TORQUE = "0D9B8A"
    private const val COLOR_ANGLE = "3A6EA5"
    private const val COLOR_TIME = "6B5FB5"
    private const val COLOR_BAR = "0D9B8A"
    private const val COLOR_CANDIDATE = "9A4FC2"
    private const val COLOR_THRESHOLD = "C23B4F"
    /** 叠加图各段颜色（不含候选紫色，避免混淆）。 */
    private val RUN_COLORS = listOf("0D9B8A", "3A6EA5", "D9822B", "C23B4F", "5B8C2A", "6B5FB5", "8A6D3B", "2F6F8F")
    /** 工况着色（与 [WorkingCondition.Phase] 顺序一致；异常工况用红 / 棕）。 */
    private val PHASE_COLORS = mapOf(
        WorkingCondition.Phase.IDLE to "B8C0C8",
        WorkingCondition.Phase.FREE_TRAVEL to "8A96A3",
        WorkingCondition.Phase.LOADING to "0D9B8A",
        WorkingCondition.Phase.STALL to "D9822B",
        WorkingCondition.Phase.UNLOADING to "3A6EA5",
        WorkingCondition.Phase.TORQUE_DROP to "C23B4F",
        WorkingCondition.Phase.FREE_SPIN to "8A6D3B",
        WorkingCondition.Phase.BACKOFF to "5B8C2A",
        WorkingCondition.Phase.NO_LOAD to "2F6F8F",
    )
    private val PHASES = WorkingCondition.Phase.entries
    /** 「图表数据」里工况着色列的起始列（AB）。 */
    private const val PHASE_COL = 27

    private val dataHeaders = listOf(
        "序号", "接收时间（手机）", "接收时间戳(ms)", "相对首点(s)", "距上一点(ms)",
        "运行段", "段内时间(s)", "帧号", "帧内序号", "帧内点数",
        "螺栓号", "扭矩 N·m", "转角 °", "段内转角增量 °", "相对有效加载起点 °", "帧间转速 °/s", "工况", "事件",
        "原始转角值", "帧类型", "原始HEX",
    )
    private val dataWidths = listOf(8.0, 24.0, 16.0, 11.0, 11.0, 7.0, 11.0, 8.0, 8.0, 8.0, 7.0, 10.0, 9.0, 12.0, 14.0, 12.0, 10.0, 22.0, 10.0, 12.0, 60.0)
    private val chartHeaders = listOf(
        "序号", "转角 °", "扭矩 N·m", "相对首点(s)", "",
        "斜率 转角 °", "斜率 N·m/°", "", "候选 转角 °", "候选 扭矩 N·m", "候选 运行段", "",
        "帧 相对首点(s)", "帧间转速 °/s", "帧接收间隔 ms", "",
        "叠加 运行段", "叠加 相对有效加载起点 °", "叠加 分箱平均扭矩 N·m", "",
        "扫描 运行段", "扫描 相对有效加载起点 °", "扫描 残差改善 %", "",
        "阈值 相对转角 °", "阈值 残差改善 %", "",
    ) + PHASES.map { "工况·${it.label} 扭矩 N·m" }
    private val runHeaders = listOf(
        "运行段", "首序号", "末序号", "点数", "螺栓号", "开始时间", "时长 s", "起始转角 °", "最大转角 °", "平均转速 °/s",
        "峰值扭矩 N·m", "峰值处转角 °", "有效加载起 °", "有效加载止 °", "单直线斜率 N·m/°",
        "最佳两段位置 °", "最佳残差改善 %",
        "候选转角 °", "候选扭矩 N·m", "候选相对加载起点 °", "前斜率 N·m/°", "后斜率 N·m/°", "残差改善 %", "判定",
        "工况次序", "空程转角 °", "加载转角 °", "停转保压时长 s", "疑似打滑次数", "转角回退次数", "数据间断次数", "工况判读",
    )
    private val fitHeaders = listOf(
        "运行段", "分箱序号", "转角 °", "相对有效加载起点 °", "平均扭矩 N·m", "点数", "局部斜率 N·m/°",
        "单直线拟合 N·m", "最佳两段拟合 N·m", "两段残差 N·m", "位于最佳位置",
    )
    private val scanHeaders = listOf(
        "运行段", "候选位置 c °", "相对有效加载起点 °", "残差改善 %", "前斜率 N·m/°", "后斜率 N·m/°", "斜率变化 %", "最佳",
    )
    private val phaseHeaders = listOf(
        "运行段", "阶段序号", "工况", "首序号", "末序号", "点数", "开始时间", "时长 s",
        "起始转角 °", "结束转角 °", "转角变化 °", "起始扭矩 N·m", "结束扭矩 N·m", "最大扭矩 N·m", "平均转速 °/s",
    )
    private val eventHeaders = listOf("运行段", "序号", "接收时间（手机）", "事件", "数值", "单位", "转角 °", "扭矩 N·m")
    private val resultHeaders = listOf(
        "序号", "接收时间（手机）", "所属运行段", "功能码", "状态码", "结果", "判定", "参数模式", "员工号", "螺栓号",
        "扳手时钟", "扳手时钟−手机接收 s",
        "扭矩字段1", "扭矩字段1 N·m", "扭矩字段2", "扭矩字段2 N·m", "角度字段1", "角度字段1 °", "角度字段2", "角度字段2 °",
        "扭矩下限 N·m", "扭矩上限 N·m", "角度下限 °", "角度上限 °",
        "所属段峰值扭矩(0x12) N·m", "所属段最大转角(0x12) °", "原始HEX",
    )
    private val commHeaders = listOf("序号", "时间（手机）", "时间戳(ms)", "相对首条(s)", "方向", "功能码", "类型", "内容", "原始HEX")

    /**
     * @param frameEvents 通信记录（可选，按时间顺序）；没有时「通信记录」页只写说明。
     * @param rows 按序号给出全部过程点；**会被调用两次**（第一遍分段分析，第二遍逐行写出），两次必须给出同一序列。
     */
    suspend fun write(
        output: OutputStream,
        summary: WorkbookSummary,
        totalPoints: Long,
        frameEvents: (suspend (emit: suspend (FrameEventRow) -> Unit) -> Unit)? = null,
        rows: suspend (emit: suspend (ProcessRow) -> Unit) -> Unit,
    ): WorkbookStats {
        require(totalPoints <= EXCEL_MAX_DATA_ROWS) { "过程点超过 Excel 单表行数上限，请改用 CSV 导出" }
        // 第一遍：只看 0x12 过程点，逐段做加载分析与工况划分；第二遍写「过程数据」时逐点带上工况与相对加载起点。
        val runs = RunCollector()
        rows { row -> if (row.isProcess) runs.accept(row) }
        runs.finish()

        val zip = ZipOutputStream(output.buffered(64 * 1024))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        val hasPoints = totalPoints > 0
        val lastResult = summary.resultRawHex?.let(::decodeResult)
        val hasResultChart = summary.actualTorqueNm != null
        val chartCount = (if (hasPoints) PROCESS_CHART_COUNT else 0) + (if (hasResultChart) 1 else 0)

        fun entry(name: String, body: (Writer) -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            body(writer)
            writer.flush()
            zip.closeEntry()
        }

        entry("[Content_Types].xml") { it.write(contentTypes(chartCount)) }
        entry("_rels/.rels") { it.write(rootRels()) }
        entry("docProps/core.xml") { it.write(coreProps(summary)) }
        entry("docProps/app.xml") { it.write(appProps()) }
        entry("xl/styles.xml") { it.write(Styles.xml) }

        // 第二遍：过程数据全量流式写出，同时按序号分桶抽出图表用的点、统计峰值与转角范围、收集结果帧。
        // （ZIP 内各部件顺序无关紧要，概要页放在数据之后写，才能带上统计值。）
        val sampler = ChartSampler(totalPoints)
        val stats = ProcessStats()
        val annotator = Annotator()
        val frames = FrameSeries(MAX_FRAME_ROWS)
        val cursor = EventCursor(runs.runs)
        val results = ArrayList<ResultRow>()
        var resultsOmitted = 0
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet3.xml"))
        val dataSheet = SheetWriter(writer, dataWidths, freezeHeader = true)
        dataSheet.begin()
        dataSheet.row(dataHeaders.map(::text), Styles.HEADER)
        var written = 0L
        var firstMillis: Long? = null
        rows { row ->
            val start = firstMillis ?: row.receivedAtMillis.also { firstMillis = it }
            val relativeSeconds = (row.receivedAtMillis - start) / 1000.0
            annotator.accept(row)
            val process = row.isProcess
            val run = if (process) runs.runs.getOrNull(annotator.run - 1) else null
            val point = annotator.runPoint
            val phase = run?.condition?.takeIf { point < it.pointCount }?.phaseAt(point)
            val event = if (run != null) cursor.at(annotator.run, point) else null
            val fromLoadStart = run?.result?.loadStartDeg?.let { row.angleDeg - it }
            dataSheet.row(
                listOf(
                    num(row.index), dateTime(row.receivedAtMillis), num(row.receivedAtMillis), num(relativeSeconds), num(annotator.intervalMs),
                    num(annotator.run.takeIf { process }), num(annotator.runSeconds), num(annotator.frame), num(annotator.pointInFrame), num(annotator.framePoints),
                    num(row.boltNo), num(row.torqueNm), num(row.angleDeg), num(annotator.runAngle), num(fromLoadStart?.let(::round3)),
                    num(annotator.frameSpeed?.let(::round3)), text(phase?.label), text(event),
                    num(row.angleRaw), text(row.frameLabel), text(row.rawHex),
                ),
            )
            if (process) {
                sampler.accept(row, relativeSeconds, phase)
                stats.accept(row)
                if (annotator.newFrame) {
                    frames.add(relativeSeconds, annotator.frameSpeed ?: Double.NaN, annotator.frameIntervalMs?.toDouble() ?: Double.NaN)
                }
            } else {
                decodeResult(row.rawHex)?.let { decoded ->
                    if (results.size < MAX_LIST_ROWS) results += ResultRow(row, annotator.run.takeIf { it > 0 }, decoded) else resultsOmitted += 1
                }
            }
            written += 1
        }
        dataSheet.end()
        writer.flush()
        zip.closeEntry()
        val samples = sampler.finish()

        val summaryLayout = SummaryLayout(summary, totalPoints, stats, runs.runs, lastResult, results.size + resultsOmitted)
        entry("xl/worksheets/sheet1.xml") { summaryLayout.write(it) }

        val slopes = runs.slopeRows
        val candidates = runs.runs.mapNotNull { run -> run.result.candidate?.let { run to it } }
        val frameSeries = frames.finish()
        val overlay = runs.recent.toList()
        // 叠加图 / 扫描图：每段一块连续行（各自一条系列），横轴为相对有效加载起点的转角。
        val overlayRows = ArrayList<Triple<Int, Double, Double>>()
        val overlayRanges = ArrayList<IntRange>()
        val scanRows = ArrayList<Triple<Int, Double, Double>>()
        val scanRanges = ArrayList<IntRange>()
        overlay.forEach { (run, detail) ->
            val origin = run.result.loadStartDeg ?: 0.0
            val first = overlayRows.size + 2
            detail.bins.forEach { overlayRows += Triple(run.index, round3(it.angleDeg - origin), round3(it.torqueNm)) }
            overlayRanges += first..(overlayRows.size + 1)
            val scanFirst = scanRows.size + 2
            detail.scan.forEach { scanRows += Triple(run.index, round3(it.angleDeg - origin), round3(it.improvement * 100.0)) }
            scanRanges += scanFirst..(scanRows.size + 1)
        }
        val threshold = if (scanRows.isEmpty()) emptyList() else {
            val lo = scanRows.minOf { it.second }
            val hi = scanRows.maxOf { it.second }
            listOf(lo to LoadingAnalysis.MIN_IMPROVEMENT * 100.0, hi to LoadingAnalysis.MIN_IMPROVEMENT * 100.0)
        }
        // 工况着色：每个工况一列，只在该工况的抽样点上有值；工况切换处把切换点也写进前一工况列，线不断开。
        val phaseY = PHASES.map { arrayOfNulls<Double>(samples.size) }
        var previousSample: Sample? = null
        samples.forEachIndexed { i, sample ->
            if (sample == null) {
                previousSample = null
                return@forEachIndexed
            }
            sample.phase?.let { phaseY[it.ordinal][i] = sample.torque }
            val before = previousSample?.phase
            if (before != null && before != sample.phase) phaseY[before.ordinal][i] = sample.torque
            previousSample = sample
        }
        entry("xl/worksheets/sheet4.xml") { out ->
            val sheet = SheetWriter(
                out,
                listOf(8.0, 10.0, 11.0, 12.0, 3.0, 11.0, 11.0, 3.0, 11.0, 13.0, 11.0, 3.0, 13.0, 12.0, 13.0, 3.0, 10.0, 15.0, 15.0, 3.0, 10.0, 15.0, 13.0, 3.0, 13.0, 13.0, 3.0) +
                    List(PHASES.size) { 14.0 },
                freezeHeader = true,
            )
            sheet.begin()
            sheet.row(chartHeaders.map(::text), Styles.HEADER)
            val rowCount = maxOf(maxOf(samples.size, slopes.size, candidates.size), maxOf(frameSeries.size, overlayRows.size, scanRows.size), threshold.size)
            for (i in 0 until rowCount) {
                val sample = samples.getOrNull(i)
                val slope = slopes.getOrNull(i)
                val cand = candidates.getOrNull(i)
                val frame = if (i < frameSeries.size) i else -1
                val over = overlayRows.getOrNull(i)
                val scan = scanRows.getOrNull(i)
                val limit = threshold.getOrNull(i)
                val cells = listOf(
                    num(sample?.index), num(sample?.angle), num(sample?.torque), num(sample?.seconds), Cell.Blank,
                    num(slope?.angleDeg), num(slope?.slope?.let(::round3)), Cell.Blank,
                    num(cand?.second?.angleDeg?.let(::round3)), num(cand?.second?.torqueNm?.let(::round3)), num(cand?.first?.index), Cell.Blank,
                    num(frameSeries.seconds.getOrNull(frame)), num(frameSeries.speed.getOrNull(frame)?.let(::round3)), num(frameSeries.intervalMs.getOrNull(frame)), Cell.Blank,
                    num(over?.first), num(over?.second), num(over?.third), Cell.Blank,
                    num(scan?.first), num(scan?.second), num(scan?.third), Cell.Blank,
                    num(limit?.first), num(limit?.second), Cell.Blank,
                ) + phaseY.map { num(it.getOrNull(i)) }
                if (cells.all { it == Cell.Blank }) sheet.blankRow() else sheet.row(cells)
            }
            sheet.end()
        }

        entry("xl/worksheets/sheet5.xml") { out -> writeRuns(out, runs.runs) }
        entry("xl/worksheets/sheet6.xml") { out -> writeFit(out, runs) }
        entry("xl/worksheets/sheet7.xml") { out -> writeScan(out, runs) }
        entry("xl/worksheets/sheet8.xml") { out -> writePhases(out, runs.runs) }
        entry("xl/worksheets/sheet9.xml") { out -> writeEvents(out, runs.runs) }
        entry("xl/worksheets/sheet10.xml") { out -> writeResults(out, results, resultsOmitted, runs.runs) }
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet11.xml"))
        writeComm(writer, frameEvents)
        writer.flush()
        zip.closeEntry()

        val charts = buildList {
            if (hasPoints) {
                val last = samples.size + 1
                val angle = samples.map { it?.angle }
                val torque = samples.map { it?.torque }
                val seconds = samples.map { it?.seconds }
                val candidateSeries = if (candidates.isEmpty()) emptyList() else {
                    val lastCandidate = candidates.size + 1
                    listOf(
                        ChartSpec.ScatterSeries(
                            "转折候选（未验证）",
                            Xml.range(SHEET_CHART_DATA, 8, 2, lastCandidate), Xml.range(SHEET_CHART_DATA, 9, 2, lastCandidate),
                            candidates.map { round3(it.second.angleDeg) }, candidates.map { round3(it.second.torqueNm) }, COLOR_CANDIDATE, markerOnly = true,
                        ),
                    )
                }
                add(ChartSpec.Scatter("扭矩–转角", "扭矩 N·m", "转角 °", "扭矩 N·m",
                    Xml.range(SHEET_CHART_DATA, 1, 2, last), Xml.range(SHEET_CHART_DATA, 2, 2, last), angle, torque, COLOR_TORQUE,
                    extra = candidateSeries))
                // 斜率–转角：各运行段有效加载段的局部斜率 dT/dθ；斜率在哪里明显变化，就是转折候选附近。
                val lastSlope = max(2, slopes.size + 1)
                add(ChartSpec.Scatter(if (slopes.any { it != null }) "斜率–转角（局部 dT/dθ）" else "斜率–转角（有效加载不足，未计算）",
                    "斜率 N·m/°", "转角 °", "斜率 N·m/°",
                    Xml.range(SHEET_CHART_DATA, 5, 2, lastSlope), Xml.range(SHEET_CHART_DATA, 6, 2, lastSlope),
                    slopes.map { it?.angleDeg }, slopes.map { it?.slope?.let(::round3) }, COLOR_ANGLE, yMinZero = false))
                // 工况着色：空程在哪里结束（= 有效加载起点）、停转保压多久、哪里扭矩掉下来，一眼可见。
                add(phaseChart("扭矩–转角（按工况着色）", "转角 °", 1, angle, phaseY, last))
                add(phaseChart("扭矩–接收时间（按工况着色）", "相对首点接收时间 s", 3, seconds, phaseY, last))
                add(ChartSpec.Scatter("扭矩–接收时间（手机接收时基）", "扭矩 N·m", "相对首点接收时间 s", "扭矩 N·m",
                    Xml.range(SHEET_CHART_DATA, 3, 2, last), Xml.range(SHEET_CHART_DATA, 2, 2, last), seconds, torque, COLOR_TIME))
                // 各段叠加：同一隔振器重复试验时，转折位置是否稳定一眼可见。
                add(multiRunChart(
                    title = if (overlay.isEmpty()) "各段叠加（有效加载不足，未计算）" else "各段叠加：扭矩–有效转角（对齐加载起点）",
                    xTitle = "相对有效加载起点转角 °", yTitle = "扭矩 N·m", xCol = 17, yCol = 18,
                    runs = overlay.map { it.first }, ranges = overlayRanges, rows = overlayRows, yMinZero = true, extra = emptyList(),
                ))
                // 残差改善–候选位置：峰越高越尖，说明两段直线越明显地优于单直线；红虚线为判定阈值。
                val thresholdSeries = if (threshold.isEmpty()) emptyList() else listOf(
                    ChartSpec.ScatterSeries(
                        "判定阈值 ${(LoadingAnalysis.MIN_IMPROVEMENT * 100).toInt()}%",
                        Xml.range(SHEET_CHART_DATA, 24, 2, 3), Xml.range(SHEET_CHART_DATA, 25, 2, 3),
                        threshold.map { it.first }, threshold.map { it.second }, COLOR_THRESHOLD, markerOnly = false, dashed = true,
                    ),
                )
                add(multiRunChart(
                    title = if (scanRows.isEmpty()) "残差改善–候选位置（有效加载不足，未计算）" else "残差改善–候选位置（两段直线 vs 单直线）",
                    xTitle = "候选位置（相对有效加载起点）°", yTitle = "残差改善 %", xCol = 21, yCol = 22,
                    runs = overlay.map { it.first }, ranges = scanRanges, rows = scanRows, yMinZero = true, extra = thresholdSeries,
                ))
                add(ChartSpec.Scatter("转角–接收时间（转速与停顿）", "转角 °", "相对首点接收时间 s", "转角 °",
                    Xml.range(SHEET_CHART_DATA, 3, 2, last), Xml.range(SHEET_CHART_DATA, 1, 2, last), seconds, angle, COLOR_TIME))
                val lastFrame = max(2, frameSeries.size + 1)
                val frameSeconds = frameSeries.seconds.map { it.takeIf(Double::isFinite) }
                add(ChartSpec.Scatter("转速–接收时间（帧间 °/s，段首空缺）", "转速 °/s", "相对首点接收时间 s", "转速 °/s",
                    Xml.range(SHEET_CHART_DATA, 12, 2, lastFrame), Xml.range(SHEET_CHART_DATA, 13, 2, lastFrame),
                    frameSeconds, frameSeries.speed.map { it.takeIf(Double::isFinite)?.let(::round3) }, COLOR_ANGLE, yMinZero = false))
                add(ChartSpec.Scatter("帧接收间隔–接收时间（段内，ms）", "接收间隔 ms", "相对首点接收时间 s", "接收间隔 ms",
                    Xml.range(SHEET_CHART_DATA, 12, 2, lastFrame), Xml.range(SHEET_CHART_DATA, 14, 2, lastFrame),
                    frameSeconds, frameSeries.intervalMs.map { it.takeIf(Double::isFinite) }, COLOR_TIME))
            }
            if (hasResultChart) {
                val firstRow = summaryLayout.torqueFirstRow
                val labels = summaryLayout.torqueLabels
                add(ChartSpec.Bar("结果帧扭矩（${lastResult?.modeText ?: "模式未知"}）", "扭矩 N·m", "扭矩 N·m",
                    Xml.range(SHEET_SUMMARY, 0, firstRow, firstRow + labels.size - 1), Xml.range(SHEET_SUMMARY, 1, firstRow, firstRow + labels.size - 1),
                    labels, listOf(summary.targetTorqueNm, summary.actualTorqueNm, summary.torqueLowerNm, summary.torqueUpperNm), COLOR_BAR))
            }
        }
        check(charts.size == chartCount) { "图表数与内容类型清单不一致" }

        entry("xl/worksheets/sheet2.xml") { out ->
            val sheet = SheetWriter(out, emptyList(), freezeHeader = false)
            sheet.begin()
            sheet.row(listOf(text("WiFi 扳手 · 记录 R${summary.recordId} · ${formatTime(summary.startTimeMillis).take(16)}")), Styles.TITLE)
            sheet.row(listOf(text(chartNote(hasPoints, totalPoints, samples.count { it != null }))), Styles.NOTE)
            sheet.end(drawingRelId = if (charts.isNotEmpty()) "rId1" else null)
        }
        if (charts.isNotEmpty()) {
            entry("xl/worksheets/_rels/sheet2.xml.rels") { it.write(relationships(listOf(Rel("rId1", REL_DRAWING, "../drawings/drawing1.xml")))) }
            entry("xl/drawings/drawing1.xml") { it.write(ChartXml.drawing(charts.size)) }
            entry("xl/drawings/_rels/drawing1.xml.rels") { out ->
                out.write(relationships(charts.indices.map { Rel("rId${it + 1}", REL_CHART, "../charts/chart${it + 1}.xml") }))
            }
            charts.forEachIndexed { index, spec -> entry("xl/charts/chart${index + 1}.xml") { it.write(ChartXml.render(spec)) } }
        }

        entry("xl/workbook.xml") { it.write(workbook()) }
        entry("xl/_rels/workbook.xml.rels") { out ->
            out.write(relationships((1..SHEET_PARTS).map { Rel("rId$it", REL_SHEET, "worksheets/sheet$it.xml") } + Rel("rId${SHEET_PARTS + 1}", REL_STYLES, "styles.xml")))
        }
        writer.flush()
        zip.finish()
        zip.flush()
        return WorkbookStats(processRows = written, chartRows = samples.count { it != null }, chartCount = charts.size)
    }

    /** 工况着色图：每个出现过的工况一条系列（引用「图表数据」AB 起各工况列），共用抽样点的横轴列。 */
    private fun phaseChart(title: String, xTitle: String, xCol: Int, xValues: List<Double?>, phaseY: List<Array<Double?>>, last: Int): ChartSpec.Scatter {
        val present = PHASES.filter { phase -> phaseY[phase.ordinal].any { it != null } }
        val xRef = Xml.range(SHEET_CHART_DATA, xCol, 2, max(2, last))
        fun yRef(phase: WorkingCondition.Phase) = Xml.range(SHEET_CHART_DATA, PHASE_COL + phase.ordinal, 2, max(2, last))
        if (present.isEmpty()) {
            return ChartSpec.Scatter("$title（未计算）", "扭矩 N·m", xTitle, "扭矩 N·m", xRef, yRef(PHASES.first()),
                emptyList(), emptyList(), PHASE_COLORS.getValue(PHASES.first()))
        }
        val head = present.first()
        return ChartSpec.Scatter(
            title, head.label, xTitle, "扭矩 N·m", xRef, yRef(head), xValues, phaseY[head.ordinal].toList(), PHASE_COLORS.getValue(head),
            extra = present.drop(1).map { phase ->
                ChartSpec.ScatterSeries(phase.label, xRef, yRef(phase), xValues, phaseY[phase.ordinal].toList(), PHASE_COLORS.getValue(phase), markerOnly = false)
            },
            legend = true,
        )
    }

    private fun chartNote(hasPoints: Boolean, total: Long, sampled: Int): String = when {
        !hasPoints -> "本记录只有扳手结果帧，没有过程点。"
        sampled.toLong() < total -> "图表点 $sampled / 全量 $total（按序号分桶，保留每桶最大、最小扭矩）；运行段之间断开。$CHART_LEGEND"
        else -> "图表点 = 全量 $total；运行段之间断开。$CHART_LEGEND"
    }

    private val CHART_LEGEND = "紫色菱形为转折候选（未验证）；工况着色图里灰 = 空程、绿 = 加载、橙 = 停转保压、蓝 = 卸载、红 = 转动降扭；" +
        "叠加图与残差改善图画最近 $MAX_OVERLAY_RUNS 段，全部段见「拟合明细」「候选扫描」。"

    /** 新运行段：与实时图同一口径（螺栓号变化、转角回落或回到 0 附近、接收间隔过长）。 */
    private fun isNewRun(previous: ProcessRow, row: ProcessRow): Boolean = TorqueAngleWindow.isNewRun(
        previous.boltNo, previous.angleDeg, previous.receivedAtMillis, row.boltNo, row.angleDeg, row.receivedAtMillis,
    )

    /** 一个运行段的统计、转折候选与工况（[result] 不含斜率/明细，明细另存在 [detail]，超出行数上限时为 null）。 */
    private class RunSummary(
        val index: Int,
        val firstSeq: Long,
        val lastSeq: Long,
        val count: Int,
        val boltNo: Int,
        val startMillis: Long,
        val endMillis: Long,
        val startAngle: Double,
        val maxAngle: Double,
        val result: LoadingAnalysis.Result,
        val lineSlope: Double?,
        val best: LoadingAnalysis.BestHinge?,
        val detail: LoadingAnalysis.Detail?,
        /** 工况划分；单段点数超过 [MAX_ANALYSIS_POINTS] 时为 null。 */
        val condition: WorkingCondition.Result?,
        /** 各阶段首末点的记录内序号（每阶段两个数）。 */
        val segmentSeqs: LongArray,
        /** 各事件所在点的记录内序号、接收时间、转角、扭矩（与 condition.events 一一对应）。 */
        val eventSeqs: LongArray,
        val eventMillis: LongArray,
        val eventAngles: DoubleArray,
        val eventTorques: DoubleArray,
    ) {
        val seconds: Double get() = (endMillis - startMillis) / 1000.0

        /** 段内平均转速：（最大转角 − 起始转角）/ 时长。 */
        val meanSpeed: Double?
            get() = if (seconds > 0.0 && maxAngle.isFinite()) (maxAngle - startAngle) / seconds else null
    }

    /**
     * 逐段收集过程点，段结束时做加载分析（带计算明细）；局部斜率按段拼接（段间空行），最多 [MAX_CHART_ROWS] 行；
     * 明细累计不超过 [MAX_DETAIL_ROWS] 行；最近 [MAX_OVERLAY_RUNS] 个有分箱数据的段留给叠加图。
     */
    private class RunCollector {
        val runs = ArrayList<RunSummary>()
        val slopeRows = ArrayList<LoadingAnalysis.SlopePoint?>()
        val recent = ArrayDeque<Pair<RunSummary, LoadingAnalysis.Detail>>()
        var fitRows = 0
            private set
        var scanRows = 0
            private set
        /** 因明细行数上限没写进「拟合明细」「候选扫描」的段数。 */
        var detailOmitted = 0
            private set
        private var angles = DoubleArray(1_024)
        private var torques = DoubleArray(1_024)
        private var millis = LongArray(1_024)
        private var seqs = LongArray(1_024)
        private var n = 0
        private var first: ProcessRow? = null
        private var last: ProcessRow? = null
        private var maxAngle = Double.NEGATIVE_INFINITY

        fun accept(row: ProcessRow) {
            val prev = last
            if (prev != null && isNewRun(prev, row)) finishRun()
            if (first == null) first = row
            if (n < MAX_ANALYSIS_POINTS) {
                if (n == angles.size) {
                    angles = angles.copyOf(n * 2)
                    torques = torques.copyOf(n * 2)
                    millis = millis.copyOf(n * 2)
                    seqs = seqs.copyOf(n * 2)
                }
                angles[n] = row.angleDeg
                torques[n] = row.torqueNm
                millis[n] = row.receivedAtMillis
                seqs[n] = row.index
            }
            n += 1
            if (row.angleDeg.isFinite()) maxAngle = max(maxAngle, row.angleDeg)
            last = row
        }

        fun finish() = finishRun()

        private fun finishRun() {
            val start = first ?: return
            val end = last ?: return
            val result = if (n > MAX_ANALYSIS_POINTS) {
                LoadingAnalysis.Result.none("单段点数超过 $MAX_ANALYSIS_POINTS，未分析", n)
            } else {
                LoadingAnalysis.analyze(angles, torques, n, withDetail = true)
            }
            val condition = if (n > MAX_ANALYSIS_POINTS) null else WorkingCondition.classify(angles, torques, millis, n)
            val segmentSeqs = condition?.segments?.let { list -> LongArray(list.size * 2) { k -> seqs[if (k % 2 == 0) list[k / 2].start else list[k / 2].end] } } ?: LongArray(0)
            val events = condition?.events.orEmpty()
            val eventSeqs = LongArray(events.size) { k -> seqs[events[k].index] }
            val eventMillis = LongArray(events.size) { k -> millis[events[k].index] }
            val eventAngles = DoubleArray(events.size) { k -> angles[events[k].index] }
            val eventTorques = DoubleArray(events.size) { k -> torques[events[k].index] }
            val detail = result.detail
            val kept = detail?.takeIf { fitRows + it.bins.size <= MAX_DETAIL_ROWS && scanRows + it.scan.size <= MAX_DETAIL_ROWS }
            if (kept != null) {
                fitRows += kept.bins.size
                scanRows += kept.scan.size
            } else if (detail != null && detail.bins.isNotEmpty()) {
                detailOmitted += 1
            }
            val summary = RunSummary(
                index = runs.size + 1, firstSeq = start.index, lastSeq = end.index, count = n, boltNo = start.boltNo,
                startMillis = start.receivedAtMillis, endMillis = end.receivedAtMillis,
                startAngle = start.angleDeg, maxAngle = maxAngle,
                result = result.copy(slopes = emptyList(), detail = null),
                lineSlope = detail?.lineSlope, best = detail?.best, detail = kept,
                condition = condition, segmentSeqs = segmentSeqs,
                eventSeqs = eventSeqs, eventMillis = eventMillis, eventAngles = eventAngles, eventTorques = eventTorques,
            )
            runs += summary
            if (detail != null && detail.bins.isNotEmpty()) {
                recent.addLast(summary to detail)
                if (recent.size > MAX_OVERLAY_RUNS) recent.removeFirst()
            }
            if (result.slopes.isNotEmpty() && slopeRows.size + result.slopes.size + 1 <= MAX_CHART_ROWS) {
                if (slopeRows.isNotEmpty()) slopeRows += null
                slopeRows.addAll(result.slopes)
            }
            first = null
            last = null
            n = 0
            maxAngle = Double.NEGATIVE_INFINITY
        }
    }

    private fun writeRuns(out: Writer, runs: List<RunSummary>) {
        val sheet = SheetWriter(
            out,
            listOf(7.0, 9.0, 9.0, 8.0, 7.0, 22.0, 8.0) + List(15) { 11.0 } + listOf(36.0, 40.0) + List(6) { 11.0 } + listOf(36.0),
            freezeHeader = true,
        )
        sheet.begin()
        sheet.row(runHeaders.map(::text), Styles.HEADER)
        runs.forEach { run ->
            val r = run.result
            val c = r.candidate
            sheet.row(
                listOf(
                    num(run.index), num(run.firstSeq), num(run.lastSeq), num(run.count), num(run.boltNo),
                    dateTime(run.startMillis), num(run.seconds),
                    num(run.startAngle), num(run.maxAngle.takeIf { it.isFinite() }), derived(run.meanSpeed),
                    num(r.peakTorqueNm), num(r.angleAtPeakDeg), num(r.loadStartDeg), num(r.loadEndDeg), derived(run.lineSlope),
                    derived(run.best?.angleDeg), derived(run.best?.improvement?.times(100.0)),
                    derived(c?.angleDeg), derived(c?.torqueNm), derived(CandidateConsistency.relativeAngle(r)), derived(c?.slopeBefore), derived(c?.slopeAfter), derived(c?.improvement?.times(100.0)),
                    text(if (c != null) "转折候选（未验证）" else (r.outcome as LoadingAnalysis.NotFound).reason),
                ) + conditionCells(run.condition),
            )
        }
        sheet.row(emptyList())
        RUN_NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }
        sheet.end()
    }

    private fun conditionCells(condition: WorkingCondition.Result?): List<Cell> {
        if (condition == null) return listOf(text("未分析")) + List(7) { Cell.Blank }
        return listOf(
            text(condition.sequence()),
            derived(condition.angleIn(WorkingCondition.Phase.FREE_TRAVEL)),
            derived(condition.angleIn(WorkingCondition.Phase.LOADING)),
            derived(condition.secondsIn(WorkingCondition.Phase.STALL)),
            num(condition.count(WorkingCondition.EventKind.SLIP)),
            num(condition.count(WorkingCondition.EventKind.BACKTRACK)),
            num(condition.count(WorkingCondition.EventKind.GAP)),
            text(condition.verdict),
        )
    }

    /** 工况分段：每段按阶段列出起止序号、时间、转角、扭矩与平均转速。 */
    private fun writePhases(out: Writer, runs: List<RunSummary>) {
        val sheet = SheetWriter(out, listOf(7.0, 8.0, 10.0, 9.0, 9.0, 7.0, 22.0, 8.0, 10.0, 10.0, 10.0, 12.0, 12.0, 12.0, 12.0), freezeHeader = true)
        sheet.begin()
        sheet.row(phaseHeaders.map(::text), Styles.HEADER)
        var written = 0
        var omitted = 0
        runs.forEach { run ->
            val condition = run.condition ?: return@forEach
            condition.segments.forEachIndexed { k, seg ->
                if (written >= MAX_DETAIL_ROWS) {
                    omitted += 1
                    return@forEachIndexed
                }
                val speed = if (seg.seconds > 0.0) seg.angleChange / seg.seconds else null
                sheet.row(
                    listOf(
                        num(run.index), num(k + 1), text(seg.phase.label), num(run.segmentSeqs[2 * k]), num(run.segmentSeqs[2 * k + 1]), num(seg.points),
                        dateTime(seg.startMillis), num(seg.seconds), num(seg.startAngle), num(seg.endAngle), derived(seg.angleChange),
                        num(seg.startTorque), num(seg.endTorque), num(seg.maxTorque), derived(speed),
                    ),
                )
                written += 1
            }
        }
        sheet.row(emptyList())
        PHASE_NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }
        if (omitted > 0) sheet.row(listOf(text("另有 $omitted 个阶段超过行数上限（$MAX_DETAIL_ROWS 行）未列出；逐点工况见「过程数据」的「工况」列。")), Styles.NOTE)
        sheet.end()
    }

    /** 工况事件：疑似打滑、数据间断、转角回退，逐条列出位置与数值。 */
    private fun writeEvents(out: Writer, runs: List<RunSummary>) {
        val sheet = SheetWriter(out, listOf(7.0, 9.0, 24.0, 10.0, 10.0, 10.0, 10.0, 12.0), freezeHeader = true)
        sheet.begin()
        sheet.row(eventHeaders.map(::text), Styles.HEADER)
        var written = 0
        var omitted = 0
        runs.forEach { run ->
            val condition = run.condition ?: return@forEach
            condition.events.forEachIndexed { k, event ->
                if (written >= MAX_LIST_ROWS) {
                    omitted += 1
                    return@forEachIndexed
                }
                sheet.row(
                    listOf(
                        num(run.index), num(run.eventSeqs[k]), dateTime(run.eventMillis[k]), text(event.kind.label), derived(event.value), text(event.kind.unit),
                        num(run.eventAngles[k]), num(run.eventTorques[k]),
                    ),
                )
                written += 1
            }
        }
        if (written == 0) sheet.row(listOf(text("本记录未发现疑似打滑、数据间断或转角回退。")), Styles.NOTE)
        sheet.row(emptyList())
        EVENT_NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }
        if (omitted > 0) sheet.row(listOf(text("另有 $omitted 条事件超过行数上限（$MAX_LIST_ROWS 行）未列出；逐点事件见「过程数据」的「事件」列。")), Styles.NOTE)
        sheet.end()
    }

    /** 结果帧：每个 0x15 / 0x55 按协议逐字段解出；M1 / M2 字段含义不同，字段名逐行写明。 */
    private fun writeResults(out: Writer, results: List<ResultRow>, omitted: Int, runs: List<RunSummary>) {
        val sheet = SheetWriter(
            out,
            listOf(8.0, 24.0, 9.0, 7.0, 7.0, 12.0, 7.0, 16.0, 12.0, 7.0, 20.0, 12.0, 16.0, 12.0, 16.0, 12.0, 18.0, 11.0, 16.0, 11.0, 11.0, 11.0, 11.0, 11.0, 15.0, 15.0, 60.0),
            freezeHeader = true,
        )
        sheet.begin()
        sheet.row(resultHeaders.map(::text), Styles.HEADER)
        results.forEach { item ->
            val r = item.decoded
            val run = item.run?.let { runs.getOrNull(it - 1) }
            sheet.row(
                listOf(
                    num(item.row.index), dateTime(item.row.receivedAtMillis), num(item.run), text(hex(r.functionCode)), text(r.statusCode?.let(::hex)),
                    text(r.statusLabel), text(r.kindLabel), text(r.modeText), text(r.employeeId), num(r.boltNo),
                    dateTime(r.clockMillis), num(r.clockMillis?.let { (it - item.row.receivedAtMillis) / 1000.0 }),
                    text(r.torque1Name), num(r.torque1), text(r.torque2Name), num(r.torque2),
                    text(r.angle1Name), num(r.angle1), text(r.angle2Name), num(r.angle2),
                    num(r.torqueLower), num(r.torqueUpper), num(r.angleLower), num(r.angleUpper),
                    num(run?.result?.peakTorqueNm), num(run?.maxAngle?.takeIf { it.isFinite() }), text(item.row.rawHex),
                ),
            )
        }
        if (results.isEmpty()) sheet.row(listOf(text("本记录没有收到 0x15 / 0x55 结果帧。")), Styles.NOTE)
        sheet.row(emptyList())
        RESULT_NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }
        if (omitted > 0) sheet.row(listOf(text("另有 $omitted 个结果帧超过行数上限（$MAX_LIST_ROWS 行）未列出，原始 HEX 见「过程数据」。")), Styles.NOTE)
        sheet.end()
    }

    /** 通信记录：按时间列出收发的非 0x12 帧与关键操作（0x12 逐点在「过程数据」）。 */
    private suspend fun writeComm(out: Writer, events: (suspend (emit: suspend (FrameEventRow) -> Unit) -> Unit)?) {
        val sheet = SheetWriter(out, listOf(8.0, 24.0, 16.0, 11.0, 6.0, 8.0, 14.0, 40.0, 60.0), freezeHeader = true)
        sheet.begin()
        sheet.row(commHeaders.map(::text), Styles.HEADER)
        var written = 0
        var omitted = 0
        var first: Long? = null
        events?.invoke { event ->
            if (written >= MAX_LIST_ROWS) {
                omitted += 1
                return@invoke
            }
            val start = first ?: event.atMillis.also { first = it }
            sheet.row(
                listOf(
                    num(event.index), dateTime(event.atMillis), num(event.atMillis), num((event.atMillis - start) / 1000.0), text(event.direction),
                    text(event.functionCode?.let(::hex)), text(event.functionCode?.let(MeasurementCsvExporter::functionLabel)), text(event.summary), text(event.rawHex),
                ),
            )
            written += 1
        }
        if (written == 0) sheet.row(listOf(text("本记录没有通信记录（旧版本记录，或连接期间没有非 0x12 帧）。")), Styles.NOTE)
        sheet.row(emptyList())
        COMM_NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }
        if (omitted > 0) sheet.row(listOf(text("另有 $omitted 条超过行数上限（$MAX_LIST_ROWS 行）未列出。")), Styles.NOTE)
        sheet.end()
    }

    private fun hex(value: Int): String = "0x%02X".format(value and 0xFF)

    /** 拟合得出的量（非原始采样）保留 3 位小数，避免 301.9806253 这类假精度。 */
    private fun derived(value: Double?) = num(value?.let(::round3))

    private fun round3(value: Double): Double = Math.rint(value * 1_000.0) / 1_000.0

    /** 拟合明细：每段有效加载的分箱数据、局部斜率、单直线与最佳两段直线的拟合值和残差。 */
    private fun writeFit(out: Writer, collector: RunCollector) {
        val sheet = SheetWriter(out, listOf(7.0, 8.0, 10.0, 14.0, 12.0, 7.0, 13.0, 13.0, 15.0, 12.0, 11.0), freezeHeader = true)
        sheet.begin()
        sheet.row(fitHeaders.map(::text), Styles.HEADER)
        collector.runs.forEach { run ->
            val detail = run.detail ?: return@forEach
            val origin = run.result.loadStartDeg ?: 0.0
            val best = detail.best
            detail.bins.forEachIndexed { k, bin ->
                val line = if (detail.lineIntercept != null && detail.lineSlope != null) detail.lineIntercept + detail.lineSlope * bin.angleDeg else null
                val hinge = best?.at(bin.angleDeg)
                sheet.row(
                    listOf(
                        num(run.index), num(k + 1), derived(bin.angleDeg), derived(bin.angleDeg - origin), derived(bin.torqueNm), num(bin.count),
                        derived(bin.slope), derived(line), derived(hinge), derived(hinge?.let { bin.torqueNm - it }),
                        text(best?.let { if (bin.angleDeg < it.angleDeg) "前" else "后" } ?: ""),
                    ),
                )
            }
        }
        sheet.row(emptyList())
        FIT_NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }
        if (collector.detailOmitted > 0) sheet.row(listOf(text("另有 ${collector.detailOmitted} 段超过明细行数上限（$MAX_DETAIL_ROWS 行）未列出，汇总见「运行段」。")), Styles.NOTE)
        sheet.end()
    }

    /** 候选扫描：每段在每个允许的候选位置 c 上做两段直线拟合的结果；残差改善最高处即「最佳两段位置」。 */
    private fun writeScan(out: Writer, collector: RunCollector) {
        val sheet = SheetWriter(out, listOf(7.0, 12.0, 14.0, 12.0, 13.0, 13.0, 11.0, 7.0), freezeHeader = true)
        sheet.begin()
        sheet.row(scanHeaders.map(::text), Styles.HEADER)
        collector.runs.forEach { run ->
            val detail = run.detail ?: return@forEach
            val origin = run.result.loadStartDeg ?: 0.0
            val bestAngle = detail.best?.angleDeg
            detail.scan.forEach { point ->
                val change = abs(point.slopeAfter - point.slopeBefore) / max(max(abs(point.slopeBefore), abs(point.slopeAfter)), 1e-9)
                sheet.row(
                    listOf(
                        num(run.index), derived(point.angleDeg), derived(point.angleDeg - origin), derived(point.improvement * 100.0),
                        derived(point.slopeBefore), derived(point.slopeAfter), derived(change * 100.0),
                        text(if (point.angleDeg == bestAngle) "最佳" else ""),
                    ),
                )
            }
        }
        sheet.row(emptyList())
        SCAN_NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }
        if (collector.detailOmitted > 0) sheet.row(listOf(text("另有 ${collector.detailOmitted} 段超过明细行数上限（$MAX_DETAIL_ROWS 行）未列出，汇总见「运行段」。")), Styles.NOTE)
        sheet.end()
    }

    /** 多段叠加图：每段一条系列（第一段为主系列，其余为叠加系列），引用「图表数据」里该段的连续行。 */
    private fun multiRunChart(
        title: String,
        xTitle: String,
        yTitle: String,
        xCol: Int,
        yCol: Int,
        runs: List<RunSummary>,
        ranges: List<IntRange>,
        rows: List<Triple<Int, Double, Double>>,
        yMinZero: Boolean,
        extra: List<ChartSpec.ScatterSeries>,
    ): ChartSpec.Scatter {
        fun name(run: RunSummary) = "第 ${run.index} 段" + (run.result.candidate?.let { " · 候选 ${Xml.number(Math.rint(it.angleDeg * 10.0) / 10.0)}°" } ?: "")
        fun values(range: IntRange, pick: (Triple<Int, Double, Double>) -> Double) = range.map { pick(rows[it - 2]) }
        val series = runs.indices.filter { !ranges[it].isEmpty() }
        if (series.isEmpty()) {
            return ChartSpec.Scatter(title, yTitle, xTitle, yTitle, Xml.range(SHEET_CHART_DATA, xCol, 2, 2), Xml.range(SHEET_CHART_DATA, yCol, 2, 2),
                emptyList(), emptyList(), RUN_COLORS[0], extra = extra, yMinZero = yMinZero)
        }
        val head = series.first()
        val others = series.drop(1).mapIndexed { k, i ->
            ChartSpec.ScatterSeries(
                name(runs[i]), Xml.range(SHEET_CHART_DATA, xCol, ranges[i].first, ranges[i].last), Xml.range(SHEET_CHART_DATA, yCol, ranges[i].first, ranges[i].last),
                values(ranges[i]) { it.second }, values(ranges[i]) { it.third }, RUN_COLORS[(k + 1) % RUN_COLORS.size], markerOnly = false,
            )
        }
        return ChartSpec.Scatter(
            title, name(runs[head]), xTitle, yTitle,
            Xml.range(SHEET_CHART_DATA, xCol, ranges[head].first, ranges[head].last), Xml.range(SHEET_CHART_DATA, yCol, ranges[head].first, ranges[head].last),
            values(ranges[head]) { it.second }, values(ranges[head]) { it.third }, RUN_COLORS[0],
            extra = others + extra, yMinZero = yMinZero, legend = true,
        )
    }

    private val FIT_NOTES = listOf(
        "每段有效加载（扭矩达到峰值 5% 起、到峰值止）按转角分箱：箱宽 1°，跨度很大时放宽到不超过 ${LoadingAnalysis.MAX_BINS} 箱；平均扭矩为箱内各点平均。",
        "局部斜率：每箱前后约 跨度/8（4°–40°）内各箱的加权直线斜率，两端箱不出值。",
        "最佳两段拟合：在全部允许位置里残差最小的两段直线（不论是否通过判定）；是否给出转折候选看「运行段」的判定列。",
    )

    private val SCAN_NOTES = listOf(
        "残差改善 = 1 − 两段直线残差平方和 / 单直线残差平方和（含箱内离散）；越接近 100% 说明折线越明显。",
        "候选位置两侧各至少留 ${(LoadingAnalysis.SIDE_FRACTION * 100).toInt()}% 有效转角跨度与 ${LoadingAnalysis.MIN_SIDE_BINS} 个箱；判定阈值：残差改善 ≥ ${(LoadingAnalysis.MIN_IMPROVEMENT * 100).toInt()}%、斜率变化 ≥ ${(LoadingAnalysis.MIN_SLOPE_CHANGE * 100).toInt()}%（开发默认值，未经试验标定）。",
        "峰尖而高：转折位置明确；峰平或多峰：位置不稳定，不能当结论。",
    )

    private val RUN_NOTES = listOf(
        "转折候选：在每段有效加载（扭矩达到峰值 5% 起、到峰值止）上，用两段直线 T = β0 + m1·θ + (m2−m1)·max(0, θ−c) 遍历 c 拟合，残差明显小于单直线、前后斜率差别够大时给出。",
        "判定阈值为开发默认值（残差改善 ≥ 50%、斜率变化 ≥ 30%、两侧各 ≥ 15% 转角跨度），未经试验标定。候选只供平衡点（临界分离）研究参考，未经实测验证，不是平衡点结论。",
        "前斜率 / 后斜率单位 N·m/°；转角为扳手 0x12 原始整数度。",
        "候选相对加载起点 = 候选转角 − 有效加载起点转角：各段起转位置不同，比较多次加载时看这一列，不看绝对转角。",
    )

    /**
     * 「过程数据」的派生列（流式逐行计算，不回看）：运行段（与图表同一切分规则）、帧号 / 帧内序号、
     * 段内时间与转角增量、帧间转速（本帧首点 vs 上一帧首点）、与上一点 / 上一帧的接收间隔。
     */
    private class Annotator {
        var run = 0
            private set
        var frame = 0L
            private set
        var pointInFrame = 0
            private set
        var framePoints: Int? = null
            private set
        var newFrame = false
            private set
        var intervalMs: Long? = null
            private set
        /** 段内与上一过程帧的接收间隔，只在过程帧首点给出（段首为 null）。 */
        var frameIntervalMs: Long? = null
            private set
        var runSeconds: Double? = null
            private set
        var runAngle: Double? = null
            private set
        var frameSpeed: Double? = null
            private set
        /** 本点在所属运行段里的下标（从 0 起，只数过程点）；与第一遍的段内下标一致。 */
        var runPoint = 0
            private set
        private var previous: ProcessRow? = null
        private var previousProcess: ProcessRow? = null
        private var runStartMillis = 0L
        private var runStartAngle = 0.0
        private var lastFrameAngle: Double? = null
        private var lastFrameMillis = 0L
        private var processFrameSpeed: Double? = null

        fun accept(row: ProcessRow) {
            val prev = previous
            intervalMs = prev?.let { row.receivedAtMillis - it.receivedAtMillis }
            // 同一帧：原始 HEX 与接收时间都相同，且还没数满该帧声明的点数（防止两帧字节完全相同、又在同一毫秒到达时被并成一帧）。
            val frameFull = framePoints?.let { pointInFrame >= it } ?: false
            newFrame = prev == null || row.rawHex != prev.rawHex || row.receivedAtMillis != prev.receivedAtMillis || frameFull
            if (newFrame) {
                frame += 1
                pointInFrame = 1
                framePoints = row.pointsInFrame
            } else {
                pointInFrame += 1
            }
            previous = row
            if (!row.isProcess) {
                runSeconds = null
                runAngle = null
                frameSpeed = null
                frameIntervalMs = null
                return
            }
            val prevProcess = previousProcess
            if (prevProcess == null || isNewRun(prevProcess, row)) {
                run += 1
                runPoint = 0
                runStartMillis = row.receivedAtMillis
                runStartAngle = row.angleDeg
                lastFrameAngle = null
                processFrameSpeed = null
                frameIntervalMs = null
                if (newFrame) {
                    lastFrameAngle = row.angleDeg
                    lastFrameMillis = row.receivedAtMillis
                }
            } else {
                runPoint += 1
                if (newFrame) {
                    val dt = (row.receivedAtMillis - lastFrameMillis) / 1000.0
                    frameIntervalMs = row.receivedAtMillis - lastFrameMillis
                    processFrameSpeed = lastFrameAngle?.takeIf { dt > 0.0 }?.let { (row.angleDeg - it) / dt }
                    lastFrameAngle = row.angleDeg
                    lastFrameMillis = row.receivedAtMillis
                }
            }
            frameSpeed = processFrameSpeed
            runSeconds = (row.receivedAtMillis - runStartMillis) / 1000.0
            runAngle = row.angleDeg - runStartAngle
            previousProcess = row
        }
    }

    /** 帧级时间序列（每个过程帧一行）的流式降采样结果；NaN 表示段首空缺（图上断开）。 */
    private class FrameRows(val seconds: List<Double>, val speed: List<Double>, val intervalMs: List<Double>) {
        val size: Int get() = seconds.size
    }

    /**
     * 帧级时间序列流式降采样：点数到 2×[limit] 时相邻两点合并（转速取均值、遇段首空缺保留空缺；接收间隔取最大，
     * 保住通信卡顿），之后按加倍的步长继续合并。内存与帧数无关。
     */
    private class FrameSeries(private val limit: Int) {
        private var t = DoubleArray(256)
        private var v = DoubleArray(256)
        private var g = DoubleArray(256)
        private var size = 0
        private var stride = 1
        private var pending = 0
        private var pt = 0.0
        private var pv = 0.0
        private var pvCount = 0
        private var pBreak = false
        private var pg = Double.NaN

        fun add(seconds: Double, speed: Double, gapMs: Double) {
            if (pending == 0) pt = seconds
            if (speed.isNaN()) pBreak = true else { pv += speed; pvCount += 1 }
            pg = maxOrNaN(pg, gapMs)
            pending += 1
            if (pending >= stride) flush()
        }

        private fun flush() {
            if (pending == 0) return
            push(pt, if (pBreak || pvCount == 0) Double.NaN else pv / pvCount, pg)
            pending = 0
            pv = 0.0
            pvCount = 0
            pBreak = false
            pg = Double.NaN
        }

        private fun push(a: Double, b: Double, c: Double) {
            if (size == t.size) {
                t = t.copyOf(size * 2)
                v = v.copyOf(size * 2)
                g = g.copyOf(size * 2)
            }
            t[size] = a
            v[size] = b
            g[size] = c
            size += 1
            if (size >= 2 * limit) compact()
        }

        private fun compact() {
            var j = 0
            var i = 0
            while (i < size) {
                if (i + 1 < size) {
                    t[j] = t[i]
                    v[j] = if (v[i].isNaN() || v[i + 1].isNaN()) Double.NaN else (v[i] + v[i + 1]) / 2.0
                    g[j] = maxOrNaN(g[i], g[i + 1])
                } else {
                    t[j] = t[i]
                    v[j] = v[i]
                    g[j] = g[i]
                }
                j += 1
                i += 2
            }
            size = j
            stride *= 2
        }

        fun finish(): FrameRows {
            flush()
            return FrameRows(t.copyOf(size).toList(), v.copyOf(size).toList(), g.copyOf(size).toList())
        }

        private fun maxOrNaN(a: Double, b: Double) = when {
            a.isNaN() -> b
            b.isNaN() -> a
            else -> max(a, b)
        }
    }

    /** 过程数据统计（只统计实测值，不做任何推算）。 */
    private class ProcessStats {
        var peakTorque: Double? = null
            private set
        var angleAtPeak: Double? = null
            private set
        var minAngle: Double? = null
            private set
        var maxAngle: Double? = null
            private set
        var firstMillis: Long? = null
            private set
        var lastMillis: Long? = null
            private set
        var runs: Int = 0
            private set
        private var previous: ProcessRow? = null

        fun accept(row: ProcessRow) {
            val prev = previous
            if (prev == null || isNewRun(prev, row)) runs += 1
            if (row.torqueNm.isFinite() && (peakTorque == null || row.torqueNm > peakTorque!!)) {
                peakTorque = row.torqueNm
                angleAtPeak = row.angleDeg
            }
            if (row.angleDeg.isFinite()) {
                minAngle = minAngle?.let { minOf(it, row.angleDeg) } ?: row.angleDeg
                maxAngle = maxAngle?.let { maxOf(it, row.angleDeg) } ?: row.angleDeg
            }
            if (firstMillis == null) firstMillis = row.receivedAtMillis
            lastMillis = row.receivedAtMillis
            previous = row
        }

        val durationSeconds: Double?
            get() {
                val first = firstMillis ?: return null
                val last = lastMillis ?: return null
                return (last - first) / 1000.0
            }
    }

    private data class Sample(val index: Long, val angle: Double, val torque: Double, val seconds: Double, val phase: WorkingCondition.Phase?)

    /** 分桶抽样：每桶留最小/最大扭矩点（按原顺序），段边界处插入 null 作为断点。 */
    private class ChartSampler(total: Long) {
        private val bucketSize = max(1L, ceil(total / (MAX_CHART_ROWS / 2.0)).toLong())
        private val output = ArrayList<Sample?>()
        private var bucket = ArrayList<Sample>()
        private var previous: ProcessRow? = null

        fun accept(row: ProcessRow, seconds: Double, phase: WorkingCondition.Phase?) {
            val prev = previous
            if (prev != null && isNewRun(prev, row)) {
                flush()
                if (output.isNotEmpty() && output.last() != null) output += null
            }
            bucket += Sample(row.index, row.angleDeg, row.torqueNm, seconds, phase)
            if (bucket.size >= bucketSize) flush()
            previous = row
        }

        private fun flush() {
            if (bucket.isEmpty()) return
            if (bucket.size <= 2) {
                output.addAll(bucket)
            } else {
                val minSample = bucket.minBy { it.torque }
                val maxSample = bucket.maxBy { it.torque }
                if (minSample === maxSample) output += minSample
                else output.addAll(listOf(minSample, maxSample).sortedBy { it.index })
            }
            bucket = ArrayList()
        }

        fun finish(): List<Sample?> {
            flush()
            while (output.isNotEmpty() && output.last() == null) output.removeAt(output.lastIndex)
            return output
        }
    }

    /** 概要页：记录 · 过程数据统计 · 转折候选 · 工况 · 扳手结果帧（扭矩四行供柱状图引用）· 说明 · 导出信息。 */
    private class SummaryLayout(
        private val summary: WorkbookSummary,
        private val totalPoints: Long,
        private val stats: ProcessStats,
        private val runs: List<RunSummary>,
        private val lastResult: DecodedResult?,
        private val resultFrames: Int,
    ) {
        /** 结果帧扭矩四行（字段1、字段2、下限、上限）的首行行号，柱状图引用。 */
        var torqueFirstRow: Int = 0
            private set

        /** 结果帧扭矩四行的名称：按功能码与 M1 / M2 给出协议字段名。 */
        val torqueLabels: List<String> = listOf(
            "${lastResult?.torque1Name ?: "目标扭矩"} N·m",
            "${lastResult?.torque2Name ?: "实际扭矩"} N·m",
            "${lastResult?.torqueLimitName ?: "扭矩"}下限 N·m",
            "${lastResult?.torqueLimitName ?: "扭矩"}上限 N·m",
        )

        fun write(out: Writer) {
            val sheet = SheetWriter(out, listOf(26.0, 34.0), freezeHeader = false)
            sheet.begin()
            sheet.row(listOf(text("WiFi 扳手 · 测量记录 R${summary.recordId}")), Styles.TITLE)
            fun section(title: String) {
                sheet.row(emptyList())
                sheet.row(listOf(text(title), Cell.Blank), Styles.HEADER)
            }
            fun line(label: String, value: Cell) = sheet.row(listOf(text(label), value))

            section("记录")
            line("记录编号", text("R${summary.recordId}"))
            line("板号", text(summary.slabNo.ifBlank { "--" }))
            line("测点", text(summary.pointNo.ifBlank { "--" }))
            line("扳手 SN", text(summary.deviceSn.takeIf { it.isNotBlank() && it != "未知设备" } ?: "--"))
            line("开始时间", dateTime(summary.startTimeMillis))
            line("结束时间", summary.endTimeMillis?.let { dateTime(it) } ?: text("未结束"))
            line("记录点数（含结果帧）", num(totalPoints))
            line("运行段数", num(stats.runs))
            line("首末点间隔 s", num(stats.durationSeconds))

            section("过程数据统计")
            line("峰值扭矩 N·m", num(stats.peakTorque))
            line("峰值处转角 °", num(stats.angleAtPeak))
            line("最小转角 °", num(stats.minAngle))
            line("最大转角 °", num(stats.maxAngle))

            section("转折候选（平衡点研究参考，未验证）")
            val withCandidate = runs.count { it.result.candidate != null }
            line("有候选的运行段", text(if (runs.isEmpty()) "--" else "$withCandidate / ${runs.size}"))
            val lastRun = runs.lastOrNull()
            val lastCandidate = lastRun?.result?.candidate
            line("末段候选转角 °", num(lastCandidate?.angleDeg))
            line("末段候选扭矩 N·m", num(lastCandidate?.torqueNm))
            line("末段前 / 后斜率 N·m/°", text(lastCandidate?.let { pair(it.slopeBefore, it.slopeAfter) } ?: ""))
            line("末段判定", text(lastRun?.let { run -> if (lastCandidate != null) "转折候选（未验证）" else (run.result.outcome as LoadingAnalysis.NotFound).reason } ?: "--"))

            section("多段候选重复性（只描述，不是平衡点结论）")
            val consistency = CandidateConsistency.summarize(runs.map { it.result })
            fun stats(s: CandidateConsistency.Stats?) = s?.let { "${Xml.number(round3(it.mean))} / ${Xml.number(round3(it.min))} / ${Xml.number(round3(it.max))}" } ?: "--"
            line("候选扭矩 均值 / 最小 / 最大 N·m", text(stats(consistency.torque)))
            line("候选扭矩 标准差 N·m", num(consistency.torque?.sd?.let(::round3)))
            line("候选扭矩 变异系数 %", num(consistency.torque?.cv?.let { round3(it * 100.0) }))
            line("候选相对加载起点 均值 / 最小 / 最大 °", text(stats(consistency.relativeAngle)))
            line("候选相对加载起点 标准差 °", num(consistency.relativeAngle?.sd?.let(::round3)))
            line("重复性说明", text(consistency.note))

            section("工况（开发默认阈值，待真机标定）")
            val conditions = runs.mapNotNull { it.condition }
            fun total(kind: WorkingCondition.EventKind) = conditions.sumOf { it.count(kind) }
            line("全程未受力的运行段", text(if (runs.isEmpty()) "--" else "${conditions.count { it.noLoad }} / ${runs.size}"))
            line("有疑似打滑的运行段", text(if (runs.isEmpty()) "--" else "${conditions.count { it.count(WorkingCondition.EventKind.SLIP) > 0 }} / ${runs.size}"))
            line("疑似打滑 / 转角回退 / 数据间断（次）", text(if (runs.isEmpty()) "--" else
                "${total(WorkingCondition.EventKind.SLIP)} / ${total(WorkingCondition.EventKind.BACKTRACK)} / ${total(WorkingCondition.EventKind.GAP)}"))
            val lastCondition = lastRun?.condition
            line("末段工况次序", text(lastCondition?.sequence() ?: "--"))
            line("末段空程转角 °", num(lastCondition?.angleIn(WorkingCondition.Phase.FREE_TRAVEL)?.let(::round3)))
            line("末段加载转角 °", num(lastCondition?.angleIn(WorkingCondition.Phase.LOADING)?.let(::round3)))
            line("末段停转保压时长 s", num(lastCondition?.secondsIn(WorkingCondition.Phase.STALL)?.let(::round3)))
            line("末段判读", text(lastCondition?.verdict ?: "--"))

            section("扳手结果帧（最后一帧；全部见「结果帧」）")
            line("结果", text(summary.resultStatus))
            line("结果帧个数", num(resultFrames))
            line("功能码 / 状态码", text(lastResult?.let { r -> "${"0x%02X".format(r.functionCode)} / ${r.statusCode?.let { "0x%02X".format(it) } ?: "--"}" } ?: ""))
            line("判定", text(lastResult?.kindLabel ?: ""))
            line("参数模式", text(lastResult?.modeText ?: ""))
            line("员工号 / 螺栓号", text(lastResult?.let { "${it.employeeId.ifBlank { "--" }} / ${it.boltNo}" } ?: ""))
            line("扳手时钟", dateTime(lastResult?.clockMillis))
            torqueFirstRow = sheet.rowsWritten + 1
            line(torqueLabels[0], num(summary.targetTorqueNm))
            line(torqueLabels[1], num(summary.actualTorqueNm))
            line(torqueLabels[2], num(summary.torqueLowerNm))
            line(torqueLabels[3], num(summary.torqueUpperNm))
            line("${lastResult?.angle1Name ?: "目标转角"} °", num(summary.targetAngleDeg))
            line("${lastResult?.angle2Name ?: "实际转角"} °", num(summary.actualAngleDeg))
            line("${lastResult?.angleLimitName ?: "转角"}下限 / 上限 °", text(pair(summary.angleLowerDeg, summary.angleUpperDeg)))

            section("说明")
            NOTES.forEach { sheet.row(listOf(text(it)), Styles.NOTE) }

            section("导出")
            line("导出时间", dateTime(summary.exportedAtMillis))
            line("导出软件", text("WiFi 扳手 ${summary.appVersion}"))
            sheet.end()
        }

        private fun pair(a: Double?, b: Double?): String =
            if (a == null && b == null) "" else "${a?.let { Xml.number(round3(it)) } ?: "--"} / ${b?.let { Xml.number(round3(it)) } ?: "--"}"

        private fun round3(value: Double): Double = Math.rint(value * 1_000.0) / 1_000.0

        companion object {
            val NOTES = listOf(
                "「过程数据」为本记录全部数据点（0x12 过程点与 0x15/0x55 结果帧），未截断；另带运行段、帧号、帧内序号、段内时间/转角增量、相对有效加载起点转角、帧间转速、接收间隔、工况、事件等派生列。",
                "接收时间为手机接收时间（毫秒），同一帧的多个点共用；0x12 帧约每秒一帧，帧内各点没有单独时间。",
                "图表、统计、转折候选、工况只用 0x12 过程帧；0x15/0x55 结果帧逐字段见「结果帧」，收发的其余帧见「通信记录」。",
                "转折候选为两段直线拟合的斜率变化位置，未经实测验证，不是平衡点结论；逐段汇总见「运行段」，计算过程见「拟合明细」「候选扫描」。",
                "工况：受力门槛 = 段峰值 × ${(WorkingCondition.ENGAGE_FRACTION * 100).toInt()}%（且 ≥ ${WorkingCondition.ENGAGE_FLOOR_NM.toInt()} N·m），与有效加载起点同口径；" +
                    "整段峰值 < ${WorkingCondition.NO_LOAD_PEAK_NM.toInt()} N·m 记「空转」；前后各 ${WorkingCondition.WINDOW} 点转角净变化 ≥ ${WorkingCondition.MOVE_DEG.toInt()}° 算在转；阈值为开发默认值，待真机标定。",
                "0x15 的扭矩/角度字段在 M1、M2 下含义不同（M2 的 28-29 是预设扭矩实际值，不是目标扭矩），本表按结果帧模式写字段名。",
                "运行段切分：螺栓号变化、转角回落超过 ${TorqueAngleWindow.RESET_DROP_DEG.toInt()}°、转角从 ≥ ${TorqueAngleWindow.RESTART_FROM_DEG.toInt()}° 回到 ≤ ${TorqueAngleWindow.RESTART_AT_DEG.toInt()}°、或接收间隔超过 ${TorqueAngleWindow.GAP_MILLIS / 1000} s。",
                "未标定：不含顶升力等正式力值。",
            )
        }
    }

    /** 结果帧的统一视图（0x15 / 0x55），字段名按协议与模式给出。 */
    private class DecodedResult(
        val functionCode: Int,
        val statusCode: Int?,
        val statusLabel: String,
        val kind: ResultKind,
        val modeText: String,
        val employeeId: String,
        val boltNo: Int,
        val clockMillis: Long?,
        val torque1Name: String,
        val torque1: Double?,
        val torque2Name: String,
        val torque2: Double?,
        val angle1Name: String,
        val angle1: Double?,
        val angle2Name: String,
        val angle2: Double?,
        val torqueLimitName: String,
        val torqueLower: Double?,
        val torqueUpper: Double?,
        val angleLimitName: String,
        val angleLower: Double?,
        val angleUpper: Double?,
    ) {
        val kindLabel: String get() = when (kind) {
            ResultKind.OK -> "OK"
            ResultKind.NG -> "NG"
            ResultKind.UNKNOWN -> "未知"
        }
    }

    private class ResultRow(val row: ProcessRow, val run: Int?, val decoded: DecodedResult)

    /** 由入库的原始 HEX 重新按协议解出结果帧；不是 0x15 / 0x55 或解不出时返回 null。 */
    private fun decodeResult(rawHex: String): DecodedResult? {
        val packet = runCatching { WrenchPacketParser().append(HexUtils.hexToBytes(rawHex)).firstOrNull() }.getOrNull() ?: return null
        return when (packet.functionCode) {
            WrenchPacket.FUNC_RESULT -> runCatching { ResultFrameDecoder.decode(packet) }.getOrNull()?.let { r ->
                DecodedResult(
                    functionCode = packet.functionCode, statusCode = r.status.code, statusLabel = r.status.label, kind = r.status.kind,
                    modeText = r.modeText, employeeId = r.employeeId, boltNo = r.boltNo,
                    clockMillis = wrenchClock(r.year, r.month, r.day, r.hour, r.minute, r.second),
                    torque1Name = r.torqueField1Name, torque1 = r.targetTorqueNm, torque2Name = r.torqueField2Name, torque2 = r.actualTorqueNm,
                    angle1Name = r.angleField1Name, angle1 = r.targetAngleDeg, angle2Name = r.angleField2Name, angle2 = r.actualAngleDeg,
                    torqueLimitName = r.torqueLimitName, torqueLower = r.torqueLowerNm, torqueUpper = r.torqueUpperNm,
                    angleLimitName = r.angleLimitName, angleLower = r.angleLowerDeg, angleUpper = r.angleUpperDeg,
                )
            }
            WrenchPacket.FUNC_PULSE_RESULT -> runCatching { PulseResultFrameDecoder.decode(packet) }.getOrNull()?.let { r ->
                DecodedResult(
                    functionCode = packet.functionCode, statusCode = r.status.code, statusLabel = r.status.label, kind = r.status.kind,
                    modeText = r.modeText, employeeId = r.employeeId, boltNo = r.boltNo,
                    clockMillis = wrenchClock(r.year, r.month, r.day, r.hour, r.minute, r.second),
                    torque1Name = "目标扭矩（÷10）", torque1 = r.targetTorqueNm, torque2Name = "实际扭矩（÷10）", torque2 = r.actualTorqueNm,
                    angle1Name = "", angle1 = null, angle2Name = "实际角度", angle2 = r.actualAngleDeg,
                    torqueLimitName = "扭矩", torqueLower = null, torqueUpper = null,
                    angleLimitName = "角度", angleLower = null, angleUpper = null,
                )
            }
            else -> null
        }
    }

    /** 结果帧自带的扳手时钟（按本地时区）；字段不合法时为 null。 */
    private fun wrenchClock(year: Int?, month: Int?, day: Int?, hour: Int?, minute: Int?, second: Int?): Long? {
        if (year == null || month == null || day == null || hour == null || minute == null || second == null) return null
        return runCatching { LocalDateTime.of(year, month, day, hour, minute, second).atZone(ZONE).toInstant().toEpochMilli() }.getOrNull()
    }

    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 第二遍写「过程数据」时按段内下标取该点的事件（段内下标单调递增，游标只前进）。 */
    private class EventCursor(private val runs: List<RunSummary>) {
        private var run = -1
        private var k = 0

        fun at(runIndex: Int, point: Int): String? {
            if (runIndex != run) {
                run = runIndex
                k = 0
            }
            val events = runs.getOrNull(runIndex - 1)?.condition?.events ?: return null
            while (k < events.size && events[k].index < point) k += 1
            var j = k
            var text: String? = null
            while (j < events.size && events[j].index == point) {
                val e = events[j]
                val value = Xml.number(Math.rint(e.value * 10.0) / 10.0)
                val item = when (e.kind) {
                    WorkingCondition.EventKind.SLIP -> "疑似打滑（扭矩降 $value%）"
                    WorkingCondition.EventKind.GAP -> "数据间断 $value s"
                    WorkingCondition.EventKind.BACKTRACK -> "转角回退 $value°"
                }
                text = if (text == null) item else "$text；$item"
                j += 1
            }
            return text
        }
    }

    private val PHASE_NOTES = listOf(
        "工况按前后各 ${WorkingCondition.WINDOW} 点的转角 / 扭矩变化判断：未受力且在转 = 空程（本段尚未受力）/ 脱开空转（受力之后）；受力且在转 = 加载（扭矩明显下降则为转动降扭）；" +
            "受力且不转 = 停转保压（扭矩明显下降则为卸载）；转角在减 = 回退；整段峰值很小 = 空转；不受力也不转 = 静止。",
        "短于 ${WorkingCondition.MIN_PHASE_POINTS} 点的阶段并入相邻阶段；「空程」结束处即有效加载起点（与转折候选同一口径）。",
        "时长按手机接收时间计（同一帧各点同一时间），分辨率约 1 帧（≈1 s）。反转松螺栓时出现「转动降扭」「脱开空转」属正常现象。",
        "阈值为开发默认值，未经真机标定；工况只描述现象，不是故障结论。",
    )

    private val EVENT_NOTES = listOf(
        "疑似打滑：相邻两点扭矩下降 ≥ ${(WorkingCondition.SLIP_DROP_FRACTION * 100).toInt()}%，且转角仍在增加（螺栓还在转）；卸载时扭矩降、转角停住，不算打滑。连续下降只记第一次。与扳手 0x15 状态 0x13「NG-打滑」互为参照。",
        "数据间断：段内相邻两点接收间隔 > ${WorkingCondition.GAP_MILLIS / 1000.0} s（0x12 约每秒一帧，漏了一帧以上）；超过 ${TorqueAngleWindow.GAP_MILLIS / 1000} s 则切成新运行段。",
        "转角回退：相邻两点转角下降 ≥ ${WorkingCondition.BACKTRACK_EVENT_DEG.toInt()}°；回落超过 ${TorqueAngleWindow.RESET_DROP_DEG.toInt()}° 视为新运行段。",
    )

    private val RESULT_NOTES = listOf(
        "0x15 字段按协议原位解出：扭矩、角度为整数（不缩放）；0x55 扭矩 ÷10。状态码 0x00/0x01/0x02 为 OK，0x10–0x16 为 NG（扭矩/角度越限、打滑、重复拧紧）。",
        "M1：字段1 = 目标扭矩、字段2 = 目标扭矩实际值、角度字段1 固定 0；M2：字段1 = 预设扭矩实际值、字段2 = 最终扭矩实际值、角度字段1 = 目标角度设置值。",
        "扳手时钟为结果帧自带时间（可由 0x21/0x22 对时）；「扳手时钟−手机接收」可看两边时钟差。所属运行段为结果帧到达前最后一个 0x12 运行段。",
    )

    private val COMM_NOTES = listOf(
        "记录本次连接期间收发的非 0x12 帧：应答 0x06、状态 0x05（错误码、方向位）、对时 0x21/0x22、电量 0x04、SN 0x26、结果 0x15/0x55 与确认 0x17，以及手机发出的 0x11/0x01/0x10/0x25。",
        "0x12 过程帧逐点见「过程数据」；通信层每分钟自动发的 0x33 保活不逐条记录。",
    )

    private data class Rel(val id: String, val type: String, val target: String)

    private const val REL_SHEET = "${Xml.NS_REL}/worksheet"
    private const val REL_STYLES = "${Xml.NS_REL}/styles"
    private const val REL_DRAWING = "${Xml.NS_REL}/drawing"
    private const val REL_CHART = "${Xml.NS_REL}/chart"

    private fun relationships(rels: List<Rel>): String = buildString {
        append(Xml.HEADER).append("<Relationships xmlns=\"${Xml.NS_PKG_REL}\">")
        rels.forEach { append("<Relationship Id=\"${it.id}\" Type=\"${it.type}\" Target=\"${it.target}\"/>") }
        append("</Relationships>")
    }

    private fun rootRels(): String = buildString {
        append(Xml.HEADER).append("<Relationships xmlns=\"${Xml.NS_PKG_REL}\">")
        append("<Relationship Id=\"rId1\" Type=\"${Xml.NS_REL}/officeDocument\" Target=\"xl/workbook.xml\"/>")
        append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>")
        append("<Relationship Id=\"rId3\" Type=\"${Xml.NS_REL}/extended-properties\" Target=\"docProps/app.xml\"/>")
        append("</Relationships>")
    }

    /** 工作表部件数（sheet1..sheet11）。 */
    private const val SHEET_PARTS = 11

    private fun workbook(): String = buildString {
        append(Xml.HEADER)
        append("<workbook xmlns=\"${Xml.NS_MAIN}\" xmlns:r=\"${Xml.NS_REL}\">")
        append("<bookViews><workbookView activeTab=\"1\"/></bookViews><sheets>")
        // 标签顺序：概要 · 图表 · 运行段 · 工况分段 · 工况事件 · 结果帧 · 拟合明细 · 候选扫描 · 通信记录 · 过程数据 · 图表数据。
        listOf(
            SHEET_SUMMARY to 1, SHEET_CHARTS to 2, SHEET_RUNS to 5, SHEET_PHASES to 8, SHEET_EVENTS to 9, SHEET_RESULTS to 10,
            SHEET_FIT to 6, SHEET_SCAN to 7, SHEET_COMM to 11, SHEET_DATA to 3, SHEET_CHART_DATA to 4,
        ).forEachIndexed { index, (name, part) ->
            append("<sheet name=\"${Xml.escape(name)}\" sheetId=\"${index + 1}\" r:id=\"rId$part\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun contentTypes(chartCount: Int): String = buildString {
        append(Xml.HEADER)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (sheet in 1..SHEET_PARTS) {
            append("<Override PartName=\"/xl/worksheets/sheet$sheet.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        if (chartCount > 0) {
            append("<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>")
            for (chart in 1..chartCount) {
                append("<Override PartName=\"/xl/charts/chart$chart.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>")
            }
        }
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        append("</Types>")
    }

    private fun coreProps(summary: WorkbookSummary): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(summary.exportedAtMillis))
        return Xml.HEADER +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${Xml.escape("WiFi 扳手 R${summary.recordId}")}</dc:title>" +
            "<dc:creator>WiFi 扳手</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$iso</dcterms:created>" +
            "</cp:coreProperties>"
    }

    private fun appProps(): String = Xml.HEADER +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
        "<Application>WiFi 扳手</Application></Properties>"

    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
    }

    fun formatTime(millis: Long): String = timeFormat.get()!!.format(Date(millis))
}
