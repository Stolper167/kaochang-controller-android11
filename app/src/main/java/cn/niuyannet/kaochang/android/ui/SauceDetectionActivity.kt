package cn.niuyannet.kaochang.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.databinding.ActivitySauceDetectionBinding
import cn.niuyannet.kaochang.android.detecition.ArucoHelper
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.detecition.SauceDetector
import cn.niuyannet.kaochang.android.services.CameraService
import cn.niuyannet.kaochang.android.utils.LogUtils
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

class SauceDetectionActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var binding: ActivitySauceDetectionBinding
    private lateinit var processor: SauceDetectionProcessor
    private lateinit var arucoHelper: ArucoHelper

    private var roiFlag = 0
    private var cameraService: CameraService? = null
    private var isServiceBound = false
    @Volatile
    private var captureInFlight = false

    private var lastSummary: String = "尚未执行检测"
    private var lastFailure: String = "无"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? CameraService.ClientBinder
            cameraService = binder?.service
            isServiceBound = cameraService != null
            renderIdleState("相机服务已连接，可执行按需拍照")
            LogUtils.d("【视觉调试】SauceDetectionActivity 已绑定 CameraService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isServiceBound = false
            updateStatusText("相机服务：已断开")
            lastFailure = "相机服务已断开"
            LogUtils.w("【视觉调试】SauceDetectionActivity 的 CameraService 已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySauceDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        processor = SauceDetectionProcessor(this)
        arucoHelper = ArucoHelper(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.transportButton.setOnClickListener {
            roiFlag = 0
            processor.roiFlag = roiFlag
            updateModeButtons()
            renderIdleState("已切换到运输台检测模式")
        }
        binding.salesButton.setOnClickListener {
            roiFlag = 1
            processor.roiFlag = roiFlag
            updateModeButtons()
            renderIdleState("已切换到售卖台检测模式")
        }
        binding.arUcoButton.setOnClickListener { captureAndDetectAruco() }
        binding.detectButton.setOnClickListener { captureAndDetectSausage() }
        binding.btnResetView.setOnClickListener { renderIdleState("已返回待拍照状态") }
        binding.btnRebindCamera.setOnClickListener { rebindCameraService() }

        updateModeButtons()
        renderIdleState("页面已就绪，点击下方按钮执行抓拍识别")

        if (allPermissionsGranted()) {
            bindCameraServiceIfNeeded()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            bindCameraServiceIfNeeded()
        }
    }

    override fun onPause() {
        super.onPause()
        releaseAndUnbindCameraService()
    }

    override fun onDestroy() {
        releaseAndUnbindCameraService()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                bindCameraServiceIfNeeded()
            } else {
                Toast.makeText(this, "需要相机权限才能使用视觉调试页", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun bindCameraServiceIfNeeded() {
        if (isServiceBound) {
            updateStatusText("相机服务：已连接")
            return
        }
        updateStatusText("相机服务：连接中...")
        bindService(Intent(this, CameraService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun rebindCameraService() {
        updateStatusText("相机服务：正在重绑...")
        releaseAndUnbindCameraService()
        bindCameraServiceIfNeeded()
    }

    private fun releaseAndUnbindCameraService() {
        if (!isServiceBound) {
            cameraService = null
            return
        }
        cameraService?.releaseNow(Runnable { safeUnbindCameraService() }) ?: safeUnbindCameraService()
    }

    private fun safeUnbindCameraService() {
        if (!isServiceBound) {
            return
        }
        try {
            unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
        }
        isServiceBound = false
        cameraService = null
        updateStatusText("相机服务：未连接")
        LogUtils.d("【视觉调试】SauceDetectionActivity 已解绑 CameraService")
    }

    private fun updateModeButtons() {
        val transportSelected = roiFlag == 0
        binding.transportButton.isEnabled = !transportSelected
        binding.transportButton.alpha = if (transportSelected) 0.5f else 1f
        binding.salesButton.isEnabled = transportSelected
        binding.salesButton.alpha = if (transportSelected) 1f else 0.5f
    }

    private fun renderIdleState(extraMessage: String? = null) {
        binding.previewView.visibility = View.VISIBLE
        binding.imageView.visibility = View.GONE
        binding.detectButton.text = "烤肠检测"
        binding.arUcoButton.text = "ArUco 标定检测"
        binding.resultTextView.text = buildResultBlock(
            title = "视觉调试页待命中",
            summaryLines = listOf(
                "当前模式：${currentModeLabel()}",
                "抓拍策略：按需拍照，不维持独立实时预览",
                "最近结果：$lastSummary"
            ),
            detailLines = buildList {
                add("调试图目录：${SauceDetector.getDebugImageDirPath()}")
                add("最近失败原因：$lastFailure")
                if (!extraMessage.isNullOrBlank()) {
                    add(extraMessage)
                }
            }
        )
        updateStatusText(if (isServiceBound) "相机服务：已连接" else "相机服务：未连接")
    }

    private fun updateStatusText(message: String) {
        binding.statusTextView.text = buildString {
            append(message)
            append('\n')
            append("当前模式：").append(currentModeLabel())
            append(" | 最近结果：").append(lastSummary)
            if (captureInFlight) {
                append(" | 抓拍中")
            }
        }
    }

    private fun currentModeLabel(): String = if (roiFlag == 0) "运输台" else "售卖台"

    private fun captureAndDetectAruco() {
        Toast.makeText(this, "正在拍照并执行 ArUco 检测...", Toast.LENGTH_SHORT).show()
        updateStatusText("ArUco：抓拍中...")
        LogUtils.d("【视觉调试】开始 ArUco 检测，mode=${currentModeLabel()}")
        captureBitmapWithPrewarm(
            onSuccess = { bitmap ->
                runOnUiThread {
                    binding.previewView.visibility = View.GONE
                    binding.imageView.visibility = View.VISIBLE
                    try {
                        val result = arucoHelper.detectArucoMarkers(bitmap, 1)
                        binding.imageView.setImageBitmap(result.resultImage ?: bitmap)
                        lastSummary = if (result.targetFound) {
                            "ArUco 成功，已找到目标标记"
                        } else {
                            "ArUco 完成，但未找到目标标记"
                        }
                        lastFailure = "无"
                        binding.resultTextView.text = buildResultBlock(
                            title = "ArUco 标定检测",
                            summaryLines = listOf(
                                "执行结果：$lastSummary",
                                "检测到标记数：${result.markersCount}",
                                "标记 ID：${if (result.markerIds.isEmpty()) "无" else result.markerIds.joinToString()}"
                            ),
                            detailLines = buildList {
                                add("当前模式：${currentModeLabel()}")
                                add("目标标记(ID=1)：${if (result.targetFound) "已找到" else "未找到"}")
                                if (result.targetCorners.isNotEmpty()) {
                                    add("角点坐标：")
                                    result.targetCorners.take(4).forEachIndexed { index, point ->
                                        if (point.size >= 2) {
                                            add(
                                                "  ${index + 1}. (%.1f, %.1f)".format(
                                                    point[0],
                                                    point[1]
                                                )
                                            )
                                        }
                                    }
                                }
                                add("调试图目录：${SauceDetector.getDebugImageDirPath()}")
                                add("最近失败原因：$lastFailure")
                            }
                        )
                        updateStatusText("ArUco：检测完成")
                    } catch (e: Exception) {
                        lastSummary = "ArUco 检测失败"
                        lastFailure = e.message ?: "未知异常"
                        LogUtils.e("【视觉调试】ArUco 检测失败：${e.message}", e)
                        binding.imageView.setImageBitmap(bitmap)
                        binding.resultTextView.text = buildFailureBlock(
                            title = "ArUco 标定检测",
                            failure = lastFailure
                        )
                        updateStatusText("ArUco：检测失败")
                    }
                }
            },
            onError = { message ->
                runOnUiThread {
                    lastSummary = "ArUco 抓拍失败"
                    lastFailure = message
                    binding.resultTextView.text = buildFailureBlock(
                        title = "ArUco 标定检测",
                        failure = message
                    )
                    updateStatusText("ArUco：抓拍失败")
                    Toast.makeText(this, "ArUco 抓拍失败：$message", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun captureAndDetectSausage() {
        Toast.makeText(this, "正在拍照并执行烤肠识别...", Toast.LENGTH_SHORT).show()
        updateStatusText("烤肠识别：抓拍中...")
        LogUtils.d("【视觉调试】开始烤肠识别，mode=${currentModeLabel()}")
        captureBitmapWithPrewarm(
            onSuccess = { bitmap ->
                val mat = bitmapToBgrMat(bitmap)
                if (mat == null) {
                    runOnUiThread {
                        lastSummary = "烤肠识别失败"
                        lastFailure = "图像转换失败"
                        binding.resultTextView.text = buildFailureBlock(
                            title = "烤肠识别",
                            failure = lastFailure
                        )
                        updateStatusText("烤肠识别：图像转换失败")
                        Toast.makeText(this, "图像转换失败", Toast.LENGTH_SHORT).show()
                    }
                    return@captureBitmapWithPrewarm
                }

                try {
                    val result = processor.processImage(mat)
                    runOnUiThread {
                        binding.previewView.visibility = View.GONE
                        binding.imageView.visibility = View.VISIBLE
                        displayImage(result.processedImage ?: mat, bitmap)
                        lastSummary = if (result.sausages.isEmpty()) {
                            "烤肠识别完成，未检测到烤肠"
                        } else {
                            "烤肠识别完成，检测到 ${result.sausages.size} 根"
                        }
                        lastFailure = "无"
                        binding.resultTextView.text = buildResultBlock(
                            title = "烤肠识别",
                            summaryLines = listOf(
                                "执行结果：$lastSummary",
                                "当前模式：${currentModeLabel()}",
                                "识别数量：${result.sausages.size}"
                            ),
                            detailLines = buildList {
                                if (result.sausages.isEmpty()) {
                                    add("未检测到烤肠")
                                } else {
                                    result.sausages.forEachIndexed { index, sausage ->
                                        add(
                                            "#${index + 1} 像素坐标=(${sausage.pixelX}, ${sausage.pixelY})"
                                        )
                                        add(
                                            "   世界坐标=(%.2f, %.2f, %.2f)".format(
                                                sausage.worldX,
                                                sausage.worldY,
                                                sausage.worldZ
                                            )
                                        )
                                        add("   识别码=${sausage.code}")
                                    }
                                }
                                add("调试图目录：${SauceDetector.getDebugImageDirPath()}")
                                add("最近失败原因：$lastFailure")
                            }
                        )
                        updateStatusText("烤肠识别：执行完成")
                    }
                    if (result.processedImage != null && result.processedImage !== mat) {
                        result.processedImage.release()
                    }
                } catch (e: Exception) {
                    lastSummary = "烤肠识别失败"
                    lastFailure = e.message ?: "未知异常"
                    LogUtils.e("【视觉调试】烤肠识别失败：${e.message}", e)
                    runOnUiThread {
                        binding.previewView.visibility = View.GONE
                        binding.imageView.visibility = View.VISIBLE
                        binding.imageView.setImageBitmap(bitmap)
                        binding.resultTextView.text = buildFailureBlock(
                            title = "烤肠识别",
                            failure = lastFailure
                        )
                        updateStatusText("烤肠识别：执行失败")
                    }
                } finally {
                    mat.release()
                }
            },
            onError = { message ->
                runOnUiThread {
                    lastSummary = "烤肠识别抓拍失败"
                    lastFailure = message
                    binding.resultTextView.text = buildFailureBlock(
                        title = "烤肠识别",
                        failure = message
                    )
                    updateStatusText("烤肠识别：抓拍失败")
                    Toast.makeText(this, "抓拍失败：$message", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun buildResultBlock(
        title: String,
        summaryLines: List<String>,
        detailLines: List<String>
    ): String {
        return buildString {
            append(title).append('\n')
            append("=".repeat(title.length.coerceAtLeast(6)))
            summaryLines.forEach { line ->
                append('\n').append(line)
            }
            if (detailLines.isNotEmpty()) {
                append("\n\n详细信息")
                append('\n').append("--------")
                detailLines.forEach { line ->
                    append('\n').append(line)
                }
            }
        }
    }

    private fun buildFailureBlock(title: String, failure: String): String {
        return buildResultBlock(
            title = title,
            summaryLines = listOf(
                "执行结果：失败",
                "当前模式：${currentModeLabel()}",
                "最近失败原因：$failure"
            ),
            detailLines = listOf(
                "调试图目录：${SauceDetector.getDebugImageDirPath()}",
                "建议：先点“重绑相机”，再重试一次"
            )
        )
    }

    private fun displayImage(mat: Mat, fallbackBitmap: Bitmap) {
        try {
            val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bitmap)
            binding.imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            LogUtils.e("【视觉调试】显示图像失败：${e.message}", e)
            binding.imageView.setImageBitmap(fallbackBitmap)
        }
    }

    private fun captureBitmapWithPrewarm(
        onSuccess: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val boundService = cameraService
        if (boundService == null || !isServiceBound) {
            bindCameraServiceIfNeeded()
            onError("相机服务尚未就绪，请稍后重试")
            return
        }
        if (captureInFlight) {
            onError("相机正在处理中，请稍后再试")
            return
        }
        captureInFlight = true
        boundService.prewarm(1_500L) { success ->
            if (!success) {
                captureInFlight = false
                onError("相机预热失败，请稍后重试")
                return@prewarm
            }
            boundService.takePhoto(object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        if (bitmap == null) {
                            onError("图像转换失败")
                        } else {
                            onSuccess(bitmap)
                        }
                    } catch (e: Exception) {
                        LogUtils.e("【视觉调试】抓拍成功后处理图像失败：${e.message}", e)
                        onError("图像处理失败：${e.message}")
                    } finally {
                        captureInFlight = false
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureInFlight = false
                    LogUtils.e("【视觉调试】抓拍失败：${exception.message}", exception)
                    onError(exception.message ?: "未知错误")
                }
            })
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val bitmap = when (image.format) {
                ImageFormat.JPEG -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                ImageFormat.YUV_420_888 -> {
                    val nv21 = yuv420888ToNv21(image)
                    val output = ByteArrayOutputStream()
                    val yuvImage = YuvImage(
                        nv21,
                        ImageFormat.NV21,
                        image.width,
                        image.height,
                        null
                    )
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, output)
                    BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())
                }

                else -> null
            } ?: return null

            val rotation = image.imageInfo.rotationDegrees
            if (rotation == 0) {
                bitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        } catch (e: Exception) {
            LogUtils.e("【视觉调试】ImageProxy 转 Bitmap 失败：${e.message}", e)
            null
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var position = 0
        repeat(height) { row ->
            val rowOffset = row * yPlane.rowStride
            repeat(width) { col ->
                nv21[position++] = yPlane.buffer.get(rowOffset + col * yPlane.pixelStride)
            }
        }

        repeat(height / 2) { row ->
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            repeat(width / 2) { col ->
                nv21[position++] = vPlane.buffer.get(vRowOffset + col * vPlane.pixelStride)
                nv21[position++] = uPlane.buffer.get(uRowOffset + col * uPlane.pixelStride)
            }
        }
        return nv21
    }

    private fun bitmapToBgrMat(bitmap: Bitmap): Mat? {
        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            when (mat.channels()) {
                4 -> {
                    val bgr = Mat()
                    Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
                    mat.release()
                    bgr
                }

                1 -> {
                    val bgr = Mat()
                    Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_GRAY2BGR)
                    mat.release()
                    bgr
                }

                else -> mat
            }.also {
                if (SauceDetector.SAVE_DEBUG_IMAGES) {
                    SauceDetector.saveDebugImage(it, "visual_debug_capture")
                }
            }
        } catch (e: Exception) {
            LogUtils.e("【视觉调试】Bitmap 转 Mat 失败：${e.message}", e)
            null
        }
    }
}
