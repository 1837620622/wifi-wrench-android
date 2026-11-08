package com.chuankangkk.wrenchlift.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ResultFrameDecoderTest {
    @Test
    fun resultStatusCodesAreMapped() {
        assertEquals("合格", ResultFrameDecoder.mapStatus(0x00).label)
        assertEquals(ResultKind.OK, ResultFrameDecoder.mapStatus(0x01).kind)
        assertEquals("位置到达", ResultFrameDecoder.mapStatus(0x02).label)
        assertEquals("不合格", ResultFrameDecoder.mapStatus(0x10).label)
        assertEquals("扭矩偏低", ResultFrameDecoder.mapStatus(0x11).label)
        assertEquals("扭矩偏高", ResultFrameDecoder.mapStatus(0x12).label)
        assertEquals("疑似打滑", ResultFrameDecoder.mapStatus(0x13).label)
        assertEquals("角度偏小", ResultFrameDecoder.mapStatus(0x14).label)
        assertEquals("角度偏大", ResultFrameDecoder.mapStatus(0x15).label)
        assertEquals("重复拧紧", ResultFrameDecoder.mapStatus(0x16).label)
        assertEquals(ResultKind.UNKNOWN, ResultFrameDecoder.mapStatus(0x7F).kind)
    }

    @Test
    fun manualResultFrameUsesReservedHighAsStatusAndParsesFields() {
        val raw = HexUtils.hexToBytes(
            "C5 C5 01 15 FF FF 24 " +
                "30 30 30 30 30 30 30 30 30 30 " +
                "00 01 07 E8 09 1E 0A 01 08 02 " +
                "00 B5 01 E5 00 2D 00 2C 01 F4 01 90 00 64 00 01 AD",
        )
        val packet = WrenchPacketParser().append(raw).single()
        val result = ResultFrameDecoder.decode(packet)

        assertEquals(ResultKind.UNKNOWN, result.status.kind)
        assertEquals(0xFF, result.status.code)
        assertEquals("未定义状态 0xFF", result.status.label)
        assertEquals("0000000000", result.employeeId)
        assertEquals(1, result.boltNo)
        assertEquals(2, result.mode)
        assertEquals(181.0, result.targetTorqueNm)
        assertEquals(485.0, result.actualTorqueNm)
        assertEquals(45.0, result.targetAngleDeg)
        assertEquals(44.0, result.actualAngleDeg)
        assertEquals(500.0, result.torqueUpperNm)
        assertEquals(400.0, result.torqueLowerNm)
        assertEquals(100.0, result.angleUpperDeg)
        assertEquals(1.0, result.angleLowerDeg)
    }

    @Test
    fun statusResultFrameParsesStatusAndFields() {
        val packet = TestPacketFactory.resultFrame(
            statusCode = 0x11,
            employeeId = "0000000000",
            boltNo = 2,
            actualTorqueNm = 168,
            actualAngleDeg = 39,
        )
        val result = ResultFrameDecoder.decode(packet)

        assertEquals(ResultKind.NG, result.status.kind)
        assertEquals("扭矩偏低", result.status.label)
        assertEquals(2, result.boltNo)
        assertEquals(168.0, result.actualTorqueNm)
        assertEquals(39.0, result.actualAngleDeg)
    }

    @Test
    fun fieldCapturedResultFrameMatchesManualOffsets() {
        val raw = HexUtils.hexToBytes(
            "C5 C5 FF 15 FF FF 24 " +
                "30 31 30 30 30 30 30 30 30 31 " +
                "00 01 07 E8 01 01 02 24 31 01 " +
                "01 F4 00 30 00 00 00 1B 00 00 00 00 01 68 19 98 46",
        )
        val packet = WrenchPacketParser().append(raw).single()
        val result = ResultFrameDecoder.decode(packet)

        assertEquals(0xFF, result.status.code)
        assertEquals("未定义状态 0xFF", result.status.label)
        assertEquals("0100000001", result.employeeId)
        assertEquals(1, result.boltNo)
        assertEquals(2024, result.year)
        assertEquals(1, result.month)
        assertEquals(1, result.day)
        assertEquals(1, result.mode)
        assertEquals(500.0, result.targetTorqueNm)
        assertEquals(48.0, result.actualTorqueNm)
        assertEquals(0.0, result.targetAngleDeg)
        assertEquals(27.0, result.actualAngleDeg)
        assertEquals(360.0, result.angleUpperDeg)
    }
}
