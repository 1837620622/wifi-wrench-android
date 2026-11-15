package com.chuankangkk.wrenchlift.connection

/**
 * 设备身份核对：把第一台回话的扳手 SN 记成本机信任的编号，之后回话的编号与它不一致就报警。
 *
 * 厂家 C 协议没有认证 / 加密，同网段任何设备都能按同一帧格式回话，所以这里只做「发现」不做「阻止」：
 * 结论交给调用方记进诊断、在界面上提示，但不拦连接，也**不**把新编号自动写成信任值——否则冒充者
 * 只要先回一帧，就把本机记住的真编号顶掉，核对就失效了。换扳手由维护人员显式「记住这台扳手」。
 */
object DeviceIdentity {
    /** SharedPreferences 键：本机信任的扳手编号。 */
    const val PREF_PINNED_SN = "pinned_device_sn"

    sealed interface Verdict {
        /** 本机还没有信任编号：本次编号可以记为信任值（首次使用）。 */
        data class FirstSeen(val sn: String) : Verdict

        /** 与信任编号一致。 */
        data class Matches(val sn: String) : Verdict

        /** 与信任编号不一致：可能换了扳手，也可能有人冒充。 */
        data class Differs(val pinned: String, val reported: String) : Verdict

        /** 这一帧没带有效编号（空数据），无法判断，保持原状态。 */
        data object Unknown : Verdict
    }

    fun evaluate(pinned: String?, reported: String): Verdict {
        val reportedSn = normalize(reported) ?: return Verdict.Unknown
        val pinnedSn = normalize(pinned) ?: return Verdict.FirstSeen(reportedSn)
        return if (pinnedSn == reportedSn) Verdict.Matches(reportedSn) else Verdict.Differs(pinnedSn, reportedSn)
    }

    /** 编号按大写比较（0x26 回的是十六进制串）；空串、占位说法都不算有效编号。 */
    fun normalize(sn: String?): String? =
        sn?.trim()
            ?.uppercase(java.util.Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it != "未知设备" && it != "--" }
}
