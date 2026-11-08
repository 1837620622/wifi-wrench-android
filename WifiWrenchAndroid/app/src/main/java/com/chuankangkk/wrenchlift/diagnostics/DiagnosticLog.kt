package com.chuankangkk.wrenchlift.diagnostics

import android.content.Context
import android.os.Build
import com.chuankangkk.wrenchlift.BuildConfig
import java.io.File

/**
 * 全 App 共用的诊断记录入口。[install] 之前（单元测试里）只记内存，不落盘、不接管崩溃。
 * 正式版和调试版都启用：现场出问题时由维护人员在「设置 → 诊断报告」导出。
 */
object DiagnosticLog {
    @Volatile
    var journal: DiagnosticJournal = DiagnosticJournal(directory = null)
        private set

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            journal = DiagnosticJournal(File(context.applicationContext.filesDir, "diagnostics"))
            installed = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                journal.recordCrash(thread, error, deviceContext())
                previous?.uncaughtException(thread, error)
            }
            journal.info("App 启动 ${BuildConfig.VERSION_NAME}（${BuildConfig.BUILD_TYPE}）· ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        }
    }

    /** 版本、机型、系统；写进崩溃报告和诊断报告头。 */
    fun deviceContext(): String = buildString {
        appendLine("App：${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}，${BuildConfig.BUILD_TYPE}）")
        appendLine("手机：${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）")
        append("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
    }
}
