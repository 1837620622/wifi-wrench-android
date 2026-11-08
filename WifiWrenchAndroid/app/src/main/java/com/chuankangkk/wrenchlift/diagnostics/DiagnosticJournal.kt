package com.chuankangkk.wrenchlift.diagnostics

import com.chuankangkk.wrenchlift.protocol.WrenchPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 维护用诊断记录：每一帧收发（含 0x12 逐点）、运行日志、问题点、崩溃，一律带毫秒时间戳。
 *
 * - 内存里保留最近 [memoryLimit] 条，导出时连同落盘的流水一起打包；
 * - [directory] 不为空时逐条追加到 `journal.log`，超过 [rotateBytes] 轮转到 `journal.1.log`（只留一份旧的），
 *   这样 App 被杀、崩溃之后上一轮的记录仍在；
 * - 崩溃单独写 `crash-*.txt`，只留最近 [maxCrashFiles] 份。
 *
 * 只记录 App 本身看到的数据（帧、状态、手机型号），不读联系人、不上传；导出由用户手动触发。
 */
class DiagnosticJournal(
    private val directory: File?,
    private val memoryLimit: Int = 5_000,
    private val rotateBytes: Long = 2L * 1024 * 1024,
    private val maxCrashFiles: Int = 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val label: String) { INFO("日志"), RX("收"), TX("发"), PROBLEM("问题"), MARK("标记"), CRASH("崩溃") }

    /** 问题点类别；[label] 写进报告。 */
    enum class Problem(val label: String) {
        BAD_CHECKSUM("校验和错误"),
        DISCARDED_BYTES("丢弃无法成帧的字节"),
        NAK("扳手 NAK 拒绝"),
        SEND_FAILED("指令发送失败"),
        LINK_LOST("连接断开 / 读写异常"),
        UNKNOWN_FRAME("未识别功能码"),
        DECODE_FAILED("帧解析失败"),
        CONNECT_FAILED("探测 / 连接失败"),
        EXPORT_FAILED("导出失败"),
        USER_MARK("现场手动标记"),
        CRASH("崩溃"),
    }

    data class Entry(
        val timeMillis: Long,
        val kind: Kind,
        val message: String,
        val functionCode: Int? = null,
        val rawHex: String = "",
        val problem: Problem? = null,
    )

    data class Counters(
        val framesIn: Long = 0,
        val framesOut: Long = 0,
        val byFunctionIn: Map<Int, Long> = emptyMap(),
        val byFunctionOut: Map<Int, Long> = emptyMap(),
        val problems: Map<Problem, Long> = emptyMap(),
        val discardedBytes: Long = 0,
        val crashes: Int = 0,
        val lastProblem: String = "",
        val startedAtMillis: Long = 0,
    ) {
        val problemTotal: Long get() = problems.values.sum()
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val problemEntries = ArrayDeque<Entry>()
    private var droppedFromMemory = 0L
    private val _counters = MutableStateFlow(Counters(startedAtMillis = clock(), crashes = crashFiles().size))
    val counters: StateFlow<Counters> = _counters

    init {
        directory?.mkdirs()
    }

    fun info(message: String) = add(Entry(clock(), Kind.INFO, message))

    fun received(packet: WrenchPacket, summary: String) = frame(Kind.RX, packet, summary)

    fun sent(packet: WrenchPacket, summary: String) = frame(Kind.TX, packet, summary)

    fun problem(problem: Problem, message: String, rawHex: String = "", functionCode: Int? = null) {
        add(Entry(clock(), if (problem == Problem.USER_MARK) Kind.MARK else Kind.PROBLEM, message, functionCode, rawHex, problem))
    }

    /** 解析器丢掉的字节：计数，并只留前 [MAX_HEX_BYTES] 字节的 HEX，避免一大串垃圾刷满记录。 */
    fun discarded(bytes: ByteArray, badChecksum: Boolean) {
        if (bytes.isEmpty()) return
        if (!badChecksum) _counters.update { it.copy(discardedBytes = it.discardedBytes + bytes.size) }
        val hex = bytes.take(MAX_HEX_BYTES).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } +
            if (bytes.size > MAX_HEX_BYTES) " …（共 ${bytes.size} 字节）" else ""
        if (badChecksum) {
            problem(Problem.BAD_CHECKSUM, "帧头和长度对得上但校验和不对，已按协议丢弃", hex, bytes.getOrNull(3)?.toInt()?.and(0xFF))
        } else {
            problem(Problem.DISCARDED_BYTES, "${bytes.size} 字节不能组成合法帧，已丢弃", hex)
        }
    }

    private fun frame(kind: Kind, packet: WrenchPacket, summary: String) {
        _counters.update {
            if (kind == Kind.RX) {
                it.copy(framesIn = it.framesIn + 1, byFunctionIn = it.byFunctionIn.increment(packet.functionCode))
            } else {
                it.copy(framesOut = it.framesOut + 1, byFunctionOut = it.byFunctionOut.increment(packet.functionCode))
            }
        }
        add(Entry(clock(), kind, summary, packet.functionCode, packet.rawHex()))
    }

    private fun add(entry: Entry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > memoryLimit) {
                entries.removeFirst()
                droppedFromMemory += 1
            }
            if (entry.problem != null) {
                problemEntries.addLast(entry)
                while (problemEntries.size > memoryLimit) problemEntries.removeFirst()
            }
            appendToDisk(formatLine(entry))
        }
        if (entry.problem != null) {
            _counters.update {
                it.copy(problems = it.problems.increment(entry.problem), lastProblem = "${entry.problem.label}：${entry.message}")
            }
        }
    }

    fun entries(): List<Entry> = synchronized(lock) { entries.toList() }

    fun problems(): List<Entry> = synchronized(lock) { problemEntries.toList() }

    /** 内存窗口之外被挤掉的条数（磁盘流水里仍有）。 */
    fun droppedFromMemory(): Long = synchronized(lock) { droppedFromMemory }

    /** 落盘流水，旧的在前；没有目录时为空。 */
    fun journalFiles(): List<File> {
        val dir = directory ?: return emptyList()
        return listOf(File(dir, OLD_JOURNAL), File(dir, JOURNAL)).filter { it.isFile }
    }

    fun crashFiles(): List<File> =
        directory?.listFiles { file -> file.name.startsWith(CRASH_PREFIX) && file.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            .orEmpty()

    /** 清空内存、流水和崩溃文件（导出后由用户手动触发）。 */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            problemEntries.clear()
            droppedFromMemory = 0
            journalFiles().forEach { it.delete() }
            crashFiles().forEach { it.delete() }
        }
        _counters.value = Counters(startedAtMillis = clock())
    }

    /**
     * 同步写崩溃报告：线程、异常栈、[context]（版本、机型等）、崩溃前最近 [CRASH_TAIL] 条记录。
     * 在未捕获异常处理器里调用，进程马上要退出，所以不走协程、不抛异常。
     */
    fun recordCrash(thread: Thread, error: Throwable, context: String): File? = runCatching {
        val now = clock()
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val tail = synchronized(lock) { entries.toList().takeLast(CRASH_TAIL) }
        val text = buildString {
            appendLine("WiFi 扳手 崩溃报告")
            appendLine("时间：${timestamp(now)}")
            appendLine("线程：${thread.name}")
            appendLine("异常：${error.javaClass.name}: ${error.message.orEmpty()}")
            appendLine(context.trimEnd())
            appendLine()
            appendLine("—— 异常栈 ——")
            appendLine(stack.trimEnd())
            appendLine()
            appendLine("—— 崩溃前最近 ${tail.size} 条记录 ——")
            tail.forEach { appendLine(formatLine(it)) }
        }
        add(Entry(now, Kind.CRASH, "${error.javaClass.simpleName}: ${error.message.orEmpty()}", problem = Problem.CRASH))
        _counters.update { it.copy(crashes = it.crashes + 1) }
        val dir = directory ?: return@runCatching null
        val file = File(dir, "$CRASH_PREFIX${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(now))}.txt")
        file.writeText(text)
        crashFiles().dropLast(maxCrashFiles).forEach { it.delete() }
        file
    }.getOrNull()

    private fun appendToDisk(line: String) {
        val dir = directory ?: return
        runCatching {
            val journal = File(dir, JOURNAL)
            if (journal.length() > rotateBytes) {
                val old = File(dir, OLD_JOURNAL)
                old.delete()
                journal.renameTo(old)
            }
            File(dir, JOURNAL).appendText(line + "\n")
        }
    }

    private fun <K> Map<K, Long>.increment(key: K): Map<K, Long> = this + (key to ((this[key] ?: 0L) + 1))

    private inline fun MutableStateFlow<Counters>.update(transform: (Counters) -> Counters) {
        while (true) {
            val current = value
            if (compareAndSet(current, transform(current))) return
        }
    }

    companion object {
        const val JOURNAL = "journal.log"
        const val OLD_JOURNAL = "journal.1.log"
        const val CRASH_PREFIX = "crash-"
        const val CRASH_TAIL = 200
        const val MAX_HEX_BYTES = 64

        fun timestamp(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(millis))

        fun functionText(code: Int?): String = code?.let { "0x%02X".format(Locale.ROOT, it) }.orEmpty()

        /** 流水一行：时间 | 类别 | 功能码 | 说明 | HEX（制表符分隔，便于再导入表格）。 */
        fun formatLine(entry: Entry): String = listOf(
            timestamp(entry.timeMillis),
            entry.problem?.let { "${entry.kind.label}·${it.label}" } ?: entry.kind.label,
            functionText(entry.functionCode),
            entry.message.replace('\t', ' ').replace('\n', ' '),
            entry.rawHex,
        ).joinToString("\t")
    }
}
