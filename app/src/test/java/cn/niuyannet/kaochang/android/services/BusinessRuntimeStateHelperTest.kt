package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessRuntimeStateHelperTest {

    @Test
    fun applyAutoEndBusiness_shouldSwitchToRestingAndDisableAlgorithm() {
        val config = AppConfigBean().apply {
            onlineStatus = 1
            isEnable = 1
            restStatusSource = AppConfigBean.REST_SOURCE_NONE
        }

        val result = BusinessRuntimeStateHelper.applyAutoEndBusiness(config)

        assertTrue(result.changed)
        assertEquals(2, config.onlineStatus)
        assertEquals(0, config.isEnable)
        assertEquals(AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS, config.restStatusSource)
        assertEquals(1, result.previousOnlineStatus)
        assertEquals(1, result.previousIsEnable)
    }

    @Test
    fun applyAutoStartBusiness_shouldRestoreOnlyAutoRestingState() {
        val config = AppConfigBean().apply {
            onlineStatus = 2
            isEnable = 0
            restStatusSource = AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
        }

        val result = BusinessRuntimeStateHelper.applyAutoStartBusiness(config)

        assertTrue(result.changed)
        assertEquals(1, config.onlineStatus)
        assertEquals(1, config.isEnable)
        assertEquals(AppConfigBean.REST_SOURCE_NONE, config.restStatusSource)
        assertEquals(2, result.previousOnlineStatus)
        assertEquals(0, result.previousIsEnable)
    }

    @Test
    fun applyAutoStartBusiness_shouldNotRestoreManualRestingState() {
        val config = AppConfigBean().apply {
            onlineStatus = 2
            isEnable = 0
            restStatusSource = AppConfigBean.REST_SOURCE_MANUAL_REMOTE
        }

        val result = BusinessRuntimeStateHelper.applyAutoStartBusiness(config)

        assertFalse(result.changed)
        assertEquals(2, config.onlineStatus)
        assertEquals(0, config.isEnable)
        assertEquals(AppConfigBean.REST_SOURCE_MANUAL_REMOTE, config.restStatusSource)
    }
}
