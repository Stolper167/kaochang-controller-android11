package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean

object BusinessRuntimeStateHelper {

    data class ChangeResult(
        val changed: Boolean,
        val previousOnlineStatus: Int,
        val previousIsEnable: Int,
        val previousRestStatusSource: String?
    )

    fun applyAutoEndBusiness(config: AppConfigBean): ChangeResult {
        val previousOnlineStatus = config.onlineStatus
        val previousIsEnable = config.isEnable
        val previousRestStatusSource = config.restStatusSource
        config.onlineStatus = 2
        config.isEnable = 0
        config.restStatusSource = AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
        return ChangeResult(
            changed = previousOnlineStatus != config.onlineStatus ||
                previousIsEnable != config.isEnable ||
                previousRestStatusSource != config.restStatusSource,
            previousOnlineStatus = previousOnlineStatus,
            previousIsEnable = previousIsEnable,
            previousRestStatusSource = previousRestStatusSource
        )
    }

    fun applyAutoStartBusiness(config: AppConfigBean): ChangeResult {
        val previousOnlineStatus = config.onlineStatus
        val previousIsEnable = config.isEnable
        val previousRestStatusSource = config.restStatusSource
        if (previousOnlineStatus != 2 ||
            previousRestStatusSource != AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
        ) {
            return ChangeResult(
                changed = false,
                previousOnlineStatus = previousOnlineStatus,
                previousIsEnable = previousIsEnable,
                previousRestStatusSource = previousRestStatusSource
            )
        }
        config.onlineStatus = 1
        config.isEnable = 1
        config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
        return ChangeResult(
            changed = previousOnlineStatus != config.onlineStatus ||
                previousIsEnable != config.isEnable ||
                previousRestStatusSource != config.restStatusSource,
            previousOnlineStatus = previousOnlineStatus,
            previousIsEnable = previousIsEnable,
            previousRestStatusSource = previousRestStatusSource
        )
    }
}
