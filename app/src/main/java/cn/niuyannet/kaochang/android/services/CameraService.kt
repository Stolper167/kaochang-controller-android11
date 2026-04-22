package cn.niuyannet.kaochang.android.services

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {
    companion object {
        private const val CAMERA_OWNER = "camera_service"
        private const val ANALYSIS_MODE_LOG_THROTTLE_MS = 30_000L
    }

    private data class SelectedCamera(
        val selector: CameraSelector,
        val isExternal: Boolean,
        val label: String,
        val cameraId: String?,
        val lensFacing: Int?,
        val hardwareLevel: Int?
    )

    private val binder = ClientBinder()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var useAnalysisSnapshot = false
    private var pendingAnalysisCaptureCallback: ImageCapture.OnImageCapturedCallback? = null

    private val isCameraInitialized = AtomicBoolean(false)
    private val isCameraInitializing = AtomicBoolean(false)
    private val isCameraReady = AtomicBoolean(false)
    private val cameraLock = Any()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var lastAnalysisModeLogAtMs: Long = 0L
    private var lastAnalysisModeLogKey: String? = null

    private val autoReleaseRunnable = Runnable {
        LogUtils.d("【相机服务】相机预热超时未使用，自动释放相机资源")
        releaseNow()
    }

    inner class ClientBinder : Binder() {
        val service: CameraService
            get() = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d("【相机服务】后台相机服务已创建")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseNow()
        cameraExecutor.shutdown()
        LogUtils.d("【相机服务】后台相机服务已销毁")
        super.onDestroy()
    }

    private fun logAnalysisModeSelection(selectedCamera: SelectedCamera) {
        val logKey = listOf(
            selectedCamera.label,
            selectedCamera.cameraId ?: "unknown",
            selectedCamera.lensFacing?.toString() ?: "null",
            selectedCamera.hardwareLevel?.toString() ?: "null"
        ).joinToString("|")
        val now = System.currentTimeMillis()
        val shouldLog = logKey != lastAnalysisModeLogKey ||
            now - lastAnalysisModeLogAtMs >= ANALYSIS_MODE_LOG_THROTTLE_MS
        if (!shouldLog) {
            return
        }
        lastAnalysisModeLogKey = logKey
        lastAnalysisModeLogAtMs = now
        LogUtils.w(
            "【相机服务】检测到外置相机，切换为分析帧拍照模式：label=${selectedCamera.label}, cameraId=${selectedCamera.cameraId ?: "unknown"}, lensFacing=${selectedCamera.lensFacing}, hardwareLevel=${selectedCamera.hardwareLevel}"
        )
    }

    private fun buildImageCaptureUseCase(): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(Surface.ROTATION_0)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
    }

    private fun buildAnalysisUseCase(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().apply {
                setAnalyzer(cameraExecutor) { image ->
                    val captureCallback = synchronized(cameraLock) {
                        if (!useAnalysisSnapshot) {
                            null
                        } else {
                            pendingAnalysisCaptureCallback.also {
                                if (it != null) {
                                    pendingAnalysisCaptureCallback = null
                                }
                            }
                        }
                    }

                    if (captureCallback == null) {
                        image.close()
                        return@setAnalyzer
                    }

                    try {
                        LogUtils.d("【相机服务】外置相机使用分析帧完成拍照")
                        captureCallback.onCaptureSuccess(image)
                    } catch (e: Exception) {
                        try {
                            image.close()
                        } catch (_: Exception) {
                        }
                        captureCallback.onError(
                            ImageCaptureException(
                                ImageCapture.ERROR_UNKNOWN,
                                "分析帧处理异常",
                                e
                            )
                        )
                    } finally {
                        releaseNow()
                    }
                }
            }
    }

    private fun initCamera(onComplete: (Boolean) -> Unit) {
        synchronized(cameraLock) {
            if (isCameraReady.get() && cameraProvider != null && (imageCapture != null || useAnalysisSnapshot)) {
                onComplete(true)
                return
            }
            if (isCameraInitializing.get()) {
                handler.postDelayed({ initCamera(onComplete) }, 200)
                return
            }
            if (!CameraAccessCoordinator.tryAcquire(CAMERA_OWNER)) {
                LogUtils.w(
                    "【相机服务】初始化被拒绝，当前相机占用者=${CameraAccessCoordinator.currentOwner()}",
                    null
                )
                onComplete(false)
                return
            }
            isCameraInitialized.set(true)
            isCameraInitializing.set(true)
        }

        try {
            LogUtils.d("【相机服务】开始初始化 CameraX")
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()
                    val selectedCamera = selectAnyAvailableCamera(provider)
                    if (selectedCamera == null) {
                        resetStateAndReleaseOwner()
                        LogUtils.e("【相机服务】初始化失败：未找到可用摄像头")
                        onComplete(false)
                        return@addListener
                    }

                    synchronized(cameraLock) {
                        provider.unbindAll()
                        useAnalysisSnapshot = selectedCamera.isExternal
                        imageAnalysis = buildAnalysisUseCase()
                        if (useAnalysisSnapshot) {
                            imageCapture = null
                            provider.bindToLifecycle(this, selectedCamera.selector, imageAnalysis)
                            logAnalysisModeSelection(selectedCamera)
                        } else {
                            imageCapture = buildImageCaptureUseCase()
                            provider.bindToLifecycle(this, selectedCamera.selector, imageCapture, imageAnalysis)
                        }
                        cameraProvider = provider
                        isCameraInitializing.set(false)
                        isCameraReady.set(true)
                    }
                    LogUtils.d("【相机服务】初始化成功并绑定到生命周期")
                    onComplete(true)
                } catch (e: Exception) {
                    resetStateAndReleaseOwner()
                    LogUtils.e("【相机服务】初始化失败：${e.message}")
                    onComplete(false)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            resetStateAndReleaseOwner()
            LogUtils.e("【相机服务】获取 CameraProvider 失败：${e.message}")
            onComplete(false)
        }
    }

    private fun resetStateAndReleaseOwner() {
        synchronized(cameraLock) {
            cameraProvider = null
            imageCapture = null
            imageAnalysis = null
            useAnalysisSnapshot = false
            pendingAnalysisCaptureCallback = null
            isCameraInitialized.set(false)
            isCameraInitializing.set(false)
            isCameraReady.set(false)
        }
        CameraAccessCoordinator.release(CAMERA_OWNER)
    }

    private fun releaseCamera() {
        synchronized(cameraLock) {
            handler.removeCallbacks(autoReleaseRunnable)
            val providerToUnbind = cameraProvider
            val pendingCallback = pendingAnalysisCaptureCallback
            cameraProvider = null
            imageCapture = null
            imageAnalysis = null
            useAnalysisSnapshot = false
            pendingAnalysisCaptureCallback = null
            isCameraInitialized.set(false)
            isCameraInitializing.set(false)
            isCameraReady.set(false)

            if (pendingCallback != null) {
                try {
                    pendingCallback.onError(
                        ImageCaptureException(
                            ImageCapture.ERROR_CAMERA_CLOSED,
                            "相机已释放",
                            Exception("相机已释放")
                        )
                    )
                } catch (_: Exception) {
                }
            }

            try {
                if (providerToUnbind != null) {
                    providerToUnbind.unbindAll()
                    LogUtils.d("【相机服务】相机资源已释放")
                }
            } catch (e: Exception) {
                LogUtils.e("【相机服务】释放相机资源失败：${e.message}")
            } finally {
                CameraAccessCoordinator.release(CAMERA_OWNER)
            }
        }
    }

    fun releaseNow(callback: Runnable? = null) {
        handler.removeCallbacks(autoReleaseRunnable)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseCamera()
            callback?.run()
        } else {
            handler.post {
                releaseCamera()
                callback?.run()
            }
        }
    }

    fun prewarm(holdMs: Long = 8_000L, callback: ((Boolean) -> Unit)? = null) {
        handler.removeCallbacks(autoReleaseRunnable)
        initCamera { success ->
            if (success) {
                LogUtils.d("【相机服务】预热完成，预计保持 ${holdMs}ms")
                handler.removeCallbacks(autoReleaseRunnable)
                handler.postDelayed(autoReleaseRunnable, holdMs)
            }
            callback?.invoke(success)
        }
    }

    fun takePhoto(callback: ImageCapture.OnImageCapturedCallback) {
        initCamera { success ->
            if (!success) {
                callback.onError(
                    ImageCaptureException(
                        ImageCapture.ERROR_CAMERA_CLOSED,
                        "相机初始化失败",
                        Exception("相机初始化失败")
                    )
                )
                return@initCamera
            }

            if (useAnalysisSnapshot) {
                val accepted = synchronized(cameraLock) {
                    if (!isCameraReady.get() || imageAnalysis == null || pendingAnalysisCaptureCallback != null) {
                        false
                    } else {
                        LogUtils.d("【相机服务】开始等待外置相机分析帧")
                        isCameraReady.set(false)
                        pendingAnalysisCaptureCallback = callback
                        true
                    }
                }

                if (!accepted) {
                    callback.onError(
                        ImageCaptureException(
                            ImageCapture.ERROR_CAMERA_CLOSED,
                            "外置相机分析流未就绪",
                            Exception("外置相机分析流未就绪")
                        )
                    )
                    return@initCamera
                }

                handler.removeCallbacks(autoReleaseRunnable)
                handler.postDelayed({
                    val timedOut = synchronized(cameraLock) {
                        if (pendingAnalysisCaptureCallback === callback) {
                            pendingAnalysisCaptureCallback = null
                            true
                        } else {
                            false
                        }
                    }
                    if (timedOut) {
                        LogUtils.e("【相机服务】等待外置相机分析帧超时")
                        callback.onError(
                            ImageCaptureException(
                                ImageCapture.ERROR_CAPTURE_FAILED,
                                "等待外置相机帧超时",
                                Exception("等待外置相机帧超时")
                            )
                        )
                        releaseNow()
                    }
                }, 2500L)
                return@initCamera
            }

            val captureUseCase = synchronized(cameraLock) {
                if (!isCameraReady.get() || imageCapture == null) {
                    null
                } else {
                    LogUtils.d("【相机服务】开始拍照")
                    isCameraReady.set(false)
                    imageCapture
                }
            }

            if (captureUseCase == null) {
                callback.onError(
                    ImageCaptureException(
                        ImageCapture.ERROR_CAMERA_CLOSED,
                        "相机未就绪",
                        Exception("相机未就绪")
                    )
                )
                return@initCamera
            }

            try {
                handler.removeCallbacks(autoReleaseRunnable)
                captureUseCase.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            callback.onCaptureSuccess(image)
                        } finally {
                            releaseNow()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        try {
                            LogUtils.e(
                                "【相机服务】拍照失败：error=${exception.imageCaptureError}, message=${exception.message}"
                            )
                            callback.onError(exception)
                        } finally {
                            releaseNow()
                        }
                    }
                })
            } catch (e: Exception) {
                releaseNow()
                LogUtils.e("【相机服务】拍照异常：${e.message}")
                callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "拍照异常", e))
            }
        }
    }

    private fun findCameraInfoForSelector(
        cameraProvider: ProcessCameraProvider,
        selector: CameraSelector
    ): CameraInfo? {
        return try {
            cameraProvider.availableCameraInfos.firstOrNull { info ->
                selector.filter(listOf(info)).isNotEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }

    @AndroidxOptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun isExternalCamera(cameraInfo: CameraInfo?): Boolean {
        return getCameraMeta(cameraInfo).isExternal
    }

    private data class CameraMeta(
        val isExternal: Boolean,
        val cameraId: String?,
        val lensFacing: Int?,
        val hardwareLevel: Int?
    )

    @AndroidxOptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun getCameraMeta(cameraInfo: CameraInfo?): CameraMeta {
        if (cameraInfo == null) {
            return CameraMeta(false, null, null, null)
        }
        return try {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val lensFacing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            val hardwareLevel =
                camera2Info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val cameraId = camera2Info.cameraId
            CameraMeta(
                isExternal = lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL ||
                    hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL ||
                    (cameraId.toIntOrNull()?.let { it >= 100 } == true),
                cameraId = cameraId,
                lensFacing = lensFacing,
                hardwareLevel = hardwareLevel
            )
        } catch (_: Exception) {
            CameraMeta(false, null, null, null)
        }
    }

    private fun buildSelectedCamera(
        cameraProvider: ProcessCameraProvider,
        selector: CameraSelector,
        label: String
    ): SelectedCamera {
        val cameraInfo = findCameraInfoForSelector(cameraProvider, selector)
        val cameraMeta = getCameraMeta(cameraInfo)
        LogUtils.d(
            "【相机服务】选择${label}摄像头：cameraId=${cameraMeta.cameraId ?: "unknown"}, external=${cameraMeta.isExternal}, lensFacing=${cameraMeta.lensFacing}, hardwareLevel=${cameraMeta.hardwareLevel}"
        )
        return SelectedCamera(
            selector = selector,
            isExternal = cameraMeta.isExternal,
            label = label,
            cameraId = cameraMeta.cameraId,
            lensFacing = cameraMeta.lensFacing,
            hardwareLevel = cameraMeta.hardwareLevel
        )
    }

    private fun selectAnyAvailableCamera(cameraProvider: ProcessCameraProvider): SelectedCamera? {
        return try {
            when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                    buildSelectedCamera(cameraProvider, CameraSelector.DEFAULT_BACK_CAMERA, "后置")
                }

                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                    buildSelectedCamera(cameraProvider, CameraSelector.DEFAULT_FRONT_CAMERA, "前置")
                }

                cameraProvider.availableCameraInfos.isNotEmpty() -> {
                    val cameraInfo = cameraProvider.availableCameraInfos.first()
                    val cameraMeta = getCameraMeta(cameraInfo)
                    LogUtils.d(
                        "【相机服务】使用第一个可用摄像头：cameraId=${cameraMeta.cameraId ?: "unknown"}, external=${cameraMeta.isExternal}, lensFacing=${cameraMeta.lensFacing}, hardwareLevel=${cameraMeta.hardwareLevel}"
                    )
                    SelectedCamera(
                        selector = cameraInfo.cameraSelector,
                        isExternal = cameraMeta.isExternal,
                        label = "首个可用",
                        cameraId = cameraMeta.cameraId,
                        lensFacing = cameraMeta.lensFacing,
                        hardwareLevel = cameraMeta.hardwareLevel
                    )
                }

                else -> {
                    LogUtils.e("【相机服务】未找到任何可用摄像头")
                    null
                }
            }
        } catch (e: Exception) {
            LogUtils.e("【相机服务】选择摄像头失败：${e.message}")
            null
        }
    }
}
