package cn.niuyannet.kaochang.android.utils

import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import cn.niuyannet.kaochang.android.MyApp.Companion.instance
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.detecition.SauceDetector
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.services.CameraMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.core.Mat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 售卖口视觉识别工具。
 *
 * 当前主链路使用后台拍照服务统一排队拍照与识别；
 * 旧版 CameraX 直拍路径仅保留作兼容兜底，避免历史流程直接失效。
 */
object SauceDetectionUitls {
    private const val DETECT_LOG_WINDOW_MS = 10_000L
    private val recentDetectWarnAtMs = ConcurrentHashMap<String, Long>()

    private fun logDetectWarnThrottled(key: String, message: String) {
        val now = System.currentTimeMillis()
        val lastAt = recentDetectWarnAtMs[key] ?: 0L
        if (now - lastAt >= DETECT_LOG_WINDOW_MS) {
            recentDetectWarnAtMs[key] = now
            logDetectWarn(message)
        }
    }
    private fun logPhoto(tag: String, message: String) = LogUtils.d(tag, "【售卖口拍照】$message")
    private fun logPhotoError(tag: String, message: String) = LogUtils.e(tag, "【售卖口拍照】$message")
    private fun logDetect(message: String) = LogUtils.d("【售卖口识别】$message")
    private fun logDetectWarn(message: String) = LogUtils.w("【售卖口识别】$message")
    private fun logDetectError(message: String) = LogUtils.e("【售卖口识别】$message")

    // 售卖口识别处理器
    var processor: SauceDetectionProcessor? = null

    // 旧版 CameraX 兼容路径使用的对象，当前主链路已切到后台服务拍照
    var cameraProvider: ProcessCameraProvider? = null
    var imageCapture: ImageCapture? = null
    var camera: Camera? = null
    val executor: Executor = Executors.newSingleThreadExecutor()
    var lifecycleOwner: LifecycleOwner? = null

    private val captureMutex = Mutex()
    private val pendingRequests =
        ConcurrentHashMap<String, CompletableDeferred<SauceDetectionProcessor.SausageInfo?>>()
    private var forcedSellDetectCount = 0
    private var forcedSellDetectWindowStartMs = 0L

    private fun buildForcedSuccessResult(roiFlag: Int): SauceDetectionProcessor.SausageInfo {
        return SauceDetectionProcessor.SausageInfo().apply {
            pixelX = 0
            pixelY = 0
            worldX = 0.0
            worldY = if (roiFlag == 0) 80.0 else 0.0
            worldZ = 0.0
            code = 2
        }
    }

    private fun buildForcedSellEmptyResult(): SauceDetectionProcessor.SausageInfo {
        return SauceDetectionProcessor.SausageInfo().apply {
            pixelX = 0
            pixelY = 0
            worldX = 0.0
            worldY = 0.0
            worldZ = 0.0
            code = 1
        }
    }

    /**
     * 协程风格的拍照识别入口。
     *
     * `roiFlag`:
     * - 0：运输台
     * - 1：售卖口
     */
    suspend fun captureAndDetect(
        roiFlag: Int,
        retry: Int = 3,
        timeoutMs: Long = 20000,
        retryDelayMs: Long = 3000L,
        captureTimeoutMs: Long = 5000L,
        tag: String = "KaoChangAlgorithm"
    ): SauceDetectionProcessor.SausageInfo? = withContext(Dispatchers.IO) {
        if (AppConfig.getAppConfig().forceVisionSuccess == 1) {
            if (roiFlag == 0) {
                logDetectWarnThrottled(
                    "force_transport_success",
                    "联调模式已开启，运输台视觉识别固定返回成功：roiFlag=$roiFlag, forceVisionSuccess=1"
                )
                return@withContext buildForcedSuccessResult(roiFlag)
            }

            val now = System.currentTimeMillis()
            if (now - forcedSellDetectWindowStartMs > 3_000L) {
                forcedSellDetectWindowStartMs = now
                forcedSellDetectCount = 0
            }
            forcedSellDetectCount++

            if (forcedSellDetectCount <= 2) {
                logDetectWarnThrottled(
                    "force_sell_detected",
                    "联调模式已开启，售卖口视觉识别模拟为“检测到烤肠”：roiFlag=$roiFlag, countInWindow=$forcedSellDetectCount"
                )
                return@withContext buildForcedSuccessResult(roiFlag)
            }

            logDetectWarnThrottled(
                "force_sell_empty",
                "联调模式已开启，售卖口视觉识别模拟为“顾客已取走，窗口为空”：roiFlag=$roiFlag, countInWindow=$forcedSellDetectCount"
            )
            return@withContext buildForcedSellEmptyResult()
        }

        logPhoto(tag, "开始拍照检测，roiFlag=$roiFlag，最大重试次数=$retry")
        val requestId = "${tag}_${System.currentTimeMillis()}_${(0..1000).random()}"
        return@withContext try {
            withTimeoutOrNull(timeoutMs) {
                val deferred = CompletableDeferred<SauceDetectionProcessor.SausageInfo?>()
                pendingRequests[requestId] = deferred
                try {
                    captureMutex.withLock {
                        logPhoto(tag, "已获取拍照锁，准备进入拍照队列")
                        suspendCancellableCoroutine { continuation ->
                            CameraMonitor.instance.takePhotoQueue(
                                roiFlag,
                                retryDelayMs,
                                captureTimeoutMs,
                                retry,
                                tag != "lunXun"
                            ) { sausageInfo ->
                                logPhoto(tag, "拍照检测回调完成，是否识别到结果=${sausageInfo != null}")
                                pendingRequests.remove(requestId)?.let { deferredRef ->
                                    if (deferredRef.isActive) {
                                        deferredRef.complete(sausageInfo)
                                    }
                                }
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }
                            continuation.invokeOnCancellation {
                                logPhoto(tag, "拍照任务被取消，已清理等待回调")
                                pendingRequests.remove(requestId)
                            }
                        }
                    }
                    val result = deferred.await()
                    logPhoto(tag, "收到拍照结果，是否为空=${result == null}")
                    result
                } finally {
                    pendingRequests.remove(requestId)
                }
            }
        } catch (_: TimeoutCancellationException) {
            logPhotoError(tag, "拍照超时，耗时超过 ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            logPhotoError(tag, "拍照异常：${e.message}")
            null
        }
    }

    /**
     * 旧版 CameraX 拍照路径，仅保留作兼容兜底。
     */
    @Deprecated("使用后台服务版本", ReplaceWith("captureAndDetect(roiFlag)"))
    private fun captureAndDetectOld(roiFlag: Int): SauceDetectionProcessor.SausageInfo? {
        processor?.roiFlag = roiFlag

        if (cameraProvider == null || imageCapture == null) {
            logPhoto("KaoChangAlgorithm", "相机尚未初始化，等待初始化完成")
            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline && (cameraProvider == null || imageCapture == null)) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    logPhoto("KaoChangAlgorithm", "等待相机初始化时被中断")
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (cameraProvider == null || imageCapture == null) {
                logPhoto("KaoChangAlgorithm", "相机初始化超时，返回空结果")
                return null
            }
            logPhoto("KaoChangAlgorithm", "相机初始化完成，继续拍照流程")
        }

        if (imageCapture == null) {
            logPhoto("KaoChangAlgorithm", "相机未初始化")
            return null
        }
        logPhoto("KaoChangAlgorithm", "开始拍照")

        val latch = CountDownLatch(1)
        var result: SauceDetectionProcessor.SausageInfo? = null

        if (camera == null || imageCapture == null) {
            val rebound = rebindCamera()
            if (!rebound) {
                logPhotoError("KaoChangAlgorithm", "Camera 或 ImageCapture 未绑定，无法拍照")
                result = null
                latch.countDown()
            }
        }

        if (camera != null && imageCapture != null) {
            var didRetry = false
            imageCapture?.takePicture(executor, object : OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val img = imageProxyToMat(imageProxy)
                        result = if (img == null) {
                            logPhotoError("KaoChangAlgorithm", "图像处理失败")
                            null
                        } else {
                            detectSausageSync(img)
                        }
                    } catch (e: Exception) {
                        logPhotoError("KaoChangAlgorithm", "处理图像时出错：${e.message}")
                        result = null
                    } finally {
                        imageProxy.close()
                        latch.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    logPhotoError("KaoChangAlgorithm", "拍照失败，尝试重新拍照：${exception.message}")
                    if (!didRetry) {
                        didRetry = true
                        val rebound = rebindCamera()
                        if (rebound && imageCapture != null) {
                            imageCapture?.takePicture(executor, this)
                            return
                        }
                    }
                    result = null
                    latch.countDown()
                }
            })
        }

        return try {
            if (latch.await(10, TimeUnit.SECONDS)) {
                result
            } else {
                logPhotoError("KaoChangAlgorithm", "拍照检测超时")
                null
            }
        } catch (_: InterruptedException) {
            logPhotoError("KaoChangAlgorithm", "拍照检测被中断")
            null
        }
    }

    @Deprecated("使用后台服务版本，不再需要手动初始化相机")
    private fun rebindCamera(): Boolean {
        return try {
            val provider = cameraProvider ?: return false
            val owner = lifecycleOwner ?: return false
            provider.unbindAll()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .setTargetResolution(Size(832, 448))
                .build()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            camera = provider.bindToLifecycle(owner, selector, imageCapture!!)
            true
        } catch (e: Exception) {
            logPhotoError("KaoChangAlgorithm", "相机重新绑定失败：${e.message}")
            false
        }
    }

    private fun imageProxyToMat(imageProxy: ImageProxy): Mat? {
        return CameraFrameUtils.imageProxyToMat(imageProxy, "【售卖口拍照】")
    }

    /**
     * 同步识别逻辑。
     *
     * 对售卖口：
     * - code=1：未检测到烤肠
     * - code=2：检测到烤肠
     */
    private fun detectSausageSync(img: Mat?): SauceDetectionProcessor.SausageInfo? {
        try {
            if (img == null) {
                logDetectError("执行烤肠检测失败：图像为空")
                return null
            }

            val currentProcessor = processor ?: return null
            val platformType = if (currentProcessor.roiFlag == 0) "运输台" else "售卖口"
            logDetect("开始检测${platformType}上的烤肠，图像尺寸=${img.width()}x${img.height()}，通道数=${img.channels()}")

            val result = currentProcessor.processImage(img)
            if (currentProcessor.roiFlag == 1) {
                if (result.sausages.isNotEmpty()) {
                    val sausage = result.sausages[0]
                    val resultText = String.format(
                        "售卖口检测结果：\n结果码=%d\n像素坐标=(%d, %d)\n世界坐标=(%.2f, %.2f, %.2f)",
                        sausage.code,
                        sausage.pixelX,
                        sausage.pixelY,
                        sausage.worldX,
                        sausage.worldY,
                        sausage.worldZ
                    )
                    logDetect(resultText)
                    return sausage
                }
                logDetectWarn("售卖口检测结果为空")
                return null
            }

            if (result.sausages.isEmpty()) {
                logDetect("${platformType}未检测到烤肠")
                return null
            }

            val sausage = result.sausages[0]
            val resultText = String.format(
                "检测到烤肠：\n像素坐标=(%d, %d)\n世界坐标=(%.2f, %.2f, %.2f)",
                sausage.pixelX,
                sausage.pixelY,
                sausage.worldX,
                sausage.worldY,
                sausage.worldZ
            )
            logDetect(resultText)
            return sausage
        } catch (e: Exception) {
            logDetectError("检测烤肠时出错：${e.message}")
            return null
        } finally {
            img?.release()
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                val cacheDir = instance().cacheDir.absolutePath
                logDetect("调试图片已保存到：$cacheDir")
            }
        }
    }

    private fun detectSausage(
        img: Mat?,
        callBack: ((saInfo: SauceDetectionProcessor.SausageInfo?) -> Unit)
    ) {
        val result = detectSausageSync(img)
        callBack.invoke(result)
    }
}
