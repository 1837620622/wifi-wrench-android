package com.chuankangkk.wrenchlift.ui.worker

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chuankangkk.wrenchlift.measurement.LinkPhase
import com.chuankangkk.wrenchlift.measurement.MeasurementState
import com.chuankangkk.wrenchlift.measurement.MotionCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 正式工人页（设计规范 §10 验收清单的可自动化部分）：横屏 640×360dp 手机（CI 模拟器同尺寸）。
 * 设置环境变量 WIFI_WRENCH_EVIDENCE_DIR 时额外输出截图，供人工视觉验收。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w640dp-h360dp-land-xhdpi")
class WorkerScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        state: MeasurementState,
        actions: WorkerActions = WorkerActions(),
        screen: WorkerScreen = WorkerScreen.Workbench,
        debugContent: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ) {
        compose.setContent {
            WifiWrenchWorkerApp(state, actions, debugContent = debugContent, startScreen = screen, immersive = false)
        }
        compose.waitForIdle()
    }

    @Test fun workbenchHasSixControlsAndNoPortOrIpOnMainScreen() {
        show(WorkerSamples.live(remote = false))
        listOf("back", "top_bar", "readouts", "torque_chart", "chart_tabs", "controls", "remote_toggle", "forward", "reverse", "stop", "params", "export", "stored_count")
            .forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertExists() }
        // 顶栏分格：SN · 电量 · 通信 · 远程 · 已记录 · 返回（板号/测点在最左格）。
        compose.onNodeWithText("2026110501").assertIsDisplayed()
        compose.onNodeWithText("82%").assertIsDisplayed()
        compose.onNodeWithText("已连上", substring = true).assertIsDisplayed()
        compose.onNodeWithText("未启用").assertIsDisplayed()
        compose.onNodeWithText("2505 点").assertIsDisplayed()
        compose.onNodeWithText("扭矩").assertIsDisplayed()
        compose.onNodeWithText("转角").assertIsDisplayed()
        compose.onNodeWithText("峰值", substring = true).assertIsDisplayed()
        compose.onNodeWithText("扭矩–转角").assertIsDisplayed()
        // NET-02：主流程不出现端口 / IP / 协议码。
        listOf("7888", "192.168", "端口", "TCP", "0x").forEach { word ->
            assertEquals("主屏不应出现 $word", 0, compose.onAllNodesWithText(word, substring = true).fetchSemanticsNodes().size)
        }
        // 工业终端不写说明性废话、不署名：板号/测点、显示窗口说明、待分析、作者/供应都不在主屏。
        compose.onNodeWithTag("place_cell").assertIsDisplayed()
        listOf("已确认平衡", "模拟", "完整数据", "见导出", "显示窗口", "待分析", "免费", "传康").forEach { word ->
            assertEquals("主屏不应出现 $word", 0, compose.onAllNodesWithText(word, substring = true).fetchSemanticsNodes().size)
        }
        capture("workbench-live-remote-off")
    }

    @Test fun motionDisabledUntilRemoteEnabledAndStopAlwaysAvailable() {
        var remoteOn = 0
        var stops = 0
        show(WorkerSamples.live(remote = false), WorkerActions(enableRemote = { remoteOn++ }, stop = { stops++ }))
        compose.onNodeWithTag("forward").assertIsNotEnabled()
        compose.onNodeWithTag("reverse").assertIsNotEnabled()
        compose.onNodeWithTag("stop").assertIsEnabled().performClick()
        // 640dp 宽时控制列窄，用短句；宽屏用完整句（见 remoteOffHintUsesFullSentenceOnWideScreens）。
        compose.onNodeWithText("先开远程").assertIsDisplayed()
        compose.onNodeWithTag("remote_toggle").performClick()
        compose.runOnIdle {
            assertEquals(1, remoteOn)
            assertEquals(1, stops)
        }
    }

    @Test fun forwardAndReverseRequireSecondConfirmation() {
        var forwards = 0
        var reverses = 0
        show(WorkerSamples.live(remote = true), WorkerActions(forward = { forwards++ }, reverse = { reverses++ }))
        compose.onNodeWithTag("forward").assertIsEnabled().performClick()
        compose.onNodeWithText("确认以正转方向驱动扳手？请确认现场安全。").assertIsDisplayed()
        compose.runOnIdle { assertEquals("确认前不得发送", 0, forwards) }
        captureDialog("confirm-forward")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, forwards) }
        compose.onNodeWithTag("forward").performClick()
        compose.onAllNodesWithText("正转").fetchSemanticsNodes().also { assertTrue(it.size >= 2) }
        compose.onNodeWithTag("confirm_motion").assertExists()
        compose.onNode(isDialog()).assertExists()
        compose.onAllNodesWithText("正转")[compose.onAllNodesWithText("正转").fetchSemanticsNodes().size - 1].performClick()
        compose.runOnIdle { assertEquals(1, forwards) }
        compose.onNodeWithTag("reverse").performClick()
        compose.onNodeWithText("确认以反转方向驱动扳手？请确认现场安全。").assertIsDisplayed()
        compose.onAllNodesWithText("反转")[compose.onAllNodesWithText("反转").fetchSemanticsNodes().size - 1].performClick()
        compose.runOnIdle { assertEquals(1, reverses) }
    }

    @Test fun backWarnsThatEndingSessionDoesNotStopWrenchAndOffersOneKeyStop() {
        var ended: Boolean? = null
        show(WorkerSamples.live(remote = true, motion = MotionCommand.FORWARD), WorkerActions(endSession = { stopFirst -> ended = stopFirst }))
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("结束仅断开本机会话与采集，不会自动停止扳手。是否仍要结束？").assertIsDisplayed()
        captureDialog("confirm-leave")
        compose.onNodeWithText("先停止扳手再结束").performClick()
        compose.runOnIdle { assertEquals(true, ended) }
        compose.onNodeWithTag("records").assertExists()
    }

    @Test fun parametersOpenAsSideSheetAndWriteNeedsConfirmation() {
        var written = 0
        show(WorkerSamples.live(remote = true), WorkerActions(writeParameters = { written++ }))
        compose.onNodeWithTag("params").performClick()
        compose.onNodeWithTag("parameter_sheet").assertIsDisplayed()
        compose.onNodeWithText("M1 定扭矩").assertExists()
        capture("workbench-parameter-sheet")
        compose.onNodeWithTag("param_save").performClick()
        compose.onNodeWithText("确认将参数写入扳手？写入后影响后续动作。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, written) }
        compose.onNodeWithText("写入").performClick()
        compose.runOnIdle { assertEquals(1, written) }
    }

    @Test fun compactLargeFontStillShowsAllControls() {
        RuntimeEnvironment.setFontScale(1.3f)
        val state = WorkerSamples.live(remote = true, motion = MotionCommand.FORWARD).copy(lastResult = null)
        show(state)
        listOf("forward", "reverse", "stop", "params", "export", "readout_torque", "readout_angle").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        capture("workbench-live-remote-on-font1.3")
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun guideAndSplashHaveNoPortField() {
        show(WorkerSamples.idle(LinkPhase.NO_WIFI), screen = WorkerScreen.Guide)
        compose.onNodeWithText("去连接 Wi‑Fi").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("端口", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("192.168", substring = true).fetchSemanticsNodes().size)
        capture("guide-no-wifi")
    }

    @Test fun splashShowsExactlyTheThreeChineseLines() {
        compose.setContent { WrenchWorkerTheme(dark = false) { SplashScreen(animate = false) } }
        // 定稿启动页（docs/design-renders/03-splash.png）文案已烘焙在位图内；无叠字 Text，无障碍走 contentDescription。
        compose.onNodeWithContentDescription("WiFi 扳手 · 隔振器顶升调频采集 · 传康Kk 免费供应").assertIsDisplayed()
        compose.onNodeWithTag("splash").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("WiFi 扳手", substring = false).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("端口", substring = true).fetchSemanticsNodes().size)
        capture("splash")
    }

    @Test fun recordsPageStartsMeasurementDirectlyWithoutAnyForm() {
        var started = 0
        show(WorkerSamples.idle(LinkPhase.IDLE).copy(recentSessions = WorkerSamples.sessions()), WorkerActions(startMeasurement = { started++ }), WorkerScreen.Records)
        compose.onNodeWithTag("wrench_card").assertIsDisplayed()
        compose.onNodeWithText("R20 · ", substring = true).assertIsDisplayed()
        listOf("板号", "测点", "FZB").forEach { word ->
            assertEquals("记录页不应出现 $word", 0, compose.onAllNodesWithText(word, substring = true).fetchSemanticsNodes().size)
        }
        capture("records")
        compose.onNodeWithTag("start_measurement").performClick()
        compose.runOnIdle { assertEquals(1, started) }
        compose.onNodeWithTag("readouts").assertExists()
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun chartSwitchesBetweenTorqueAngleAndSlopeViews() {
        show(WorkerSamples.liveTwoRuns())
        compose.onNodeWithTag("chart_tabs").assertIsDisplayed()
        compose.onNodeWithContentDescription("扭矩–转角实时曲线", substring = true).assertExists()
        capture("chart-torque-angle-two-runs")
        compose.onNodeWithTag("chart_mode_slope_angle").performClick()
        compose.onNodeWithContentDescription("斜率–转角实时曲线", substring = true).assertExists()
        capture("chart-slope-angle-two-runs")
        // 「扭矩–序号 / 转角–序号」已移除：主屏只有两种关系，都以转角为横轴。
        assertEquals(listOf(ChartMode.TORQUE_ANGLE, ChartMode.SLOPE_ANGLE), ChartMode.entries.toList())
        ChartMode.entries.forEach { compose.onNodeWithTag("chart_mode_${it.name.lowercase()}").assertIsDisplayed() }
        assertEquals(0, compose.onAllNodesWithText("序号", substring = true).fetchSemanticsNodes().size)
    }

    /** 斜率明显变化的加载曲线：图上标出转折候选，写明「未验证」，全屏不出现「平衡点」字样。 */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun slopeChangeIsMarkedAsUnverifiedCandidateNotBalancePoint() {
        show(WorkerSamples.live(remote = true, motion = MotionCommand.FORWARD))
        val chart = compose.onNodeWithContentDescription("转折候选", substring = true)
        chart.assertExists()
        val description = chart.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.contains("未验证"))
        val angle = Regex("候选 ([\\d.]+)°").find(description)!!.groupValues[1].toDouble()
        assertTrue("样例曲线在 220° 处变缓，候选 $angle°", angle in 205.0..235.0)
        assertEquals(0, compose.onAllNodesWithText("平衡点", substring = true).fetchSemanticsNodes().size)
        capture("chart-candidate-torque-angle")
        compose.onNodeWithTag("chart_mode_slope_angle").performClick()
        compose.onNodeWithContentDescription("斜率–转角实时曲线，转折候选", substring = true).assertExists()
        capture("chart-candidate-slope-angle")
    }

    /** 段内超过 2.5 秒没收到数据：曲线在那里画虚线（截图核对），且数据点不丢。 */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun dataGapInsideARunIsDrawnDashed() {
        show(WorkerSamples.liveWithGap())
        compose.onNodeWithContentDescription("扭矩–转角实时曲线", substring = true).assertExists()
        capture("chart-data-gap")
    }

    @Test fun controlFeedbackShowsInControlColumnStatusLine() {
        show(WorkerSamples.live(remote = true).copy(controlMessage = "参数已写入"))
        compose.onNodeWithText("参数已写入").assertIsDisplayed()
    }

    @Test fun portsOnlyAppearInAdvancedDiagnostics() {
        show(WorkerSamples.live(remote = false), screen = WorkerScreen.Settings)
        assertEquals(0, compose.onAllNodesWithText("端口", substring = true).fetchSemanticsNodes().size)
        // 作者只在「关于」里写一次。
        compose.onNodeWithTag("about_author", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("作者：传康Kk（GitHub 1837620622）").assertExists()
        assertEquals(0, compose.onAllNodesWithText("免费供应", substring = true).fetchSemanticsNodes().size)
        capture("settings")
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithText("192.168.4.1:7888（先试）").assertExists()
        // 诊断报告按钮在右侧面板顶部，一进来就看得到；正式版也有。
        listOf("diag_export", "diag_mark").forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
        compose.onNodeWithTag("diag_clear", useUnmergedTree = true).assertExists()
        capture("diagnostics")
    }

    /** 640×360（最矮的目标横屏）+ 调试版入口：设置两块面板的每一项都在屏内，不藏半截。 */
    @Test fun settingsFitOneScreenEvenWithDebugEntry() {
        show(WorkerSamples.live(remote = false), screen = WorkerScreen.Settings, debugContent = {})
        listOf("wifi_name", "model_TORQUE", "model_PULSE_RCH", "csv_toggle", "dark_toggle", "open_diagnostics", "open_debug", "about_author")
            .forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
        capture("settings-debug-build")
    }

    @Test fun darkThemeWorkbench() {
        show(WorkerSamples.live(remote = true).copy(darkTheme = true))
        capture("workbench-dark")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletWorkbenchScalesReadoutsAndKeepsZones() {
        show(WorkerSamples.live(remote = true, motion = MotionCommand.FORWARD).copy(batteryPercent = 15))
        compose.onNodeWithText("远程控制").assertExists()
        compose.onNodeWithText("导出 Excel").assertExists()
        capture("workbench-tablet")
    }

    @Test
    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    fun midPhoneWorkbench() {
        show(WorkerSamples.live(remote = false))
        capture("workbench-853x480")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun ruggedPhone20by9Workbench() {
        show(WorkerSamples.live(remote = true, motion = MotionCommand.FORWARD).copy(lastResult = WorkerSamples.result()))
        listOf("forward", "reverse", "stop", "params", "export", "readout_torque", "readout_angle", "back").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        capture("rugged-914x411-remote-on")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun ruggedPhoneHdPlusWorkbench() {
        show(WorkerSamples.live(remote = false))
        listOf("forward", "reverse", "stop", "params", "export").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        capture("rugged-800x360-remote-off")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun lightThemeOptionStillAvailable() {
        show(WorkerSamples.live(remote = true).copy(darkTheme = false))
        capture("rugged-914x411-light")
    }

    @Test fun wrenchErrorCodeIsShownNextToControls() {
        show(WorkerSamples.live(remote = true).copy(deviceErrorCode = 18))
        compose.onNodeWithText("报错 18").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun wrenchErrorCodeUsesFullSentenceOnWideScreens() {
        show(WorkerSamples.live(remote = true).copy(deviceErrorCode = 18))
        compose.onNodeWithText("扳手报错（代码 18）").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun remoteOffHintUsesFullSentenceOnWideScreens() {
        show(WorkerSamples.live(remote = false))
        compose.onNodeWithText("先开远程，才能转动").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun pulseWrenchParametersUse0x50Fields() {
        var written: com.chuankangkk.wrenchlift.measurement.PulseDraft? = null
        show(
            WorkerSamples.live(remote = true).copy(wrenchModel = com.chuankangkk.wrenchlift.measurement.WrenchModel.PULSE_RC283F),
            WorkerActions(writePulseParameters = { written = it }),
        )
        compose.onNodeWithTag("params").performClick()
        compose.onNodeWithText("定点硬连接").assertExists()
        compose.onNodeWithText("K1").assertExists()
        capture("rugged-914x411-pulse-params")
        compose.onNodeWithTag("param_save").performClick()
        compose.onNodeWithText("写入").performClick()
        compose.runOnIdle { assertEquals(0x00, written?.mode) }
    }

    @Test fun emptyWorkbenchWhileProbing() {
        show(WorkerSamples.idle(LinkPhase.PROBING))
        assertTrue(compose.onAllNodesWithText("正在连接扳手…").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("export").assertIsNotEnabled()
        capture("workbench-probing")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        save(name, compose.activity.window.decorView)
    }

    /** Compose 对话框是独立窗口：取最新的 Dialog 窗口画出来。 */
    private fun captureDialog(name: String) {
        compose.waitForIdle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() ?: return
        save(name, dialog.window!!.decorView, background = compose.activity.window.decorView)
    }

    private fun save(name: String, view: android.view.View, background: android.view.View? = null) {
        val dir = System.getenv("WIFI_WRENCH_EVIDENCE_DIR") ?: return
        val base = background ?: view
        val bitmap = Bitmap.createBitmap(base.width.coerceAtLeast(1), base.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        base.draw(canvas)
        if (background != null) {
            canvas.drawColor(0x66000000)
            val left = (background.width - view.width) / 2f
            val top = (background.height - view.height) / 2f
            canvas.translate(left, top)
            view.draw(canvas)
        }
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
