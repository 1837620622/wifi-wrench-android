package com.chuankangkk.wrenchlift.diagnostics

import com.chuankangkk.wrenchlift.diagnostics.DiagnosticJournal.Problem
import com.chuankangkk.wrenchlift.protocol.WrenchPacket
import com.chuankangkk.wrenchlift.protocol.WrenchPacketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DiagnosticJournalTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private fun journal(dir: java.io.File? = folder.root, memoryLimit: Int = 5_000, rotateBytes: Long = 2L * 1024 * 1024) =
        DiagnosticJournal(dir, memoryLimit = memoryLimit, rotateBytes = rotateBytes, maxCrashFiles = 3, clock = { now++ })

    @Test fun countsFramesByFunctionAndProblemsByKind() {
        val j = journal()
        j.sent(WrenchPacketBuilder.enableRemoteControl(), "开启远程")
        j.received(WrenchPacketBuilder.build(WrenchPacket.FUNC_ACK, byteArrayOf(0x01)), "NAK")
        j.received(WrenchPacketBuilder.build(WrenchPacket.FUNC_TORQUE_ANGLE, ByteArray(16)), "过程点")
        j.received(WrenchPacketBuilder.build(WrenchPacket.FUNC_TORQUE_ANGLE, ByteArray(16)), "过程点")
        j.problem(Problem.NAK, "扳手拒绝：开启远程")
        j.discarded(ByteArray(100) { 0x11 }, badChecksum = false)
        j.discarded(byteArrayOf(0xC5.toByte(), 0xC5.toByte(), 0x01, 0x12, 0, 0, 0, 0x00), badChecksum = true)

        val c = j.counters.value
        assertEquals(3, c.framesIn)
        assertEquals(1, c.framesOut)
        assertEquals(2L, c.byFunctionIn[WrenchPacket.FUNC_TORQUE_ANGLE])
        assertEquals(1L, c.byFunctionOut[WrenchPacket.FUNC_REMOTE_CONTROL])
        assertEquals(mapOf(Problem.NAK to 1L, Problem.DISCARDED_BYTES to 1L, Problem.BAD_CHECKSUM to 1L), c.problems)
        assertEquals(100, c.discardedBytes)
        assertEquals(3, j.problems().size)
        // 丢弃的长串只留前 64 字节 HEX，并注明总长。
        val garbage = j.problems().first { it.problem == Problem.DISCARDED_BYTES }
        assertTrue(garbage.rawHex, garbage.rawHex.endsWith("…（共 100 字节）"))
        assertEquals(0x12, j.problems().first { it.problem == Problem.BAD_CHECKSUM }.functionCode)
    }

    @Test fun journalSurvivesRestartAndRotates() {
        val first = journal(rotateBytes = 2_000)
        repeat(60) { first.info("第 $it 条记录 ——————————") }
        val files = first.journalFiles().map { it.name }
        assertEquals(listOf(DiagnosticJournal.OLD_JOURNAL, DiagnosticJournal.JOURNAL), files)
        // 新实例（模拟 App 重启）读得到磁盘流水，内存是空的。
        val second = journal(rotateBytes = 2_000)
        assertTrue(second.entries().isEmpty())
        assertTrue(second.journalFiles().last().readText().contains("第 59 条记录"))
        assertTrue(second.journalFiles().sumOf { it.length() } < 6_000)
    }

    @Test fun memoryWindowDropsOldestButCountsThem() {
        val j = journal(dir = null, memoryLimit = 10)
        repeat(25) { j.info("x$it") }
        assertEquals(10, j.entries().size)
        assertEquals("x15", j.entries().first().message)
        assertEquals(15, j.droppedFromMemory())
        assertTrue(j.journalFiles().isEmpty())
    }

    @Test fun crashReportHasStackContextAndRecentTail() {
        val j = journal()
        j.sent(WrenchPacketBuilder.stop(), "停止扳手")
        val file = j.recordCrash(Thread.currentThread(), IllegalStateException("模拟崩溃"), "App：测试版\n手机：测试机")
        assertNotNull(file)
        val text = file!!.readText()
        assertTrue(text.contains("java.lang.IllegalStateException: 模拟崩溃"))
        assertTrue(text.contains("at com.chuankangkk.wrenchlift.diagnostics.DiagnosticJournalTest"))
        assertTrue(text.contains("手机：测试机"))
        assertTrue(text.contains("停止扳手"))
        assertEquals(1, j.counters.value.crashes)
        assertEquals(1L, j.counters.value.problems[Problem.CRASH])
        // 只留最近 3 份。
        repeat(4) { j.recordCrash(Thread.currentThread(), RuntimeException("再崩 $it"), "") }
        assertEquals(3, j.crashFiles().size)
        // 重启后崩溃份数从磁盘读回。
        assertEquals(3, journal().counters.value.crashes)
    }

    @Test fun clearRemovesFilesAndCounters() {
        val j = journal()
        j.problem(Problem.USER_MARK, "现场标记")
        j.recordCrash(Thread.currentThread(), RuntimeException("x"), "")
        j.clear()
        assertTrue(j.entries().isEmpty())
        assertTrue(j.crashFiles().isEmpty())
        assertTrue(j.journalFiles().isEmpty())
        assertEquals(0L, j.counters.value.problemTotal)
    }

    @Test fun reportZipContainsEveryPart() {
        val j = journal()
        j.info("App 启动")
        j.sent(WrenchPacketBuilder.enableRemoteControl(), "开启远程")
        j.received(WrenchPacketBuilder.build(WrenchPacket.FUNC_TORQUE_ANGLE, ByteArray(16)), "过程点 1 个")
        j.problem(Problem.SEND_FAILED, "=SUM(A1), \"引号\"", "C5 C5")
        j.recordCrash(Thread.currentThread(), RuntimeException("boom"), "")

        val out = ByteArrayOutputStream()
        DiagnosticReportWriter.write(out, j, "App：测试", "连接：已连", now)
        val files = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(
            setOf("报告摘要.txt", "问题点.csv", "协议记录.csv", "运行日志.txt", "流水/journal.log"),
            files.keys.filterNot { it.startsWith("崩溃/") }.toSet(),
        )
        assertEquals(1, files.keys.count { it.startsWith("崩溃/crash-") })
        val summary = files.getValue("报告摘要.txt")
        assertTrue(summary, summary.contains("收 1 帧，发 1 帧"))
        assertTrue(summary, summary.contains("收（按功能码）：0x12×1"))
        assertTrue(summary, summary.contains("指令发送失败：1 次"))
        assertTrue(summary, summary.contains("崩溃报告：1 份"))
        assertTrue(summary, summary.contains("连接：已连"))
        val protocol = files.getValue("协议记录.csv")
        assertTrue(protocol.startsWith("﻿时间,类别,功能码,说明,原始HEX"))
        assertEquals(3, protocol.trimEnd().lines().size)
        assertTrue(protocol.contains(",发,0x11,开启远程,"))
        val problems = files.getValue("问题点.csv")
        // 逗号 / 引号转义，公式开头加单引号。
        assertTrue(problems, problems.contains("\"'=SUM(A1), \"\"引号\"\"\""))
        assertTrue(problems.contains("崩溃"))
        assertFalse(files.getValue("运行日志.txt").isBlank())
        assertTrue(DiagnosticReportWriter.fileName(now).matches(Regex("WiFi扳手诊断报告_\\d{8}_\\d{6}\\.zip")))
    }
}
