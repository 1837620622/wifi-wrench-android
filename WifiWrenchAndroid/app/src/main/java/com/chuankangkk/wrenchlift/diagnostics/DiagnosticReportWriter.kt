package com.chuankangkk.wrenchlift.diagnostics

import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断报告 ZIP：给厂商 / 开发排查用，不是测量结果。
 *
 * - `报告摘要.txt`：版本、机型、连接状态快照、收发帧计数（按功能码）、各类问题点次数、崩溃份数；
 * - `问题点.csv`：每个问题点的时间、类别、功能码、说明、原始 HEX（校验错 / 丢字节 / NAK / 发送失败 / 断线 / 未识别帧 / 解析失败 / 手动标记 / 崩溃）；
 * - `协议记录.csv`：每一帧收发（含 0x12 逐点）的时间、方向、功能码、说明、完整 HEX；
 * - `运行日志.txt`：内存窗口内全部记录按时间排列；
 * - `流水/`：落盘流水原文件（跨 App 重启，含内存窗口之外的旧记录）；
 * - `崩溃/`：每次崩溃一份，含异常栈和崩溃前最近 200 条记录。
 *
 * CSV 带 UTF-8 BOM，Excel 直接打开不乱码。
 */
object DiagnosticReportWriter {
    fun write(output: OutputStream, journal: DiagnosticJournal, header: String, stateSnapshot: String, generatedAtMillis: Long) {
        val entries = journal.entries()
        val problems = journal.problems()
        val counters = journal.counters.value
        ZipOutputStream(output.buffered(64 * 1024)).use { zip ->
            zip.text("报告摘要.txt", summary(header, stateSnapshot, counters, entries.size, journal.droppedFromMemory(), generatedAtMillis))
            zip.text("问题点.csv", csv(problems))
            zip.text("协议记录.csv", csv(entries.filter { it.kind == DiagnosticJournal.Kind.RX || it.kind == DiagnosticJournal.Kind.TX }))
            zip.text("运行日志.txt", entries.joinToString("\n", postfix = "\n") { DiagnosticJournal.formatLine(it) })
            journal.journalFiles().forEach { file ->
                zip.putNextEntry(ZipEntry("流水/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            journal.crashFiles().forEach { file ->
                zip.putNextEntry(ZipEntry("崩溃/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun fileName(generatedAtMillis: Long): String =
        "WiFi扳手诊断报告_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(java.util.Date(generatedAtMillis))}.zip"

    internal fun summary(
        header: String,
        stateSnapshot: String,
        counters: DiagnosticJournal.Counters,
        memoryEntries: Int,
        droppedFromMemory: Long,
        generatedAtMillis: Long,
    ): String = buildString {
        appendLine("WiFi 扳手 诊断报告（排查用，不含测量结论）")
        appendLine("生成时间：${DiagnosticJournal.timestamp(generatedAtMillis)}")
        appendLine("记录起点：${DiagnosticJournal.timestamp(counters.startedAtMillis)}（本次 App 启动或上次清空）")
        appendLine(header.trimEnd())
        appendLine()
        appendLine("—— 当前状态 ——")
        appendLine(stateSnapshot.trimEnd())
        appendLine()
        appendLine("—— 收发帧计数 ——")
        appendLine("收 ${counters.framesIn} 帧，发 ${counters.framesOut} 帧")
        appendLine("收（按功能码）：${byFunction(counters.byFunctionIn)}")
        appendLine("发（按功能码）：${byFunction(counters.byFunctionOut)}")
        appendLine("丢弃无法成帧字节：${counters.discardedBytes}")
        appendLine()
        appendLine("—— 问题点 ——")
        if (counters.problems.isEmpty()) {
            appendLine("无")
        } else {
            DiagnosticJournal.Problem.entries.forEach { kind ->
                counters.problems[kind]?.let { appendLine("${kind.label}：$it 次") }
            }
            appendLine("最近一次：${counters.lastProblem}")
        }
        appendLine("崩溃报告：${counters.crashes} 份（见「崩溃/」）")
        appendLine()
        appendLine("—— 文件说明 ——")
        appendLine("问题点.csv：每个问题点一行，含原始 HEX")
        appendLine("协议记录.csv：内存窗口内每一帧收发（含 0x12 逐点）")
        appendLine("运行日志.txt：内存窗口内全部记录，共 $memoryEntries 条" + if (droppedFromMemory > 0) "（更早 $droppedFromMemory 条只在「流水/」里）" else "")
        appendLine("流水/：落盘记录，跨 App 重启；journal.1.log 是轮转下来的旧记录")
    }

    private fun byFunction(map: Map<Int, Long>): String =
        if (map.isEmpty()) "无" else map.toSortedMap().entries.joinToString("，") { (code, count) -> "${DiagnosticJournal.functionText(code)}×$count" }

    internal fun csv(entries: List<DiagnosticJournal.Entry>): String = buildString {
        append(BOM)
        append("时间,类别,功能码,说明,原始HEX\r\n")
        entries.forEach { entry ->
            val category = entry.problem?.label ?: entry.kind.label
            append(listOf(DiagnosticJournal.timestamp(entry.timeMillis), category, DiagnosticJournal.functionText(entry.functionCode), entry.message, entry.rawHex)
                .joinToString(",") { escape(it) })
            append("\r\n")
        }
    }

    private const val BOM = '\uFEFF'

    private fun escape(value: String): String {
        // 防公式注入：以 = + - @ 开头的说明前加单引号。
        val safe = if (value.isNotEmpty() && value[0] in "=+-@") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
