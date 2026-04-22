package cn.niuyannet.kaochang.android.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICaptureCallBack
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UVC 后台拍照管理器。
 *
 * 页面运行期间如需使用 UVC 拍照，可在 onResume 中调用 init(...) 初始化；
 * 页面结束或跳转前调用 close() 释放资源，避免相机长期占用。
 */
class CameraUvc {

    companion object {
        private const val TAG = "CameraUvc"
        val instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CameraUvc() }
    }

    private val handle by lazy { Handler(Looper.getMainLooper()) }
    private var weakReference: WeakReference<Context>? = null
    // 拍照锁，避免多个拍照任务同时进入。
    private val takeStatus = AtomicBoolean(true)

    // 识别处理器。
    private var detectionProcessor: SauceDetectionProcessor? = null
    // 保存待回调任务，便于异步返回时准确回调调用方。
    private val pendingCallbacks = mutableMapOf<String, (SauceDetectionProcessor.SausageInfo?) -> Unit>()
    private var callbackCounter = 0

    // 拍照任务队列。
    private val deque = ConcurrentLinkedDeque<QueueData>()

    // 获取摄像头实例的回调。
    private var cameraListener: (() -> Pair<Boolean, MultiCameraClient.ICamera?>)? = null

    /**
     * 初始化拍照管理器。
     */
    fun init(context: Context, cameraListener: () -> Pair<Boolean, MultiCameraClient.ICamera?>) {
        this.cameraListener = cameraListener
        this.weakReference = WeakReference(context)
        if (this.detectionProcessor == null) {
            this.detectionProcessor = SauceDetectionProcessor(context.applicationContext)
        }
        // 启动轮询任务，统一串行消费拍照请求。
        this.handle.removeCallbacks(runnable)
        this.handle.postDelayed(runnable, 2000)
    }

    /**
     * 定时轮询拍照任务。
     */
    private val runnable = object : Runnable {
        override fun run() {
            try {
                if (takeStatus.get()) {
                    deque.poll()?.let {
                        LogUtils.d(TAG, "【UVC拍照】开始执行拍照任务")
                        takePhoto(it.roiFlag, it.delay, it.timeoutMs, it.retry, it.callback)
                    }
                }
            } finally {
                handle.postDelayed(this, 500)
            }
        }
    }

    /**
     * 清空拍照队列。
     */
    fun clearQueue() = deque.clear()

    /**
     * 添加拍照任务。
     *
     * @param roiFlag 0 表示运输台，1 表示售卖台
     * @param delay 重试延迟
     * @param retry 重试次数
     * @param priority 是否高优先级插队
     */
    fun takePhotoQueue(
        roiFlag: Int,
        delay: Long,
        timeoutMs: Long,
        retry: Int,
        priority: Boolean,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        LogUtils.d(TAG, "【UVC拍照】拍照任务入队：currentSize=${deque.size}, priority=$priority")
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
            LogUtils.w(TAG, "【UVC拍照】拍照任务队列已满，直接返回空结果")
            handle.postDelayed({ callback(null) }, 2000)
        }
    }

    /**
     * 拍照，失败时自动重试。
     */
    private fun takePhoto(
        roiFlag: Int,
        delay: Long,
        timeoutMs: Long,
        retry: Int,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        val callbackId = "callback_${callbackCounter++}"
        pendingCallbacks[callbackId] = callback
        if (takeStatus.get()) {
            takeStatus.set(false)

            detectionProcessor?.roiFlag = roiFlag
            takePhoto(delay, retry, timeoutMs) {
                invokeCallback(callbackId, it)
                takeStatus.set(true)
            }
        } else {
            LogUtils.w(TAG, "【UVC拍照】上一个拍照任务尚未结束，本次请求直接返回空结果")
            invokeCallback(callbackId, null)
        }
    }

    /**
     * 执行拍照并附带超时控制。
     */
    private fun takePhoto(
        delay: Long,
        retry: Int,
        timeoutMs: Long,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val timeoutRunnable = Runnable {
            if (!completed.getAndSet(true)) {
                LogUtils.w(TAG, "【UVC拍照】拍照超时：elapsed>${timeoutMs}ms")
                handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            }
        }

        try {
            val pair = cameraListener!!.invoke()
            if (pair.first) {
                // 摄像头刚打开时适当等待，避免首帧不稳定。
                handle.postDelayed({
                    takePhoto(pair.second!!, completed, timeoutRunnable, delay, retry, timeoutMs, callback)
                }, 2500)
            } else {
                takePhoto(pair.second!!, completed, timeoutRunnable, delay, retry, timeoutMs, callback)
            }
        } catch (e: Exception) {
            handle.removeCallbacks(timeoutRunnable)
            if (!completed.getAndSet(true)) {
                LogUtils.e(TAG, "【UVC拍照】摄像头打开失败或无可用摄像头：${e.message}")
                handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            }
        }
    }

    /**
     * 调用摄像头执行拍照。
     */
    private fun takePhoto(
        camera: MultiCameraClient.ICamera,
        completed: AtomicBoolean,
        timeoutRunnable: Runnable,
        delay: Long,
        retry: Int,
        timeoutMs: Long,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        handle.postDelayed(timeoutRunnable, timeoutMs)
        camera.captureImage(object : ICaptureCallBack {
            override fun onBegin() {}

            override fun onError(error: String?) {
                if (completed.getAndSet(true)) {
                    LogUtils.w(TAG, "【UVC拍照】拍照错误回调被忽略：任务已超时")
                    return
                }
                handle.removeCallbacks(timeoutRunnable)
                handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            }

            override fun onComplete(path: String?) {
                if (completed.getAndSet(true)) {
                    LogUtils.w(TAG, "【UVC拍照】拍照成功回调被忽略：任务已超时")
                    return
                }
                handle.removeCallbacks(timeoutRunnable)
                try {
                    LogUtils.d(TAG, "【UVC拍照】拍照成功，开始处理图像数据")
                    val img = imageProxyToMat(BitmapFactory.decodeFile(path!!)!!)
                    LogUtils.d(TAG, "【UVC拍照】图像转换结果是否为空：${img == null}")
                    callback(detectSausageSync(img))
                } catch (e: Exception) {
                    LogUtils.e(TAG, "【UVC拍照】处理图像数据失败：${e.message}")
                    callback(null)
                } finally {
                    path?.let { File(it).delete() }
                }
            }
        })
    }

    /**
     * 超时或异常后的统一重试逻辑。
     */
    private fun handleTimeoutAndRetry(
        delay: Long,
        retry: Int,
        timeoutMs: Long,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        if (retry > 0) {
            LogUtils.w(TAG, "【UVC拍照】异常后准备重试：remainingRetry=${retry - 1}")
            handle.postDelayed({
                takePhoto(delay, retry - 1, timeoutMs, callback)
            }, delay)
        } else {
            callback(null)
        }
    }

    /**
     * 统一回调出口。
     */
    private fun invokeCallback(callbackId: String, result: SauceDetectionProcessor.SausageInfo?) {
        val callback = pendingCallbacks.remove(callbackId)
        if (callback != null) {
            try {
                callback(result)
                LogUtils.d(TAG, "【UVC拍照】回调已完成：callbackId=$callbackId, hasResult=${result != null}")
            } catch (e: Exception) {
                LogUtils.e(TAG, "【UVC拍照】调用回调失败：${e.message}")
            }
        } else {
            LogUtils.w(TAG, "【UVC拍照】未找到回调对象：callbackId=$callbackId")
        }
    }

    /**
     * 将拍照结果转换为 OpenCV Mat。
     */
    private fun imageProxyToMat(bitmap: Bitmap): Mat? {
        return try {
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
            LogUtils.e(TAG, "【UVC拍照】图像转换失败：${e.message}")
            null
        }
    }

    @SuppressLint("DefaultLocale")
    private fun detectSausageSync(img: Mat?): SauceDetectionProcessor.SausageInfo? {
        return try {
            if (img == null || detectionProcessor == null) {
                LogUtils.w(TAG, "【UVC识别】执行识别失败：图像或识别器为空")
                return null
            }
            val platformType = if (detectionProcessor!!.roiFlag == 0) "运输台" else "售卖台"
            LogUtils.d(
                TAG,
                "【UVC识别】开始识别${platformType}烤肠：size=${img.width()}x${img.height()}, channels=${img.channels()}"
            )
            val result = detectionProcessor!!.processImage(img)

            if (detectionProcessor!!.roiFlag == 1) {
                if (result.sausages.isNotEmpty()) {
                    val sausage = result.sausages[0]
                    val resultText = String.format(
                        "售卖台检测结果：\n结果=(%d)\n像素坐标=(%d, %d)\n世界坐标=(%.2f, %.2f, %.2f)",
                        sausage.code, sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                    )
                    LogUtils.d(TAG, "【UVC识别】$resultText")
                    return sausage
                } else {
                    LogUtils.d(TAG, "【UVC识别】售卖台识别结果为空")
                    return null
                }
            }

            if (result.sausages.isEmpty()) {
                LogUtils.d(TAG, "【UVC识别】${platformType}未检测到烤肠")
                return null
            } else {
                val sausage = result.sausages[0]
                val resultText = String.format(
                    "检测到烤肠：\n像素坐标=(%d, %d)\n世界坐标=(%.2f, %.2f, %.2f)",
                    sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                )
                LogUtils.d(TAG, "【UVC识别】$resultText")
                return sausage
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "【UVC识别】识别烤肠失败：${e.message}")
            null
        } finally {
            img?.release()
        }
    }
}
