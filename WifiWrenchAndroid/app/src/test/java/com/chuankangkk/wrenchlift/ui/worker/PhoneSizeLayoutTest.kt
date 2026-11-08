package com.chuankangkk.wrenchlift.ui.worker

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.chuankangkk.wrenchlift.measurement.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回归（第六轮）：常见手机横屏尺寸 × 两档字号。工作台、连接引导、参数侧滑的主要控件必须：
 * 1. 完整落在屏幕内（左右上下都不出界）；
 * 2. 控制键之间、顶栏胶囊之间互不重叠；
 * 3. 控制键 / 读数不被所属面板裁掉。
 *
 * 尺寸取扣掉系统栏后的可用区域：
 * 533×320（4.x 寸 480×800 hdpi）、640×360（720p）、731×411（1080p 16:9）、
 * 800×360（720×1600 20:9）、891×411（1080×2400 扣导航栏）、960×540（小平板 / 折叠屏外屏横放）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhoneSizeLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val controlTags = listOf("forward", "reverse", "stop", "params", "export", "remote_toggle")
    private val statusTags = listOf("link_status", "remote_status")

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun Rect.inside(outer: Rect) =
        left >= outer.left - 1f && right <= outer.right + 1f && top >= outer.top - 1f && bottom <= outer.bottom + 1f

    private fun Rect.overlaps(other: Rect) =
        left < other.right - 1f && other.left < right - 1f && top < other.bottom - 1f && other.top < bottom - 1f

    /** 设了 WIFI_WRENCH_EVIDENCE_DIR 时存截图，供人工看字有没有被挤（几何断言看不出字形裁切）。 */
    private fun snapshot(name: String) {
        val dir = System.getenv("WIFI_WRENCH_EVIDENCE_DIR") ?: return
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File(dir).mkdirs()
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sizeName(): String {
        val c = RuntimeEnvironment.getApplication().resources.configuration
        return "${c.screenWidthDp}x${c.screenHeightDp}-f${c.fontScale}"
    }

    private fun withFont(scale: Float, block: () -> Unit) {
        RuntimeEnvironment.setFontScale(scale)
        try { block() } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun workbench(scale: Float) = withFont(scale) {
        compose.setContent { WifiWrenchWorkerApp(WorkerSamples.live(remote = true), WorkerActions(), null, WorkerScreen.Workbench, immersive = false) }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val problems = mutableListOf<String>()
        val all = controlTags + listOf("torque_chart", "readouts", "readout_torque", "readout_angle", "back", "top_bar")
        all.forEach { tag -> if (!bounds(tag).inside(root)) problems += "$tag 出屏 ${bounds(tag)}" }
        if (!bounds("back").inside(bounds("top_bar"))) problems += "返回 超出顶栏 ${bounds("back")} / ${bounds("top_bar")}"
        val controls = bounds("controls")
        controlTags.forEach { tag -> if (!bounds(tag).inside(controls)) problems += "$tag 被控制面板裁切" }
        val readouts = bounds("readouts")
        listOf("readout_torque", "readout_angle").forEach { tag -> if (!bounds(tag).inside(readouts)) problems += "$tag 被读数面板裁切" }
        controlTags.forEachIndexed { i, a ->
            controlTags.drop(i + 1).forEach { b -> if (bounds(a).overlaps(bounds(b))) problems += "$a 与 $b 重叠" }
        }
        val shownStatus = statusTags.filter(::exists)
        shownStatus.forEachIndexed { i, a ->
            if (!bounds(a).inside(root)) problems += "$a 出屏"
            shownStatus.drop(i + 1).forEach { b -> if (bounds(a).overlaps(bounds(b))) problems += "$a 与 $b 重叠" }
        }
        if (bounds("torque_chart").overlaps(controls)) problems += "曲线与控制面板重叠"
        snapshot("phone-workbench-${sizeName()}")
        assertEquals("工作台布局问题：", emptyList<String>(), problems)
    }

    private fun guide(scale: Float) = withFont(scale) {
        compose.setContent { WifiWrenchWorkerApp(WorkerSamples.idle(LinkPhase.NO_WIFI), WorkerActions(), null, WorkerScreen.Guide, immersive = false) }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val problems = mutableListOf<String>()
        listOf("open_wifi", "retry_connect", "guide_info").forEach { tag -> if (!bounds(tag).inside(root)) problems += "$tag 出屏 ${bounds(tag)}" }
        if (bounds("open_wifi").overlaps(bounds("retry_connect"))) problems += "两个按钮重叠"
        if (bounds("guide_info").bottom > bounds("open_wifi").top + 1f) problems += "说明区压到按钮"
        snapshot("phone-guide-${sizeName()}")
        assertEquals("连接引导布局问题：", emptyList<String>(), problems)
    }

    private fun sheet(scale: Float) = withFont(scale) {
        compose.setContent { WifiWrenchWorkerApp(WorkerSamples.live(remote = true), WorkerActions(), null, WorkerScreen.Workbench, immersive = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("params").performClick()
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val sheet = bounds("parameter_sheet")
        val problems = mutableListOf<String>()
        if (!sheet.inside(root)) problems += "侧滑出屏 $sheet"
        listOf("param_target_torque", "param_torque_lower", "param_torque_upper").forEach { tag ->
            val box = bounds(tag)
            if (box.left < sheet.left - 1f || box.right > sheet.right + 1f) problems += "$tag 左右出侧滑"
        }
        listOf("param_save", "param_cancel").filter(::exists).forEach { tag ->
            if (!bounds(tag).inside(root)) problems += "$tag 出屏"
        }
        snapshot("phone-sheet-${sizeName()}")
        assertEquals("参数侧滑布局问题：", emptyList<String>(), problems)
    }

    @Test @Config(qualifiers = "w533dp-h320dp-land-hdpi") fun workbench533x320() = workbench(1f)
    @Test @Config(qualifiers = "w533dp-h320dp-land-hdpi") fun workbench533x320LargeFont() = workbench(1.3f)
    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi") fun workbench640x360() = workbench(1f)
    @Test @Config(qualifiers = "w731dp-h411dp-land-xxhdpi") fun workbench731x411LargeFont() = workbench(1.3f)
    @Test @Config(qualifiers = "w800dp-h360dp-land-xhdpi") fun workbench800x360() = workbench(1f)
    @Test @Config(qualifiers = "w800dp-h360dp-land-xhdpi") fun workbench800x360LargeFont() = workbench(1.3f)
    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi") fun workbench891x411() = workbench(1f)
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi") fun workbench960x540() = workbench(1f)

    @Test @Config(qualifiers = "w533dp-h320dp-land-hdpi") fun guide533x320LargeFont() = guide(1.3f)
    @Test @Config(qualifiers = "w800dp-h360dp-land-xhdpi") fun guide800x360() = guide(1f)

    @Test @Config(qualifiers = "w533dp-h320dp-land-hdpi") fun sheet533x320LargeFont() = sheet(1.3f)
    @Test @Config(qualifiers = "w800dp-h360dp-land-xhdpi") fun sheet800x360() = sheet(1f)
}
