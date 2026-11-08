package com.chuankangkk.wrenchlift

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoSimulationRuntimeSourceTest {
    @Test
    fun runtimeSourceDoesNotContainLocalSimulationEntries() {
        val appDir = locateAppDir()
        val runtimeDirs = listOf("src/main", "src/debug").map { File(appDir, it) }
        val runtimeFiles = runtimeDirs
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { file -> file.isFile && file.extension in sourceExtensions }
                    .toList()
            }

        val violations = runtimeFiles.flatMap { file ->
            val relativePath = file.relativeTo(appDir).path
            forbiddenPatterns.mapNotNull { pattern ->
                val match = pattern.find(file.readText())
                if (match == null) null else "$relativePath -> ${match.value}"
            }
        }

        assertTrue(
            "运行源码不允许加入本地模拟数据入口：\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    /** 给别人用的正式界面、导出、提示里不出现「模拟 / 仿真 / 假数据 / 演示」字样（注释不算）。 */
    @Test
    fun userVisibleTextHasNoSimulationWording() {
        val main = File(locateAppDir(), "src/main")
        val literal = Regex("""\"(?:[^\"\\\n]|\\.)*\"""")
        val words = Regex("模拟|仿真|假数据|演示数据|示例数据")
        val violations = main.walkTopDown()
            .filter { it.isFile && it.extension in sourceExtensions }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    val code = line.substringBefore("//")
                    literal.findAll(code).map { it.value }.filter { words.containsMatchIn(it) }
                        .map { "${file.name}:${index + 1} -> $it" }.toList()
                }
            }
            .toList()
        assertTrue("正式界面文字不允许出现模拟字样：\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun locateAppDir(): File {
        val startDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = generateSequence(startDir) { it.parentFile }
            .flatMap { dir -> sequenceOf(dir, File(dir, "app")) }
            .toList()
        return candidates.firstOrNull { File(it, "src/main").exists() }
            ?: error("无法定位 Android app 模块目录")
    }

    private companion object {
        val sourceExtensions = setOf("kt", "java", "xml")
        val forbiddenPatterns = listOf(
            Regex("""\bFake[A-Za-z0-9_]*"""),
            Regex("""\bMock[A-Za-z0-9_]*"""),
            Regex("""\bstartSimulation\b"""),
            Regex("""\bstopSimulation\b"""),
            Regex("""\bsimulation[A-Za-z0-9_]*\b""", RegexOption.IGNORE_CASE),
            Regex("""\bRandom\b"""),
            Regex("""\bsin\s*\("""),
            Regex("""\bcos\s*\("""),
            Regex("""本地造点"""),
            Regex("""模拟采集"""),
            Regex("""模拟曲线"""),
            Regex("""假数据"""),
        )
    }
}
