package cn.niuyannet.kaochang.android.init

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityPolicyTest {

    @Test
    fun normalizeDeviceCode_shouldNotHardcodeAliasMapping() {
        val normalized = DeviceIdentityPolicy.normalizeDeviceCode("95cdf4e762fb2886")

        assertEquals("95cdf4e762fb2886", normalized)
    }

    @Test
    fun normalizeDeviceCode_shouldTrimLowercaseAndAllowActivationStyleCode() {
        val normalized = DeviceIdentityPolicy.normalizeDeviceCode("  Activate_2026-ABCD  ")

        assertEquals("activate_2026-abcd", normalized)
    }

    @Test
    fun normalizeDeviceCode_shouldRejectInvalidDeviceCode() {
        assertNull(DeviceIdentityPolicy.normalizeDeviceCode("abc"))
        assertNull(DeviceIdentityPolicy.normalizeDeviceCode("中文设备编号"))
        assertNull(DeviceIdentityPolicy.normalizeDeviceCode("04086c2932977630/../"))
    }

    @Test
    fun canInitializeWithDeviceCodeOrActivationCode_shouldAllowActivationCodeOnly() {
        assertTrue(DeviceIdentityPolicy.canInitializeWithDeviceCodeOrActivationCode("", "KC-1234-5678-ABCD"))
    }

    @Test
    fun sha256Hex_shouldFormatBytesAsTwoDigitUnsignedHex() {
        val bytes = byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte())

        assertEquals("007f80ff", DeviceIdentityPolicy.bytesToHex(bytes))
    }

    @Test
    fun resolveDeviceCode_shouldPreferLockedDeviceCodeAndIgnoreChangedAndroidId() {
        val resolved = DeviceIdentityPolicy.resolveDeviceCode(
            remoteDeviceCode = null,
            sharedPreferenceDeviceCode = "04086c2932977630",
            noBackupDeviceCode = null,
            publicDeviceCode = null,
            appConfigDeviceCode = null,
            legacyCachedDeviceCode = null,
            systemAndroidId = "95cdf4e762fb2886"
        )

        assertEquals("04086c2932977630", resolved.deviceCode)
        assertEquals(DeviceIdentityPolicy.IdentitySource.SHARED_PREFERENCE, resolved.source)
        assertTrue(resolved.systemAndroidIdIgnored)
    }

    @Test
    fun resolveDeviceCode_shouldRecoverFromPublicIdentityWhenSharedPreferenceIsMissing() {
        val resolved = DeviceIdentityPolicy.resolveDeviceCode(
            remoteDeviceCode = null,
            sharedPreferenceDeviceCode = null,
            noBackupDeviceCode = null,
            publicDeviceCode = "04086c2932977630",
            appConfigDeviceCode = null,
            legacyCachedDeviceCode = null,
            systemAndroidId = "95cdf4e762fb2886"
        )

        assertEquals("04086c2932977630", resolved.deviceCode)
        assertEquals(DeviceIdentityPolicy.IdentitySource.PUBLIC_FILE, resolved.source)
        assertTrue(resolved.systemAndroidIdIgnored)
    }

    @Test
    fun resolveDeviceCode_shouldNotUseAndroidIdWhenNoStableIdentityExists() {
        val resolved = DeviceIdentityPolicy.resolveDeviceCode(
            remoteDeviceCode = null,
            sharedPreferenceDeviceCode = null,
            noBackupDeviceCode = null,
            publicDeviceCode = null,
            appConfigDeviceCode = null,
            legacyCachedDeviceCode = null,
            systemAndroidId = "abcdef1234567890"
        )

        assertNull(resolved.deviceCode)
        assertEquals(DeviceIdentityPolicy.IdentitySource.NONE, resolved.source)
        assertTrue(resolved.systemAndroidIdIgnored)
    }
}
