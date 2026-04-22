package cn.niuyannet.kaochang.android.modbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VMModbusWriteTraceTest {

    @Test
    fun describeRegister_shouldReturnChineseMeaningForKnownRegisters() {
        assertEquals("200（动作指令寄存器）", VMModbusHelper.describeWriteRegister(200))
        assertEquals("207（售卖台动作寄存器）", VMModbusHelper.describeWriteRegister(207))
        assertEquals("220（卖肠分步协议命令寄存器）", VMModbusHelper.describeWriteRegister(220))
    }

    @Test
    fun describeWriteValue_shouldExplainKnownBusinessValues() {
        assertEquals("103（烤盘3到售卖口）", VMModbusHelper.describeWriteValue(200, 103))
        assertEquals("1（关门）", VMModbusHelper.describeWriteValue(207, 1))
        assertEquals("3（NEXT/推进下一动作包）", VMModbusHelper.describeWriteValue(220, 3))
        assertEquals("2049（卖肠目标烤盘=8）", VMModbusHelper.describeWriteValue(221, 0x0801))
    }

    @Test
    fun buildWriteTraceMessage_shouldContainSequenceSourceRegisterValueAndResult() {
        val message = VMModbusHelper.buildWriteTraceMessage(
            seq = 12L,
            serverAddress = 1,
            startAddress = 220,
            value = 3,
            result = VMModbusHelper.ModbusOperationStatus.SUCCESS,
            source = "VmLinkedSellProtocolAdapter.writeCommand（分步卖肠协议写命令）",
            action = "linked_sell_next（分步卖肠推进到下一动作包）",
            message = null
        )

        assertTrue(message.contains("seq=12"))
        assertTrue(message.contains("server=1（下位机地址）"))
        assertTrue(message.contains("register=220（卖肠分步协议命令寄存器）"))
        assertTrue(message.contains("value=3（NEXT/推进下一动作包）"))
        assertTrue(message.contains("result=SUCCESS（写入成功）"))
    }
}
