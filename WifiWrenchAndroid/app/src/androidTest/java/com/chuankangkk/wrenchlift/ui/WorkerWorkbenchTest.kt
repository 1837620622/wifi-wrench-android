package com.chuankangkk.wrenchlift.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.chuankangkk.wrenchlift.LandscapeTestHostActivity
import com.chuankangkk.wrenchlift.connection.ConnectionState
import com.chuankangkk.wrenchlift.measurement.LinkPhase
import com.chuankangkk.wrenchlift.measurement.MeasuredPoint
import com.chuankangkk.wrenchlift.measurement.MeasurementSession
import com.chuankangkk.wrenchlift.measurement.MeasurementState
import com.chuankangkk.wrenchlift.measurement.WarningLevel
import com.chuankangkk.wrenchlift.protocol.TorqueAnglePoint
import com.chuankangkk.wrenchlift.ui.worker.WifiWrenchWorkerApp
import com.chuankangkk.wrenchlift.ui.worker.WorkerActions
import com.chuankangkk.wrenchlift.ui.worker.WorkerScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 真机/模拟器上的正式工人页：单屏六键、远程门闩、二次确认、主屏无端口。截图作为视觉证据上传。 */
class WorkerWorkbenchTest {
    /** 宿主固定横屏（调试版专用），避免测试中途旋转重建导致「No compose hierarchies found」。 */
    @get:Rule val compose = createAndroidComposeRule<LandscapeTestHostActivity>()

    private fun live(remote: Boolean): MeasurementState {
        val points = List(300) { i ->
            val angle = i.toDouble()
            val torque = if (angle < 40) 4 + angle * 0.1 else 8 + (angle - 40) * 1.2
            MeasuredPoint(TorqueAnglePoint(torque.toInt().toDouble(), i, angle, 1000L + i), 1, null, warningLevel = WarningLevel.WATCH, rawHex = "test-frame")
        }
        return MeasurementState(
            linkPhase = LinkPhase.READY,
            connectionState = ConnectionState.Connected("192.168.4.1", 7888),
            session = MeasurementSession(slabNo = "FZB-012", pointNo = "P03"),
            deviceSn = "2026110501", batteryPercent = 82, remoteEnabled = remote,
            recentPoints = points, currentTorqueNm = points.last().sourcePoint.torqueNm, currentAngleDeg = 299.0,
            persistedPointCount = 2505, currentSessionId = 1,
        )
    }

    private fun show(state: MeasurementState, actions: WorkerActions = WorkerActions(), screen: WorkerScreen = WorkerScreen.Workbench) {
        compose.setContent { WifiWrenchWorkerApp(state, actions, debugContent = null, startScreen = screen, immersive = false) }
    }

    @Test fun workbenchShowsSixControlsAndNoPort() {
        show(live(remote = false))
        listOf("forward", "reverse", "stop", "params", "export", "torque_chart", "readouts").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("remote_toggle").assertIsDisplayed()
        compose.onNodeWithTag("forward").assertIsNotEnabled()
        compose.onNodeWithTag("stop").assertIsEnabled()
        listOf("7888", "192.168", "端口", "已确认平衡").forEach { word ->
            assertEquals(0, compose.onAllNodesWithText(word, substring = true).fetchSemanticsNodes().size)
        }
        screenshot("worker-remote-off")
    }

    @Test fun forwardNeedsRemoteAndConfirmation() {
        var forwards = 0
        show(live(remote = true), WorkerActions(forward = { forwards++ }))
        screenshot("worker-remote-on")
        compose.onNodeWithTag("forward").assertIsEnabled().performClick()
        compose.onNodeWithText("确认以正转方向驱动扳手？请确认现场安全。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, forwards) }
        val buttons = compose.onAllNodesWithText("正转")
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        compose.runOnIdle { assertEquals(1, forwards) }
    }

    @Test fun parameterSideSheetAndGuide() {
        show(live(remote = true))
        compose.onNodeWithTag("params").performClick()
        compose.onNodeWithTag("parameter_sheet").assertIsDisplayed()
        screenshot("worker-params")
    }

    @Test fun guidePageHasNoPortField() {
        show(MeasurementState(linkPhase = LinkPhase.NO_WIFI), screen = WorkerScreen.Guide)
        compose.onNodeWithText("去连接 Wi‑Fi").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("端口", substring = true).fetchSemanticsNodes().size)
        screenshot("worker-guide")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        InstrumentationRegistry.getInstrumentation().targetContext.openFileOutput("$name.png", 0).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
