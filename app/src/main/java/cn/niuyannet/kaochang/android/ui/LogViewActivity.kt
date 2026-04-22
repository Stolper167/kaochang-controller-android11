package cn.niuyannet.kaochang.android.ui

import android.R as AndroidR
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import cn.niuyannet.kaochang.android.databinding.ActivityLogViewBinding
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewBinding
    private lateinit var logFiles: List<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener {
            finish()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "日志管理"

        loadLogFiles()

        binding.btnClearLogs.setOnClickListener {
            Toast.makeText(this, "已屏蔽日志清理按钮", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLogFiles() {
        val logDir = LogUtils.getLogDirectory(this)
        logFiles = logDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        updateLogFilesList()
    }

    private fun updateLogFilesList() {
        val logItems = logFiles.map { file ->
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))
            val size = String.format("%.2f", file.length() / 1024.0) + " KB"
            "${file.name} - $date - $size"
        }

        val adapter = ArrayAdapter(this, AndroidR.layout.simple_list_item_1, logItems)
        binding.lvLogFiles.adapter = adapter

        binding.lvLogFiles.setOnItemClickListener { _, _, position, _ ->
            viewLogFile(logFiles[position])
        }
    }

    private fun viewLogFile(file: File) {
        val intent = Intent(this, LogDetailActivity::class.java)
        intent.putExtra("log_file_path", file.absolutePath)
        startActivity(intent)
    }

    private fun shareLatestLog() {
        val latestLog = LogUtils.getLatestLogFile(this)
        if (latestLog != null && latestLog.exists()) {
            try {
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    latestLog
                )

                val shareIntent = Intent(Intent.ACTION_SEND)
                shareIntent.type = "text/plain"
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                startActivity(Intent.createChooser(shareIntent, "分享日志文件"))
            } catch (e: Exception) {
                Toast.makeText(this, "无法分享日志文件: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "没有可用的日志文件", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == AndroidR.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
