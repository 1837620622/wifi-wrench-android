package com.chuankangkk.wrenchlift.measurement.analysis

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 同一记录里多段加载的**转折候选重复性**（docs/项目背景与开发要求.md §11.2「检查候选位置稳定性」、§11.3 重复试验）。
 *
 * 隔振器受力不均匀、摩擦和装配都会让单次曲线的转折位置漂移；一个候选只有在重复加载中反复出现在相近的扭矩 /
 * 相对转角处，才值得拿去做独立力值与分离验证。这里只做**描述统计**：
 * - 候选扭矩：均值、最小、最大、样本标准差、变异系数（标准差 / 均值）；
 * - 候选相对有效加载起点的转角（各段起转位置不同，绝对转角不可比）：均值、最小、最大、样本标准差。
 *
 * 不设合格阈值、不给结论：重复性好也只说明「候选稳定」，不等于平衡点已确认。
 */
object CandidateConsistency {
    /** 少于这么多段有候选时不算离散程度（两点的标准差没有意义）。 */
    const val MIN_CANDIDATES = 3

    data class Stats(val mean: Double, val min: Double, val max: Double, val sd: Double) {
        val range: Double get() = max - min
        /** 变异系数 sd / |mean|；均值接近 0 时为 null。 */
        val cv: Double? get() = if (abs(mean) > 1e-9) sd / abs(mean) else null
    }

    data class Summary(
        /** 参与统计的运行段数（含没有候选的）。 */
        val runs: Int,
        val candidates: Int,
        val torque: Stats?,
        val relativeAngle: Stats?,
    ) {
        /** 一句话描述，只列数字不下结论。 */
        val note: String
            get() = when {
                runs == 0 -> "无运行段"
                candidates == 0 -> "没有运行段给出候选"
                candidates < MIN_CANDIDATES -> "只有 $candidates 段有候选（少于 $MIN_CANDIDATES 段），重复性无从判断"
                else -> buildString {
                    append("$candidates 段候选：扭矩极差 ${fmt(torque!!.range)} N·m")
                    torque.cv?.let { append("（变异系数 ${fmt(it * 100.0)}%）") }
                    append("，相对加载起点转角极差 ${fmt(relativeAngle!!.range)}°；只描述重复性，未经实测验证，不是平衡点结论")
                }
            }
    }

    /** 候选相对有效加载起点的转角；没有候选或缺起点时为 null。 */
    fun relativeAngle(result: LoadingAnalysis.Result): Double? {
        val candidate = result.candidate ?: return null
        val start = result.loadStartDeg ?: return null
        return candidate.angleDeg - start
    }

    fun summarize(results: List<LoadingAnalysis.Result>): Summary {
        val withCandidate = results.filter { it.candidate != null && it.loadStartDeg != null }
        val enough = withCandidate.size >= MIN_CANDIDATES
        return Summary(
            runs = results.size,
            candidates = withCandidate.size,
            torque = if (enough) stats(withCandidate.map { it.candidate!!.torqueNm }) else null,
            relativeAngle = if (enough) stats(withCandidate.mapNotNull(::relativeAngle)) else null,
        )
    }

    internal fun stats(values: List<Double>): Stats {
        val mean = values.average()
        val variance = if (values.size > 1) values.sumOf { (it - mean) * (it - mean) } / (values.size - 1) else 0.0
        return Stats(mean, values.min(), values.max(), sqrt(variance))
    }

    private fun fmt(value: Double): String = "%.1f".format(Locale.ROOT, value)
}
