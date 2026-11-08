package com.chuankangkk.wrenchlift.measurement.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CandidateConsistencyTest {
    /**
     * 模拟一次顶升：空程 [freePlay]° 扭矩为 0；接触后原支承与工具一起受力，斜率陡（10 N·m/°）；
     * 原支承在接触后 [separateAt]° 处分离，此后只剩弹簧，斜率变缓（3 N·m/°）。每度 4 个点，扭矩取整、带 ±[noise] 抖动。
     */
    private fun jack(freePlay: Int, separateAt: Double, noise: Double, seed: Int, travel: Int = 80): LoadingAnalysis.Result {
        val random = Random(seed)
        val angles = ArrayList<Double>()
        val torques = ArrayList<Double>()
        for (i in 0..(freePlay + travel) * 4) {
            val exact = i / 4.0
            val engaged = exact - freePlay
            val ideal = when {
                engaged <= 0.0 -> 0.0
                engaged < separateAt -> 10.0 * engaged
                else -> 10.0 * separateAt + 3.0 * (engaged - separateAt)
            }
            angles += Math.floor(exact)
            torques += Math.rint(ideal + if (ideal > 0.0 && noise > 0.0) random.nextDouble(-noise, noise) else 0.0)
        }
        return LoadingAnalysis.analyze(angles, torques)
    }

    @Test fun relativeAngleRemovesDifferentFreePlay() {
        // 隔振器受力不均：每次空程不同、分离点略有漂移。绝对转角差到 15°，相对加载起点的转角应该接近。
        val results = listOf(
            jack(freePlay = 5, separateAt = 30.0, noise = 3.0, seed = 1),
            jack(freePlay = 12, separateAt = 31.0, noise = 3.0, seed = 2),
            jack(freePlay = 20, separateAt = 29.0, noise = 3.0, seed = 3),
            jack(freePlay = 8, separateAt = 30.0, noise = 3.0, seed = 4),
        )
        results.forEach { assertNotNull("每次都应给出候选：${it.outcome}", it.candidate) }
        val absolute = results.map { it.candidate!!.angleDeg }
        assertTrue("绝对转角相差大：$absolute", absolute.max() - absolute.min() > 10.0)

        val summary = CandidateConsistency.summarize(results)
        assertEquals(4, summary.runs)
        assertEquals(4, summary.candidates)
        val relative = summary.relativeAngle!!
        assertTrue("相对转角极差 ${relative.range}", relative.range < 5.0)
        assertEquals(29.0, relative.mean, 3.0)
        val torque = summary.torque!!
        assertEquals(300.0, torque.mean, 25.0)
        assertTrue("变异系数 ${torque.cv}", torque.cv!! < 0.1)
        assertTrue(summary.note.contains("4 段候选"))
        assertTrue(summary.note.contains("不是平衡点结论"))
    }

    @Test fun fewerThanThreeCandidatesGivesNoSpread() {
        val one = jack(freePlay = 5, separateAt = 30.0, noise = 0.0, seed = 1)
        val straight = LoadingAnalysis.analyze(List(200) { (it / 4).toDouble() }, List(200) { 5.0 * (it / 4) })
        assertNull(straight.candidate)
        val summary = CandidateConsistency.summarize(listOf(one, one, straight))
        assertEquals(3, summary.runs)
        assertEquals(2, summary.candidates)
        assertNull(summary.torque)
        assertNull(summary.relativeAngle)
        assertTrue(summary.note.startsWith("只有 2 段有候选"))
        assertEquals("无运行段", CandidateConsistency.summarize(emptyList()).note)
        assertEquals("没有运行段给出候选", CandidateConsistency.summarize(listOf(straight)).note)
    }

    @Test fun statsUseSampleStandardDeviation() {
        val stats = CandidateConsistency.stats(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))
        assertEquals(5.0, stats.mean, 1e-12)
        assertEquals(7.0, stats.range, 1e-12)
        assertEquals(Math.sqrt(32.0 / 7.0), stats.sd, 1e-12)
        assertEquals(Math.sqrt(32.0 / 7.0) / 5.0, stats.cv!!, 1e-12)
        assertNull(CandidateConsistency.stats(listOf(0.0, 0.0, 0.0)).cv)
    }
}
