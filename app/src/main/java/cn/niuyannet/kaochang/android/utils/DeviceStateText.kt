package cn.niuyannet.kaochang.android.utils

import cn.niuyannet.kaochang.android.init.AppConfig

object DeviceStateText {
    fun serviceStatus(value: Int): String = when (value) {
        0 -> "0（停用/离线）"
        1 -> "1（启用/在线）"
        else -> "$value（未知）"
    }

    fun onlineStatus(value: Int): String = when (value) {
        0 -> "0（未启用）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "$value（未知）"
    }

    fun isEnable(value: Int): String = when (value) {
        0 -> "0（关闭）"
        1 -> "1（开启）"
        else -> "$value（未知）"
    }

    fun modeType(value: Int): String = when (value) {
        1 -> "1（低并发）"
        2 -> "2（高并发）"
        else -> "$value（未知）"
    }

    fun transitionMode(value: Int): String = when (value) {
        0 -> "0（无过渡态）"
        1 -> "1（低转高过渡态）"
        2 -> "2（高转低过渡态）"
        else -> "$value（未知）"
    }

    fun moveStatus(value: Int): String = when (value) {
        0 -> "0（机械臂空闲）"
        1 -> "1（补肠中）"
        2 -> "2（夹取/出餐中）"
        3 -> "3（烤盘搬移中）"
        4 -> "4（丢弃中）"
        5 -> "5（自清洁中）"
        else -> "$value（未知）"
    }

    fun errorStatus(value: Int): String = when (value) {
        0 -> "0（正常）"
        1 -> "1（识别/流程错误）"
        2 -> "2（通信/硬件级故障）"
        else -> "$value（未知）"
    }

    fun restStatusSource(value: String?): String = AppConfig.restStatusSourceText(value)
}
