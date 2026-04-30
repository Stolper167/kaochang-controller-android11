package cn.niuyannet.kaochang.android.init

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object DeviceIdentityManager {
    private const val KEY_STABLE_DEVICE_CODE = "sp_device_code"
    private const val KEY_LEGACY_CACHED_DEVICE_CODE = "sp_android_id"
    private const val KEY_IDENTITY_JSON = "sp_device_identity_json"
    private const val KEY_IDENTITY_INSTALL_ID = "sp_identity_install_id"
    private const val KEY_APP_CONFIG_JSON = "sp_app_config_json"
    private const val NO_BACKUP_FILE_NAME = "kaochang_device_identity.json"
    private const val PUBLIC_DIR_NAME = "KaoChang"
    private const val PUBLIC_FILE_NAME = "device_identity.json"

    @Volatile
    private var lastLoggedSignature = ""
    @Volatile
    private var lastResolvedPersistedDeviceCode = ""

    data class DeviceIdentitySnapshot(
        val deviceCode: String?,
        val source: DeviceIdentityPolicy.IdentitySource,
        val rawDeviceCode: String?,
        val systemAndroidId: String?,
        val systemAndroidIdIgnored: Boolean,
        val identityInstallId: String,
        val versionName: String,
        val signatureSha256: String,
        val manufacturer: String,
        val brand: String,
        val model: String,
        val device: String,
        val product: String
    )

    fun getDeviceCode(): String {
        return resolveAndPersistIdentity(reason = "getDeviceId").deviceCode.orEmpty()
    }

    fun hasStableDeviceCode(): Boolean {
        return resolveAndPersistIdentity(reason = "hasStableDeviceCode").deviceCode?.isNotBlank() == true
    }

    fun saveDeviceCode(deviceCode: String?, reason: String): String? {
        val normalizedDeviceCode = DeviceIdentityPolicy.normalizeDeviceCode(deviceCode)
        if (normalizedDeviceCode.isNullOrBlank()) {
            LogUtils.w("【设备身份】拒绝保存空设备编号：reason=$reason")
            return null
        }
        persistIdentity(
            deviceCode = normalizedDeviceCode,
            rawDeviceCode = deviceCode?.trim(),
            source = reason,
            force = true
        )
        return normalizedDeviceCode
    }

    fun readIdentitySnapshot(): DeviceIdentitySnapshot {
        return resolveAndPersistIdentity(reason = "readIdentitySnapshot")
    }

    fun buildIdentityApplyPayload(candidateDeviceCode: String?, activationCode: String?, remark: String?): JSONObject {
        val snapshot = readIdentitySnapshot()
        return JSONObject().apply {
            put("candidateDeviceCode", DeviceIdentityPolicy.normalizeDeviceCode(candidateDeviceCode) ?: "")
            put("activationCode", activationCode ?: "")
            put("systemAndroidId", snapshot.systemAndroidId ?: "")
            put("identityInstallId", snapshot.identityInstallId)
            put("signatureSha256", snapshot.signatureSha256)
            put("versionName", snapshot.versionName)
            put("manufacturer", snapshot.manufacturer)
            put("brand", snapshot.brand)
            put("model", snapshot.model)
            put("device", snapshot.device)
            put("product", snapshot.product)
            put("remark", remark ?: "")
        }
    }

    fun logIdentity(reason: String, force: Boolean = false) {
        val snapshot = readIdentitySnapshot()
        val signature = listOf(
            reason,
            snapshot.deviceCode.orEmpty(),
            snapshot.source.name,
            snapshot.systemAndroidId.orEmpty(),
            snapshot.systemAndroidIdIgnored
        ).joinToString("|")
        if (!force && signature == lastLoggedSignature) {
            return
        }
        lastLoggedSignature = signature

        LogUtils.i(
            "【设备身份】$reason：" +
                "deviceCode（云端设备编号）=${snapshot.deviceCode ?: "未锁定"}，" +
                "identitySource（身份来源）=${identitySourceText(snapshot.source)}，" +
                "systemAndroidId（系统 ANDROID_ID）=${snapshot.systemAndroidId ?: "读取失败"}，" +
                "identityInstallId（本机安装/身份申请指纹）=${snapshot.identityInstallId}，" +
                "versionName（App版本名）=${snapshot.versionName}，" +
                "signatureSha256（安装签名摘要）=${snapshot.signatureSha256}"
        )
        if (snapshot.systemAndroidIdIgnored) {
            if (snapshot.deviceCode.isNullOrBlank()) {
                LogUtils.w(
                    "【设备身份】deviceCode（云端设备编号）未锁定，" +
                        "已忽略 systemAndroidId（系统 ANDROID_ID）兜底：systemAndroidId=${snapshot.systemAndroidId ?: "读取失败"}"
                )
            } else {
                LogUtils.w(
                    "【设备身份】检测到 systemAndroidId（系统 ANDROID_ID）与锁定 deviceCode（云端设备编号）不一致，" +
                        "已忽略系统 ID 变化：deviceCode=${snapshot.deviceCode}，" +
                        "systemAndroidId=${snapshot.systemAndroidId ?: "读取失败"}"
                )
            }
        }
    }

    @Synchronized
    private fun resolveAndPersistIdentity(reason: String): DeviceIdentitySnapshot {
        val context = MyApp.instance().applicationContext
        val systemAndroidId = getSystemAndroidId(context)
        val resolution = DeviceIdentityPolicy.resolveDeviceCode(
            remoteDeviceCode = null,
            sharedPreferenceDeviceCode = PreferenceUtils.getStringPreference(
                AppConfig.Sp_File_URL,
                KEY_STABLE_DEVICE_CODE,
                null
            ),
            noBackupDeviceCode = readDeviceCodeFromFile(noBackupIdentityFile(context)),
            publicDeviceCode = readDeviceCodeFromFile(publicIdentityFile()),
            appConfigDeviceCode = readDeviceCodeFromAppConfigJson(),
            legacyCachedDeviceCode = PreferenceUtils.getStringPreference(
                AppConfig.Sp_File_URL,
                KEY_LEGACY_CACHED_DEVICE_CODE,
                null
            ),
            systemAndroidId = systemAndroidId
        )

        if (!resolution.deviceCode.isNullOrBlank()) {
            persistIdentity(
                deviceCode = resolution.deviceCode,
                rawDeviceCode = resolution.rawDeviceCode,
                source = "resolve:$reason:${resolution.source.name}",
                force = false
            )
        }

        return DeviceIdentitySnapshot(
            deviceCode = resolution.deviceCode,
            source = resolution.source,
            rawDeviceCode = resolution.rawDeviceCode,
            systemAndroidId = systemAndroidId,
            systemAndroidIdIgnored = resolution.systemAndroidIdIgnored,
            identityInstallId = getOrCreateIdentityInstallId(context),
            versionName = readVersionName(context),
            signatureSha256 = readSignatureSha256(context),
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            product = Build.PRODUCT ?: ""
        )
    }

    private fun persistIdentity(deviceCode: String, rawDeviceCode: String?, source: String, force: Boolean) {
        if (!force && lastResolvedPersistedDeviceCode == deviceCode) {
            return
        }
        val context = MyApp.instance().applicationContext
        val systemAndroidId = getSystemAndroidId(context)
        val payload = JSONObject().apply {
            put("deviceCode", deviceCode)
            put("rawDeviceCode", rawDeviceCode ?: deviceCode)
            put("systemAndroidId", systemAndroidId ?: "")
            put("identityInstallId", getOrCreateIdentityInstallId(context))
            put("source", source)
            put("updatedAt", System.currentTimeMillis())
        }.toJSONString()

        PreferenceUtils.saveStringPreference(AppConfig.Sp_File_URL, KEY_STABLE_DEVICE_CODE, deviceCode)
        PreferenceUtils.saveStringPreference(AppConfig.Sp_File_URL, KEY_LEGACY_CACHED_DEVICE_CODE, deviceCode)
        PreferenceUtils.saveStringPreference(AppConfig.Sp_File_URL, KEY_IDENTITY_JSON, payload)
        writeIdentityFile(noBackupIdentityFile(context), payload, "noBackup")
        writeIdentityFile(publicIdentityFile(), payload, "public")
        lastResolvedPersistedDeviceCode = deviceCode
    }

    private fun readDeviceCodeFromAppConfigJson(): String? {
        val raw = PreferenceUtils.getStringPreference(AppConfig.Sp_File_URL, KEY_APP_CONFIG_JSON, null)
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            JSON.parseObject(raw)?.getString("deviceCode")
        } catch (_: Exception) {
            null
        }
    }

    private fun readDeviceCodeFromFile(file: File): String? {
        return readStringFieldFromFile(file, "deviceCode")
    }

    private fun readStringFieldFromFile(file: File, fieldName: String): String? {
        return try {
            if (!file.exists() || !file.isFile) {
                return null
            }
            JSON.parseObject(file.readText(Charsets.UTF_8))?.getString(fieldName)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeIdentityFile(file: File, payload: String, label: String) {
        try {
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            file.writeText(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            LogUtils.w("【设备身份】写入${label}身份副本失败：path=${file.absolutePath}，error=${e.message}")
        }
    }

    private fun noBackupIdentityFile(context: Context): File {
        return File(context.noBackupFilesDir, NO_BACKUP_FILE_NAME)
    }

    private fun publicIdentityFile(): File {
        return File(File(Environment.getExternalStorageDirectory(), PUBLIC_DIR_NAME), PUBLIC_FILE_NAME)
    }

    private fun getSystemAndroidId(context: Context): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateIdentityInstallId(context: Context): String {
        val candidates = listOf(
            PreferenceUtils.getStringPreference(AppConfig.Sp_File_URL, KEY_IDENTITY_INSTALL_ID, null),
            readStringFieldFromFile(noBackupIdentityFile(context), "identityInstallId"),
            readStringFieldFromFile(publicIdentityFile(), "identityInstallId")
        ).mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }

        val selected = candidates.firstOrNull() ?: UUID.randomUUID().toString().replace("-", "")
        PreferenceUtils.saveStringPreference(AppConfig.Sp_File_URL, KEY_IDENTITY_INSTALL_ID, selected)
        ensureIdentityInstallIdCopies(context, selected)
        return selected
    }

    private fun ensureIdentityInstallIdCopies(context: Context, identityInstallId: String) {
        val systemAndroidId = getSystemAndroidId(context) ?: ""
        listOf(noBackupIdentityFile(context), publicIdentityFile()).forEach { file ->
            if (readStringFieldFromFile(file, "identityInstallId").isNullOrBlank()) {
                val payload = JSONObject().apply {
                    put("deviceCode", readDeviceCodeFromFile(file) ?: "")
                    put("identityInstallId", identityInstallId)
                    put("systemAndroidId", systemAndroidId)
                    put("source", "identity_install_id")
                    put("updatedAt", System.currentTimeMillis())
                }.toJSONString()
                writeIdentityFile(file, payload, "identityInstallId")
            }
        }
    }

    private fun readVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun readSignatureSha256(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signatureBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            } ?: return "unknown"
            DeviceIdentityPolicy.bytesToHex(MessageDigest.getInstance("SHA-256").digest(signatureBytes))
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun identitySourceText(source: DeviceIdentityPolicy.IdentitySource): String {
        return when (source) {
            DeviceIdentityPolicy.IdentitySource.REMOTE_CONFIG -> "REMOTE_CONFIG（远端配置）"
            DeviceIdentityPolicy.IdentitySource.SHARED_PREFERENCE -> "SHARED_PREFERENCE（本地SharedPreferences）"
            DeviceIdentityPolicy.IdentitySource.NO_BACKUP_FILE -> "NO_BACKUP_FILE（no-backup内部身份文件）"
            DeviceIdentityPolicy.IdentitySource.PUBLIC_FILE -> "PUBLIC_FILE（公共持久身份文件）"
            DeviceIdentityPolicy.IdentitySource.APP_CONFIG -> "APP_CONFIG（本地设备配置缓存）"
            DeviceIdentityPolicy.IdentitySource.LEGACY_CACHED_DEVICE_CODE -> "LEGACY_CACHED_DEVICE_CODE（旧版缓存键）"
            DeviceIdentityPolicy.IdentitySource.NONE -> "NONE（未锁定）"
        }
    }
}
