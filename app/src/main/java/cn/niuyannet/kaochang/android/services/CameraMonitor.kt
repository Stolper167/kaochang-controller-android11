package cn.niuyannet.kaochang.android.services

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.utils.LogUtils
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraMonitor {

    companion object {
        private const val TAG = "CameraMonitor"
        val instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CameraMonitor() }
    }

    private fun sellPhotoD(message: String) = LogUtils.d("【售卖口拍照】$message")
    private fun sellPhotoW(message: String) = LogUtils.w("【售卖口拍照】$message")
    private fun sellPhotoE(message: String) = LogUtils.e("【售卖口拍照】$message")
    private fun sellDetectD(message: String) = LogUtils.d("【售卖口识别】$message")
    private fun sellDetectW(message: String) = LogUtils.w("【售卖口识别】$message")
    private fun sellDetectE(message: String) = LogUtils.e("【售卖口识别】$message")

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var weakReference: WeakReference<Context>? = null
    private val cameraService = AtomicReference<CameraService?>(null)

    private val takeStatus = AtomicBoolean(true)
    private var detectionProcessor: SauceDetectionProcessor? = null
    private val pendingCallbacks = mutableMapOf<String, (SauceDetectionProcessor.SausageInfo?) -> Unit>()
    private var callbackCounter = 0
    private val deque = ConcurrentLinkedDeque<QueueData>()

    /**
     * 重启拍照服务，主要用于拍照超时或回调异常后的自恢复。
     */
    fun restartService() {
        sellPhotoD("重新启动拍照服务")
        weakReference?.get()?.let { startService(it, true) }
    }

    /**
     * 启动拍照服务并建立绑定。
     */
    fun startService(context: Context, restart: Boolean = false) {
        close()
        val appContext = context.applicationContext
        if (!restart) {
            weakReference = WeakReference(appContext)
        }
        if (cameraService.get() == null) {
            val serviceIntent = Intent(appContext, CameraService::class.java)
            appContext.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        if (detectionProcessor == null) {
            detectionProcessor = SauceDetectionProcessor(appContext)
        }

        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, 2000)
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            val binder = iBinder as CameraService.ClientBinder
            cameraService.set(binder.service)
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            cameraService.set(null)
        }
    }

    private val runnable = object : Runnable {
        override fun run() {
            try {
                if (takeStatus.get()) {
                    deque.poll()?.let {
                        sellPhotoD("开始执行拍照任务")
                        takePhoto(it.roiFlag, it.delay, it.timeoutMs, it.retry, it.callback)
                    }
                }
            } finally {
                handler.postDelayed(this, 500)
            }
        }
    }

    fun clearQueue() {
        sellPhotoD("清空拍照任务队列")
        deque.clear()
    }

    /**
     * 预热相机，避免首次拍照时首帧不稳定。
     */
    fun prewarm(holdMs: Long = 8_000L) {
        val appContext = weakReference?.get()
        if (appContext == null) {
            sellPhotoW("预热相机失败：上下文不可用")
            return
        }
        if (cameraService.get() == null) {
            startService(appContext)
        }
        handler.postDelayed({
            val boundCameraService = cameraService.get()
            if (boundCameraService == null) {
                sellPhotoW("预热相机失败：相机服务尚未绑定")
                return@postDelayed
            }
            boundCameraService.prewarm(holdMs) { success ->
                if (success) {
                    sellPhotoD("相机预热成功，保持 ${holdMs}ms")
                } else {
                    sellPhotoW("相机预热失败")
                }
            }
        }, 300L)
    }

    fun takePhotoQueue(
        roiFlag: Int,
        delay: Long,
        timeoutMs: Long,
        retry: Int,
        priority: Boolean,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        sellPhotoD("拍照任务入队：currentSize=${deque.size}, priority=$priority")
        if (deque.size < 20) {
            if (priority) {
                deque.offerFirst(QueueData(roiFlag, delay, timeoutMs, retry, callback))
            } else {
                deque.offer(QueueData(roiFlag, delay, timeoutMs, retry, callback))
            }
        } else if (priority) {
            deque.pollLast()
            deque.offerFirst(QueueData(roiFlag, delay, timeoutMs, retry, callback))
        } else {
            sellPhotoW("拍照任务队列已满")
            handler.postDelayed({ callback(null) }, 2000)
        }
    }

    private fun takePhoto(
        roiFlag: Int,
        delay: Long,
        timeoutMs: Long,
        retry: Int,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        val callbackId = "callback_${callbackCounter++}"
        pendingCallbacks[callbackId] = callback
        if (takeStatus.getAndSet(false)) {
            detectionProcessor?.roiFlag = roiFlag
            takePhoto(delay, retry, timeoutMs) {
                invokeCallback(callbackId, it)
                takeStatus.set(true)
            }
        } else {
            sellPhotoW("请等待上一个拍照任务结束")
            invokeCallback(callbackId, null)
        }
    }

    private fun takePhoto(
        delay: Long,
        retry: Int,
        timeoutMs: Long,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        val boundCameraService = cameraService.get()
        if (boundCameraService == null) {
            sellPhotoE("相机服务未启动")
            handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            return
        }

        val completed = AtomicBoolean(false)
        val timeoutRunnable = Runnable {
            if (!completed.getAndSet(true)) {
                sellPhotoE("拍照超时：elapsed>${timeoutMs}ms")
                restartService()
                handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            }
        }

        try {
            handler.postDelayed(timeoutRunnable, timeoutMs)
            boundCameraService.takePhoto(object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (completed.getAndSet(true)) {
                        sellPhotoW("拍照成功回调被忽略：任务已超时")
                        image.close()
                        return
                    }
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        val img = image.use { imageProxyToMat(it) }
                        sellPhotoD("图像信息是否为空：${img == null}")
                        callback(detectSausageSync(img))
                    } catch (e: Exception) {
                        sellPhotoE("拍照成功后处理图像失败：${e.message}")
                        callback(null)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    if (completed.getAndSet(true)) {
                        sellPhotoW("拍照错误回调被忽略：任务已超时")
                        return
                    }
                    handler.removeCallbacks(timeoutRunnable)
                    restartService()
                    sellPhotoE("拍照失败：retry=$retry, message=${e.message}")
                    handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
                }
            })
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            if (!completed.getAndSet(true)) {
                sellPhotoE("调用相机服务异常：${e.message}")
                handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            }
        }
    }

    private fun handleTimeoutAndRetry(
        delay: Long,
        retry: Int,
        timeoutMs: Long,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        if (retry > 0) {
            sellPhotoW("拍照异常后准备重试：remainingRetry=${retry - 1}")
            handler.postDelayed({
                takePhoto(delay, retry - 1, timeoutMs, callback)
            }, delay)
        } else {
            callback(null)
        }
    }

    /**
     * 关闭拍照服务并解绑。
     */
    fun close(callback: (() -> Unit)? = null) {
        handler.removeCallbacks(runnable)
        takeStatus.set(true)
        val boundService = cameraService.get()
        if (boundService != null) {
            weakReference?.get()?.let {
                boundService.releaseNow(Runnable {
                    try {
                        it.unbindService(serviceConnection)
                    } catch (_: IllegalArgumentException) {
                    }
                    cameraService.set(null)
                    callback?.invoke()
                })
                return
            }
            callback?.invoke()
        } else {
            callback?.invoke()
        }
    }

    private fun invokeCallback(callbackId: String, result: SauceDetectionProcessor.SausageInfo?) {
        val callback = pendingCallbacks.remove(callbackId)
        if (callback != null) {
            try {
                callback(result)
                sellPhotoD("回调已完成：callbackId=$callbackId")
            } catch (e: Exception) {
                sellPhotoE("调用回调时出错：${e.message}")
                LogUtils.e(TAG, "【售卖口拍照】调用回调时出错", e)
            }
        } else {
            sellPhotoW("未找到回调：callbackId=$callbackId")
        }
    }

    private fun imageProxyToMat(imageProxy: ImageProxy): Mat? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer[bytes]

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            var mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            if (mat.channels() == 4) {
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_RGBA2BGR)
                mat.release()
                mat = bgrMat
            } else if (mat.channels() == 1) {
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR)
                mat.release()
                mat = bgrMat
            }
            mat
        } catch (e: Exception) {
            sellPhotoE("图像转换失败：${e.message}")
            null
        }
    }

    @SuppressLint("DefaultLocale")
    private fun detectSausageSync(img: Mat?): SauceDetectionProcessor.SausageInfo? {
        return try {
            if (img == null || detectionProcessor == null) {
                sellDetectW("执行烤肠检测失败：图像或处理器为空")
                return null
            }
            val platformType = if (detectionProcessor!!.roiFlag == 0) "运输台" else "售卖台"
            sellDetectD("开始识别${platformType}烤肠：size=${img.width()}x${img.height()}, channels=${img.channels()}")
            val result = detectionProcessor!!.processImage(img)

            if (detectionProcessor!!.roiFlag == 1) {
                if (result.sausages.isNotEmpty()) {
                    val sausage = result.sausages[0]
                    val resultText = String.format(
                        "售卖台检测结果：\n结果=(%d)\n像素坐标=(%d, %d)\n世界坐标=(%.2f, %.2f, %.2f)",
                        sausage.code, sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                    )
                    sellDetectD(resultText)
                    return sausage
                }
                sellDetectW("售卖台检测结果为空")
                return null
            }

            if (result.sausages.isEmpty()) {
                sellDetectD("${platformType}未检测到烤肠")
                null
            } else {
                val sausage = result.sausages[0]
                val resultText = String.format(
                    "检测到烤肠：\n像素坐标=(%d, %d)\n世界坐标=(%.2f, %.2f, %.2f)",
                    sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                )
                sellDetectD(resultText)
                sausage
            }
        } catch (e: Exception) {
            sellDetectE("检测烤肠时出错：${e.message}")
            null
        } finally {
            img?.release()
        }
    }
}
