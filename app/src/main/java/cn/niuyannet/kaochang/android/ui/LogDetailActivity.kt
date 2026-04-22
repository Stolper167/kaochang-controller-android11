package cn.niuyannet.kaochang.android.ui

import android.R as AndroidR
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import cn.niuyannet.kaochang.android.databinding.ActivityLogDetailBinding
import java.io.File
import java.io.RandomAccessFile

class LogDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener {
            finish()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "日志详情"

        val logFilePath = intent.getStringExtra("log_file_path")
        if (logFilePath != null) {
            val file = File(logFilePath)
            if (file.exists()) {
                val previewBytes = 200 * 1024L
                val fileNameSuffix = if (file.length() > previewBytes) {
                    "（仅显示最后 200KB）"
                } else {
                    ""
                }
                binding.tvLogFileName.text = "${file.name}$fileNameSuffix"
                binding.tvLogContent.text = readTailText(file, previewBytes)
            }
        }
    }

    private fun readTailText(file: File, maxBytes: Long): String {
        if (file.length() <= maxBytes) {
            return file.readText()
        }

        return RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            val content = bytes.toString(Charsets.UTF_8)
            val firstLineBreak = content.indexOf('\n')
            if (firstLineBreak >= 0 && firstLineBreak < content.length - 1) {
                content.substring(firstLineBreak + 1)
            } else {
                content
            }
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
