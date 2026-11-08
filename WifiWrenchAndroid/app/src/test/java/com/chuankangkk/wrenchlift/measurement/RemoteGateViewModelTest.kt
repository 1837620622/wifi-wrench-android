package com.chuankangkk.wrenchlift.measurement

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.chuankangkk.wrenchlift.connection.ScriptedWrenchServer
import com.chuankangkk.wrenchlift.protocol.TestPacketFactory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * 正式控制面的协议时序（无硬件）：本机 TCP 扳手 + 真实 ViewModel。
 * 覆盖：握手后缓存端点、未开远程拦截正/反转、先 0x11 再 0x01、0x15 自动 0x17、结束会话不发停止。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteGateViewModelTest {
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

    private fun viewModelFor(server: ScriptedWrenchServer): MeasurementViewModel {
        app.getSharedPreferences("connection_preferences", 0).edit()
            .putString(MeasurementViewModel.PREF_LAST_GOOD_HOST, server.endpoint.host)
            .putString(MeasurementViewModel.PREF_LAST_GOOD_PORT, server.endpoint.port.toString())
            .commit()
        return MeasurementViewModel(app, autoConnectOnWifi = false, handshakeTimeoutMillis = 1_500).also {
            store.put("vm-${server.port}", it)
        }
    }

    private fun awaitCondition(timeoutMillis: Long = 8_000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时：$message")
    }

    private fun ScriptedWrenchServer.frames(code: Int) = received.filter { it.functionCode == code }

    @Test fun motionNeedsRemoteFirstAndEndingSessionDoesNotStopWrench() {
        ScriptedWrenchServer().use { wrench ->
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "握手成功") { vm.state.value.linkPhase == LinkPhase.READY }
            assertEquals(wrench.endpoint, vm.state.value.lastGoodEndpoint)
            assertEquals("20261105 01".replace(" ", ""), vm.state.value.deviceSn)
            assertFalse("连接成功不得自动开远程", vm.state.value.remoteEnabled)
            assertTrue(wrench.frames(WrenchPacket.FUNC_REMOTE_CONTROL).isEmpty())

            // 未开远程：正/反转在 App 侧被拦截，扳手收不到 0x01。
            vm.startForward()
            vm.startReverse()
            awaitCondition(message = "拦截提示") { vm.state.value.controlMessage == "请先启用远程控制" }
            Thread.sleep(300)
            assertTrue(wrench.frames(WrenchPacket.FUNC_STOP_RUN).isEmpty())

            vm.enableRemoteControl()
            awaitCondition(message = "远程已启用") { vm.state.value.remoteEnabled }
            vm.startForward()
            awaitCondition(message = "收到正转") { wrench.frames(WrenchPacket.FUNC_STOP_RUN).isNotEmpty() }
            val codes = wrench.functionCodes()
            assertTrue("0x11 必须先于 0x01", codes.indexOf(WrenchPacket.FUNC_REMOTE_CONTROL) < codes.indexOf(WrenchPacket.FUNC_STOP_RUN))
            assertEquals(0x01, wrench.frames(WrenchPacket.FUNC_REMOTE_CONTROL).single().payload.single().toInt())
            assertEquals(0x01, wrench.frames(WrenchPacket.FUNC_STOP_RUN).single().payload.single().toInt())
            // 扳手先收到帧、send 返回后 VM 才更新状态：等待而非立即断言（避免竞态）。
            awaitCondition(message = "状态为正转") { vm.state.value.motion == MotionCommand.FORWARD }

            // 运行结束结果帧：App 自动回 0x17，运动状态回到已停止。
            wrench.send(TestPacketFactory.resultFrame(0x00, "0000000000", 1, 182, 88))
            awaitCondition(message = "自动 0x17") { wrench.frames(WrenchPacket.FUNC_RESULT_CONFIRM).isNotEmpty() }
            awaitCondition(message = "结果后已停止") { vm.state.value.motion == MotionCommand.STOPPED }

            // 停止始终可发。
            vm.stopWrench()
            awaitCondition(message = "收到停止") { wrench.frames(WrenchPacket.FUNC_STOP_RUN).size == 2 }
            assertEquals(0x00, wrench.frames(WrenchPacket.FUNC_STOP_RUN).last().payload.single().toInt())

            // 结束会话 ≠ 停止扳手：不得追加 0x01。
            val stopFramesBefore = wrench.frames(WrenchPacket.FUNC_STOP_RUN).size
            vm.endSession()
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE && !vm.state.value.connectionState.isOnline }
            Thread.sleep(200)
            assertEquals(stopFramesBefore, wrench.frames(WrenchPacket.FUNC_STOP_RUN).size)
            assertFalse(vm.state.value.autoConnect)
            assertFalse(vm.state.value.remoteEnabled)
        }
    }

    @Test fun endSessionWithStopFirstSendsExplicitStop() {
        ScriptedWrenchServer().use { wrench ->
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "握手成功") { vm.state.value.linkPhase == LinkPhase.READY }
            vm.endSession(stopWrenchFirst = true)
            awaitCondition(message = "收到停止") { wrench.frames(WrenchPacket.FUNC_STOP_RUN).isNotEmpty() }
            assertEquals(0x00, wrench.frames(WrenchPacket.FUNC_STOP_RUN).single().payload.single().toInt())
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }

    @Test fun exportWritesRealXlsxWithAllPersistedPoints() {
        ScriptedWrenchServer().use { wrench ->
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "握手成功") { vm.state.value.linkPhase == LinkPhase.READY }
            val payload = ByteArray(12 + 4 * 25)
            for (i in 0 until 25) {
                payload[13 + i * 4] = (i * 7).toByte()
                payload[15 + i * 4] = i.toByte()
            }
            repeat(3) { wrench.send(WrenchPacketBuilder.build(WrenchPacket.FUNC_TORQUE_ANGLE, payload)) }
            awaitCondition(message = "75 点入库") { vm.state.value.persistedPointCount == 75L }
            vm.exportCurrent()
            awaitCondition(timeoutMillis = 15_000, message = "导出完成") { vm.state.value.lastExport != null && !vm.state.value.exportInProgress }
            val outcome = vm.state.value.lastExport!!
            assertTrue(outcome.message, outcome.success)
            assertEquals("导出完成（含图表）", outcome.message)
            assertTrue(outcome.displayPath.endsWith(".xlsx"))
            // 读回 ViewModel 真正写出的文件：75 个过程点全部在表里，扭矩与发出的帧一致。
            val uri = android.net.Uri.parse(outcome.shareUri!!)
            val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            org.apache.poi.xssf.usermodel.XSSFWorkbook(java.io.ByteArrayInputStream(bytes)).use { book ->
                val data = book.getSheet("过程数据")
                assertEquals(75, data.lastRowNum)
                val header = data.getRow(0)
                fun col(name: String) = (0 until header.lastCellNum).first { header.getCell(it).stringCellValue == name }
                assertEquals(0.0, data.getRow(1).getCell(col("扭矩 N·m")).numericCellValue, 0.0)
                assertEquals(24 * 7.0, data.getRow(25).getCell(col("扭矩 N·m")).numericCellValue, 0.0)
                assertEquals(24.0, data.getRow(75).getCell(col("转角 °")).numericCellValue, 0.0)
                // 同一帧 25 个点共用接收时间：帧内序号 1..25，帧内点数 25。
                assertEquals(25.0, data.getRow(25).getCell(col("帧内序号")).numericCellValue, 0.0)
                assertEquals(25.0, data.getRow(25).getCell(col("帧内点数")).numericCellValue, 0.0)
                // 三帧字节完全相同也按帧内点数分开。
                assertEquals(1.0, data.getRow(26).getCell(col("帧内序号")).numericCellValue, 0.0)
                assertEquals(3.0, data.getRow(75).getCell(col("帧号")).numericCellValue, 0.0)
                assertTrue(book.getSheet("图表").createDrawingPatriarch().charts.size >= 4)
            }
            vm.endSession()
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }

    @Test fun pulseWrenchTypeSends0x50AndStatusFrameSurfacesErrorCode() {
        ScriptedWrenchServer().use { wrench ->
            val vm = viewModelFor(wrench)
            vm.setWrenchModel(WrenchModel.PULSE_RC283F)
            vm.connectTcp()
            awaitCondition(message = "握手成功") { vm.state.value.linkPhase == LinkPhase.READY }
            vm.writePulseParameters(PulseDraft(mode = 0x01, targetTorque = "250.5", k1 = "200", k2 = "40", k3 = "1500", boltCount = "8"))
            awaitCondition(message = "收到 0x50") { wrench.frames(WrenchPacket.FUNC_PULSE_PARAM_SET_RC283F).isNotEmpty() }
            val frame = wrench.frames(WrenchPacket.FUNC_PULSE_PARAM_SET_RC283F).single()
            assertEquals(0x50, frame.payload.size)
            assertEquals(2505, ((frame.payload[68].toInt() and 0xFF) shl 8) or (frame.payload[69].toInt() and 0xFF))
            assertTrue(wrench.frames(WrenchPacket.FUNC_PARAM_SET).isEmpty())
            // 越界参数在 App 侧拦截，不下发。
            vm.writePulseParameters(PulseDraft(mode = 0x01, targetTorque = "100", k1 = "10", k2 = "40", k3 = "1500"))
            awaitCondition(message = "拦截提示") { vm.state.value.pulseStatus.contains("K1") }
            Thread.sleep(200)
            assertEquals(1, wrench.frames(WrenchPacket.FUNC_PULSE_PARAM_SET_RC283F).size)

            wrench.send(WrenchPacketBuilder.build(WrenchPacket.FUNC_STATUS, byteArrayOf(0x00, 0x12, 0x00, 0x01)))
            awaitCondition(message = "错误码") { vm.state.value.deviceErrorCode == 0x12 }
            assertEquals(true, vm.state.value.deviceReverseDirection)
            vm.setWrenchModel(WrenchModel.TORQUE)
            vm.endSession()
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }

    @Test fun processFramesFeedAdaptiveDisplayWindowBeyondFiftyPoints() {
        ScriptedWrenchServer().use { wrench ->
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "握手成功") { vm.state.value.linkPhase == LinkPhase.READY }
            repeat(4) { frame ->
                val payload = ByteArray(12 + 4 * 30)
                for (i in 0 until 30) {
                    val torque = frame * 30 + i
                    val angle = frame * 30 + i
                    payload[12 + i * 4] = (torque ushr 8).toByte(); payload[13 + i * 4] = torque.toByte()
                    payload[14 + i * 4] = (angle ushr 8).toByte(); payload[15 + i * 4] = angle.toByte()
                }
                wrench.send(WrenchPacketBuilder.build(WrenchPacket.FUNC_TORQUE_ANGLE, payload))
            }
            awaitCondition(message = "120 点入库") { vm.state.value.persistedPointCount == 120L }
            // 显示窗口不再死锁最近 50 点。
            assertEquals(120, vm.state.value.recentPoints.size)
            assertEquals(119.0, vm.state.value.currentTorqueNm, 0.0)
            vm.endSession()
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }
    /** 协议：扳手发 0x15 后 0.5 秒内收不到 0x17 会把结果另存。App 先确认再入库；结果点不并进实时过程曲线。 */
    @Test fun resultFrameIsAckedRightAwayAndStaysOffTheLiveCurve() {
        ScriptedWrenchServer().use { wrench ->
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "握手成功") { vm.state.value.linkPhase == LinkPhase.READY }
            val payload = ByteArray(12 + 4 * 30)
            for (i in 0 until 30) {
                payload[13 + i * 4] = (i * 5).toByte()
                payload[15 + i * 4] = i.toByte()
            }
            wrench.send(WrenchPacketBuilder.build(WrenchPacket.FUNC_TORQUE_ANGLE, payload))
            awaitCondition(message = "30 点入库") { vm.state.value.persistedPointCount == 30L }
            vm.updateParameterTargetAngle("37.5")
            val sentAt = System.nanoTime()
            wrench.send(TestPacketFactory.resultFrame(0x00, "0000000000", 1, 182, 88))
            awaitCondition(timeoutMillis = 2_000, message = "自动 0x17") { wrench.frames(WrenchPacket.FUNC_RESULT_CONFIRM).isNotEmpty() }
            val ackMillis = (System.nanoTime() - sentAt) / 1_000_000
            assertTrue("0x17 用时 $ackMillis ms（协议要求 0.5 s 内；本机余量放宽到 1 s）", ackMillis < 1_000)
            awaitCondition(message = "结果入库") { vm.state.value.persistedPointCount == 31L }
            assertEquals("结果帧不并进实时曲线", 30, vm.state.value.recentPoints.size)
            assertEquals(182.0, vm.state.value.currentTorqueNm, 0.0)
            assertEquals("合格", vm.state.value.resultStatus)
            // 夹具是 M1 结果帧：28-29 目标扭矩可回填；32-33 在 M1 下不是设定值，不能把用户填的目标角度冲掉。
            assertEquals("180", vm.state.value.parameterTargetTorque)
            assertEquals("37.5", vm.state.value.parameterTargetAngle)
            vm.endSession()
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }

    /** 0x06 不带被应答的功能码：只有紧跟在操作后的 NAK 才算「扳手没有接受」，过后到来的 NAK 不改远程状态。 */
    @Test fun nakIsOnlyAttributedToARecentCommand() {
        ScriptedWrenchServer().use { wrench ->
            val vm = viewModelFor(wrench)
            vm.connectTcp()
            awaitCondition(message = "握手成功") { vm.state.value.linkPhase == LinkPhase.READY }
            vm.enableRemoteControl()
            awaitCondition(message = "远程已启用") { vm.state.value.remoteEnabled }
            wrench.send(WrenchPacketBuilder.build(WrenchPacket.FUNC_ACK, byteArrayOf(0x01)))
            awaitCondition(message = "NAK 关回远程") { !vm.state.value.remoteEnabled }
            assertEquals("扳手没有接受：开启远程", vm.state.value.controlMessage)

            vm.enableRemoteControl()
            awaitCondition(message = "远程再次启用") { vm.state.value.remoteEnabled }
            Thread.sleep(MeasurementViewModel.NAK_ATTRIBUTION_MILLIS + 300)
            val framesBefore = vm.state.value.receivedFrameCount
            wrench.send(WrenchPacketBuilder.build(WrenchPacket.FUNC_ACK, byteArrayOf(0x01)))
            awaitCondition(message = "收到迟到的 NAK") { vm.state.value.receivedFrameCount > framesBefore }
            Thread.sleep(100)
            assertTrue("迟到的 NAK 不应关掉远程", vm.state.value.remoteEnabled)
            vm.endSession()
            awaitCondition(message = "会话结束") { vm.state.value.linkPhase == LinkPhase.IDLE }
        }
    }
}
