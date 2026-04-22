package cn.niuyannet.kaochang.android.utils

import android.content.Context
import android.util.Log
import cn.niuyannet.kaochang.android.MyApp
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object LogUtils {
    private const val TAG = "KaoChang"
    private val FILE_LOG_KEYWORDS = listOf(
        "【订单防漏跟踪】",
        "【订单流转】",
        "【履约完结】",
        "【维护操作】",
        "【云端同步】",
        "【丢弃流程】",
        "【并发切换】",
        "【动作执行】",
        "【售卖口监控】",
        "【窗口收尾】",
        "【补肠流程】",
        "【烤盘搬移】",
        "【设备状态】",
        "【自清洁】",
        "【Modbus写指令】"
    )
    private val FILE_LOG_KEYWORDS_FOR_HOME_ESTIMATE_DEBUG = listOf(
        "【首页展示】",
        "【MQTT运行态】",
        "更新二维码预计时间",
        "【MQTT展示态】",
        "算法开关已开启",
        "算法开关已关闭",
        "智能烤肠算法服务启动",
        "算法状态变化",
        "手动开启烤肠算法时检测到轮询未运行",
        "【MQTT业务】收到下发订单",
        "【MQTT业务】收到设备状态下发",
        "【MQTT业务】收到运行时状态回执",
        "【MQTT业务】收到首页展示态回执"
    )

    const val LEVEL_VERBOSE = 1
    const val LEVEL_DEBUG = 2
    const val LEVEL_INFO = 3
    const val LEVEL_WARN = 4
    const val LEVEL_ERROR = 5
    const val LEVEL_NONE = 6

    private var currentLevel = LEVEL_VERBOSE
    private var writeToFile = true

    private const val LOG_FILE_RETENTION_DAYS = 7

    // Keep each file small enough to inspect directly on the device.
    private const val MAX_FILE_SIZE = 200 * 1024

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentLogFile: File? = null

    fun init(context: Context, level: Int = LEVEL_VERBOSE, writeToFile: Boolean = true) {
        this.currentLevel = level
        this.writeToFile = writeToFile

        if (writeToFile) {
            cleanOldLogFiles(context)
            getLogDir(context)
        }
    }

    private fun getLogDir(context: Context): File {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }

    private fun getCurrentLogFile(context: Context): File {
        val today = dateFormat.format(Date())
        val logDir = getLogDir(context)

        if (currentLogFile == null || !currentLogFile!!.name.contains(today)) {
            val fileIndex = getNextFileIndex(logDir, today)
            currentLogFile = File(logDir, "${today}_${fileIndex}.log")
        }

        if (currentLogFile!!.exists() && currentLogFile!!.length() > MAX_FILE_SIZE) {
            val fileIndex = getNextFileIndex(logDir, today)
            currentLogFile = File(logDir, "${today}_${fileIndex}.log")
        }

        return currentLogFile!!
    }

    private fun getNextFileIndex(logDir: File, today: String): Int {
        val existingLogs = logDir.listFiles { file ->
            file.name.startsWith(today) && file.name.endsWith(".log")
        } ?: return 1

        return existingLogs.size + 1
    }

    private fun cleanOldLogFiles(context: Context) {
        logExecutor.execute {
            try {
                val logDir = getLogDir(context)
                val now = System.currentTimeMillis()
                val retentionTime = now - (LOG_FILE_RETENTION_DAYS * 24 * 60 * 60 * 1000L)

                logDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < retentionTime) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理旧日志文件失败", e)
            }
        }
    }

    private fun writeLogToFile(context: Context, level: String, tag: String, msg: String, tr: Throwable? = null) {
        if (!writeToFile) return
        if (!shouldWriteToFile(level, msg, tr)) return

        logExecutor.execute {
            try {
                val logFile = getCurrentLogFile(context)
                val timestamp = timeFormat.format(Date())
                val logMessage = StringBuilder()
                    .append(timestamp)
                    .append(" ")
                    .append(level)
                    .append("/")
                    .append(tag)
                    .append(": ")
                    .append(msg)

                if (tr != null) {
                    logMessage.append("\n").append(Log.getStackTraceString(tr))
                }

                FileWriter(logFile, true).use { writer ->
                    writer.append(logMessage).append("\n")
                }
            } catch (e: IOException) {
                Log.e(TAG, "写入日志文件失败", e)
            }
        }
    }

    private fun shouldWriteToFile(level: String, msg: String, tr: Throwable?): Boolean {
        if (tr != null) {
            return true
        }
        if (level == "W" || level == "E") {
            return true
        }
        return FILE_LOG_KEYWORDS.any { keyword -> msg.contains(keyword) } ||
            FILE_LOG_KEYWORDS_FOR_HOME_ESTIMATE_DEBUG.any { keyword -> msg.contains(keyword) }
    }

    fun getLogDirectory(context: Context): File = getLogDir(context)

    fun getLatestLogFile(context: Context): File? {
        val logDir = getLogDir(context)
        return logDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }

    fun clearAllLogs(context: Context) {
        val logDir = getLogDir(context)
        logExecutor.execute {
            logDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".log")) {
                    file.delete()
                }
            }
        }
    }

    fun v(tag: String, msg: String) {
        if (currentLevel <= LEVEL_VERBOSE) {
            Log.v(tag, msg)
            writeLogToFile(MyApp.instance(), "V", tag, msg)
        }
    }

    fun d(tag: String, msg: String) {
        if (currentLevel <= LEVEL_DEBUG) {
            Log.d(tag, msg)
            writeLogToFile(MyApp.instance(), "D", tag, msg)
        }
    }

    fun i(tag: String, msg: String) {
        if (currentLevel <= LEVEL_INFO) {
            Log.i(tag, msg)
            writeLogToFile(MyApp.instance(), "I", tag, msg)
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (currentLevel <= LEVEL_WARN) {
            if (tr != null) {
                Log.w(tag, msg, tr)
            } else {
                Log.w(tag, msg)
            }
            writeLogToFile(MyApp.instance(), "W", tag, msg, tr)
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (currentLevel <= LEVEL_ERROR) {
            if (tr != null) {
                Log.e(tag, msg, tr)
            } else {
                Log.e(tag, msg)
            }
            writeLogToFile(MyApp.instance(), "E", tag, msg, tr)
        }
    }

    fun v(msg: String) = v(TAG, msg)
    fun d(msg: String) = d(TAG, msg)
    fun i(msg: String) = i(TAG, msg)
    fun w(msg: String, tr: Throwable? = null) = w(TAG, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = e(TAG, msg, tr)
}
