package com.chuankangkk.wrenchlift.measurement

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.chuankangkk.wrenchlift.connection.DeviceIdentity
import com.chuankangkk.wrenchlift.connection.ScriptedWrenchServer
import com.chuankangkk.wrenchlift.diagnostics.DiagnosticJournal.Problem
import com.chuankangkk.wrenchlift.diagnostics.DiagnosticLog
import com.chuankangkk.wrenchlift.protocol.WrenchPacket
import com.chuankangkk.wrenchlift.protocol.WrenchPacketBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * 设备身份核对（本机 TCP 扳手 + 真实 ViewModel，无硬件）：
 * 记住的编号与回话不一致时，工人主台要看见提示、诊断要记问题点；
 * 一条连接没核对过编号就收到测量帧，诊断里也要留下「未核对」——冒充者只要不回 0x26 就能绕过比对。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceVerificationViewModelTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var app: Application
    private val store = ViewModelStore()

    @Before fun setUp() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("connection_preferences", 0).edit().clear().commit()
    }

    @After fun tearDown() {
        store.clear()
        app.getSharedPreferences("connection_preferences", 0).edit().clear().commit()
        Dispatchers.resetMain()
        main.close()
    }

    /** 先在偏好里放一个「记住的编号」，ViewModel 构造时会读它。 */
    private fun pin(sn: String) {
        app.getSharedPreferences("connection_preferences", 0).edit()
            .putString(DeviceIdentity.PREF_PINNED_SN, sn)
            .commit()
    }

    private fun viewModelFor(server: ScriptedWrenchServer): MeasurementViewModel {
        app.getSharedPreferences("connection_preferences", 0).edit()
            .putString(MeasurementViewModel.PREF_LAST_GOOD_HOST, server.endpoint.host)
            .putString(MeasurementViewModel.PREF_LAST_GOOD_PORT, server.endpoint.port.toString())
            .commit()
        return MeasurementViewModel(app, autoConnectOnWifi = false, handshakeTimeoutMillis = 1_500).also {
            store.put("vm-${server.port}", it)
        }
    }

    private fun awaitCondition(timeoutMillis: Long = 15_000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时：$message")
    }

    @Test fun differentSnIsReportedOnTheWorkerScreen() {
        // 夹具扳手回的编号是 2026110501，这里记住另一个：应当判为不一致。
        pin("AAAABBBB")
        ScriptedWrenchServer().use { wrench ->
            val journal = DiagnosticLog.journal
            val before = journal.counters.value.problems[Problem.DEVICE_SN_MISMATCH] ?: 0
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "判为编号不一致") { vm.state.value.deviceSnMismatch }
            assertTrue("工人主台要有文字提示：${vm.state.value.controlMessage}", vm.state.value.controlMessage.isNotBlank())
            assertTrue(
                "诊断要记一条设备编号不一致",
                (journal.counters.value.problems[Problem.DEVICE_SN_MISMATCH] ?: 0) > before,
            )
            vm.endSession(false)
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }

    /** 冒充者不回 0x26 也能发测量帧：这一路没有比对过编号，诊断里必须留下记录，且只记一条。 */
    @Test fun measurementFramesWithoutSnReplyAreLoggedOnce() {
        pin("2026110501")
        ScriptedWrenchServer(replies = false).use { wrench ->
            val journal = DiagnosticLog.journal
            val before = journal.counters.value.problems[Problem.DEVICE_SN_UNVERIFIED] ?: 0
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "TCP 已连上、等待扳手回话") { vm.state.value.linkPhase == LinkPhase.WAITING_REPLY }
            val frame = WrenchPacketBuilder.build(WrenchPacket.FUNC_TORQUE_ANGLE, ByteArray(12 + 4))
            wrench.send(frame)
            awaitCondition(message = "记下未核对编号") {
                (journal.counters.value.problems[Problem.DEVICE_SN_UNVERIFIED] ?: 0) > before
            }
            wrench.send(frame)
            Thread.sleep(500)
            assertEquals(
                "同一条连接只记一条未核对",
                1L,
                (journal.counters.value.problems[Problem.DEVICE_SN_UNVERIFIED] ?: 0) - before,
            )
            vm.endSession(false)
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }
}