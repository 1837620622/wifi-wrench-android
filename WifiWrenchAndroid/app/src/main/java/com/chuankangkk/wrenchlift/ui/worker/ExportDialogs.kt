package com.chuankangkk.wrenchlift.ui.worker

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chuankangkk.wrenchlift.measurement.ExportOutcome
import com.chuankangkk.wrenchlift.measurement.MeasurementState

/** §9.3：正在导出 Excel… / 导出完成（含图表）/ 导出失败，请重试或检查存储空间。 */
@Composable
fun ExportDialogs(
    state: MeasurementState,
    actions: WorkerActions,
    onRetry: () -> Unit = actions.export,
    progressText: String = "正在导出 Excel…",
) {
    val p = palette
    val context = LocalContext.current
    if (state.exportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = p.panel,
            modifier = Modifier.testTag("export_progress"),
            title = null,
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = p.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(progressText, style = WrenchType.bodyStrong.copy(fontSize = 17.sp), color = p.textPrimary)
                }
            },
            confirmButton = {},
        )
        return
    }
    val outcome = state.lastExport ?: return
    val report = outcome.mimeType == ExportOutcome.ZIP_MIME
    if (outcome.success) {
        WrenchDialog(
            title = if (report) "诊断报告已导出" else "导出完成（含图表）",
            message = if (report) {
                "ZIP 内含崩溃报告、逐帧协议记录、问题点和运行日志，可「分享」发给厂商或开发排查。"
            } else {
                outcome.message.removePrefix("导出完成（含图表）").removePrefix("，").ifBlank { "Excel 已保存，打开即可看到扭矩–转角等原生图表。" }
            },
            confirmText = "完成",
            onConfirm = actions.clearExport,
            onDismiss = actions.clearExport,
            dismissText = null,
            extra = outcome.shareUri?.let { uri ->
                {
                    ControlButton("打开", null, onClick = {
                        runCatching {
                            val view = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.parse(uri), outcome.mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(Intent.createChooser(view, if (report) "打开诊断报告" else "用表格软件打开").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { Toast.makeText(context, if (report) "没有能打开 ZIP 的应用，可用「分享」发到电脑" else "没有能打开 Excel 的应用，可用「分享」发到电脑", Toast.LENGTH_LONG).show() }
                    }, height = WrenchDimens.touch, modifier = Modifier.testTag("export_open"))
                    ControlButton("分享", WrenchIcon.Share, onClick = {
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType(outcome.mimeType)
                                .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "分享导出文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { Toast.makeText(context, "没有可用的分享方式", Toast.LENGTH_SHORT).show() }
                    }, height = WrenchDimens.touch, modifier = Modifier.testTag("export_share"))
                }
            },
            body = {
                Text("保存位置：${outcome.displayPath}", style = WrenchType.captionStyle.copy(fontSize = 13.sp), color = p.textSecondary)
            },
            modifier = Modifier.testTag("export_done"),
        )
    } else {
        WrenchDialog(
            title = "导出失败",
            message = outcome.message.ifBlank { "导出失败，请重试或检查存储空间" },
            confirmText = "重试",
            onConfirm = {
                actions.clearExport()
                onRetry()
            },
            onDismiss = actions.clearExport,
            dismissText = "关闭",
            modifier = Modifier.testTag("export_failed"),
        )
    }
}
