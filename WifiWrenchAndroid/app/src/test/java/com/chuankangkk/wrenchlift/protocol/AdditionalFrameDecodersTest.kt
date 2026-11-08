package com.chuankangkk.wrenchlift.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditionalFrameDecodersTest {
    @Test
    fun ackAndSnFramesParseFromFieldProbe() {
        val packets = WrenchPacketParser().append(
            HexUtils.hexToBytes(
                "C5 C5 FF 06 FF FF 01 00 8E " +
                    "C5 C5 FF 26 FF FF 05 01 00 00 00 01 B4",
            ),
        )

        val ack = AckDecoder.decode(packets[0])
        val sn = DeviceSnDecoder.decode(packets[1])

        assertTrue(ack.isAck)
        assertEquals("应答正常", ack.label)
        assertEquals("0100000001", sn.sn)
    }

    @Test
    fun batteryStatusNutAndGpsFramesAreReadable() {
        val battery = WrenchPacketBuilder.build(WrenchPacket.FUNC_BATTERY, byteArrayOf(0x64), address = 0xFF)
        val status = WrenchPacketBuilder.build(WrenchPacket.FUNC_STATUS, byteArrayOf(0x01, 0x02, 0x03, 0x04), address = 0xFF)
        val nut = WrenchPacketBuilder.build(WrenchPacket.FUNC_NUT_RESET, byteArrayOf(0x01), address = 0xFF)
        val gps = WrenchPacketBuilder.build(WrenchPacket.FUNC_GPS, byteArrayOf(0x01) + "E114.1,N22.3".encodeToByteArray(), address = 0xFF)

        assertEquals(100, BatteryDecoder.decodePercent(battery))
        assertEquals("状态数据 4 字节", DeviceStatusDecoder.decode(status).summary)
        assertEquals("按原螺母数重新开始计数运行", NutResetDecoder.decode(nut).label)
        assertTrue(GpsDecoder.decode(gps).valid)
        assertEquals("E114.1,N22.3", GpsDecoder.decode(gps).text)
    }

    @Test
    fun pulseResultUsesReservedStatusAndTenthsTorque() {
        val payload = ByteArray(0x1C)
        "0000000000".encodeToByteArray().copyInto(payload, 0)
        writeU16(payload, 10, 7)
        writeU16(payload, 12, 2024)
        payload[14] = 9
        payload[15] = 30
        payload[16] = 10
        payload[17] = 1
        payload[18] = 8
        payload[19] = 0
        writeU16(payload, 20, 1234)
        writeU16(payload, 22, 5678)
        writeU16(payload, 24, 90)
        val packet = WrenchPacketBuilder.build(
            functionCode = WrenchPacket.FUNC_PULSE_RESULT,
            payload = payload,
            address = 0xFF,
            reservedHigh = 0x10,
            reservedLow = 0xFF,
        )

        val result = PulseResultFrameDecoder.decode(packet)

        assertEquals(ResultKind.NG, result.status.kind)
        assertEquals("不合格", result.status.label)
        assertEquals(7, result.boltNo)
        assertEquals(123.4, result.targetTorqueNm ?: 0.0, 0.0001)
        assertEquals(567.8, result.actualTorqueNm ?: 0.0, 0.0001)
        assertEquals(90.0, result.actualAngleDeg ?: 0.0, 0.0001)
    }

    /** 协议 0x55 字节 5 只有 0x00 OK / 0x10 NG：0x15 的细分码不能借来解释 0x55。 */
    @Test
    fun pulseResultStatusOnlyUsesCodesDefinedFor0x55() {
        fun status(code: Int) = PulseResultFrameDecoder.decode(
            WrenchPacketBuilder.build(WrenchPacket.FUNC_PULSE_RESULT, ByteArray(0x1C), reservedHigh = code, reservedLow = 0xFF),
        ).status
        assertEquals(ResultKind.OK, status(0x00).kind)
        assertEquals(ResultKind.NG, status(0x10).kind)
        listOf(0x01, 0x02, 0x13, 0x16).forEach { code ->
            assertEquals(ResultKind.UNKNOWN, status(code).kind)
            assertEquals("未定义状态 0x%02X".format(code), status(code).label)
        }
        // 0x15 仍按自己的细分表。
        assertEquals("疑似打滑", ResultFrameDecoder.mapStatus(0x13).label)
    }

    @Test
    fun nakIsRecognized() {
        val nak = WrenchPacketBuilder.build(WrenchPacket.FUNC_ACK, byteArrayOf(0x01), address = 0xFF)

        assertFalse(AckDecoder.decode(nak).isAck)
        assertEquals("设备拒绝", AckDecoder.decode(nak).label)
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }
}
