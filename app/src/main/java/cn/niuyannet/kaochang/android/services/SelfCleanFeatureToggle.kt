package cn.niuyannet.kaochang.android.services

object SelfCleanFeatureToggle {
    private const val ENABLE_SELF_CLEAN = false
    const val DISABLED_MESSAGE = "self_clean_disabled"
    private const val DISABLED_REASON_TEXT = "当前版本已屏蔽自清洁，下位机尚未开发完成"

    fun isEnabled(): Boolean = ENABLE_SELF_CLEAN

    fun disabledReasonText(): String = DISABLED_REASON_TEXT

    fun buildDisabledExecutionReport(targetLabel: String): KaoChangOperate.SelfCleanExecutionReport {
        return KaoChangOperate.SelfCleanExecutionReport(
            success = false,
            command = -1,
            targetLabel = targetLabel,
            status201 = -1,
            message = DISABLED_MESSAGE
        )
    }

    fun buildDisabledSequenceResult(targetLabel: String = "自清洁总开关"): KaoChangOperate.SelfCleanSequenceResult {
        return KaoChangOperate.SelfCleanSequenceResult(
            success = false,
            reports = listOf(buildDisabledExecutionReport(targetLabel))
        )
    }
}
