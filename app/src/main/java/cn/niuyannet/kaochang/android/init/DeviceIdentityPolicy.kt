package cn.niuyannet.kaochang.android.init

/**
 * 设备身份归一化策略。
 *
 * 注意：systemAndroidId（系统 ANDROID_ID）只能作为诊断字段，不能作为新设备编号兜底。
 */
object DeviceIdentityPolicy {
    private val DEVICE_CODE_PATTERN = Regex("^[a-z0-9_-]{6,64}$")

    enum class IdentitySource {
        REMOTE_CONFIG,
        SHARED_PREFERENCE,
        NO_BACKUP_FILE,
        PUBLIC_FILE,
        APP_CONFIG,
        LEGACY_CACHED_DEVICE_CODE,
        NONE
    }

    data class DeviceIdentityResolution(
        val deviceCode: String?,
        val source: IdentitySource,
        val rawDeviceCode: String?,
        val systemAndroidIdIgnored: Boolean
    )

    private data class Candidate(
        val source: IdentitySource,
        val rawDeviceCode: String,
        val normalizedDeviceCode: String
    )

    fun normalizeDeviceCode(deviceCode: String?): String? {
        val trimmed = deviceCode?.trim()?.lowercase().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        return trimmed.takeIf { DEVICE_CODE_PATTERN.matches(it) }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun canInitializeWithDeviceCodeOrActivationCode(deviceCode: String?, activationCode: String?): Boolean {
        return normalizeDeviceCode(deviceCode) != null || !activationCode?.trim().isNullOrBlank()
    }

    fun resolveDeviceCode(
        remoteDeviceCode: String?,
        sharedPreferenceDeviceCode: String?,
        noBackupDeviceCode: String?,
        publicDeviceCode: String?,
        appConfigDeviceCode: String?,
        legacyCachedDeviceCode: String?,
        systemAndroidId: String?
    ): DeviceIdentityResolution {
        val candidates = listOf(
            IdentitySource.REMOTE_CONFIG to remoteDeviceCode,
            IdentitySource.SHARED_PREFERENCE to sharedPreferenceDeviceCode,
            IdentitySource.NO_BACKUP_FILE to noBackupDeviceCode,
            IdentitySource.PUBLIC_FILE to publicDeviceCode,
            IdentitySource.APP_CONFIG to appConfigDeviceCode,
            IdentitySource.LEGACY_CACHED_DEVICE_CODE to legacyCachedDeviceCode
        ).mapNotNull { (source, rawDeviceCode) ->
            val normalized = normalizeDeviceCode(rawDeviceCode) ?: return@mapNotNull null
            Candidate(
                source = source,
                rawDeviceCode = rawDeviceCode!!.trim(),
                normalizedDeviceCode = normalized
            )
        }

        val selected = candidates.firstOrNull()

        if (selected == null) {
            return DeviceIdentityResolution(
                deviceCode = null,
                source = IdentitySource.NONE,
                rawDeviceCode = null,
                systemAndroidIdIgnored = !systemAndroidId.isNullOrBlank()
            )
        }

        val rawAndroidId = systemAndroidId?.trim()?.lowercase().orEmpty()
        return DeviceIdentityResolution(
            deviceCode = selected.normalizedDeviceCode,
            source = selected.source,
            rawDeviceCode = selected.rawDeviceCode,
            systemAndroidIdIgnored = rawAndroidId.isNotBlank() &&
                rawAndroidId != selected.normalizedDeviceCode
        )
    }
}
