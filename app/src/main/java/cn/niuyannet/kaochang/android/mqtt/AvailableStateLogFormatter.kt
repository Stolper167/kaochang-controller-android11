package cn.niuyannet.kaochang.android.mqtt

import kotlin.math.ceil

object AvailableStateLogFormatter {

    fun displayText(available: Long): String {
        return if (available <= 0L) {
            "扫码下单 纯肉烤肠"
        } else if (available >= 60_000L) {
            "预计烤制 ${ceil(available / 60000.0).toInt()} 分钟"
        } else {
            "预计烤制 ${ceil(available / 1000.0).toInt()} 秒"
        }
    }

    fun buildStateLogKey(available: Long): String {
        return displayText(available)
    }

    fun buildAckLogKey(
        available: Long,
        applied: Boolean,
        reason: String
    ): String {
        val result = if (applied) "applied" else "not_applied:${reason.ifBlank { "unknown" }}"
        return "$result|${buildStateLogKey(available)}"
    }
}
