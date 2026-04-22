package cn.niuyannet.kaochang.android.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.ui.MainActivity
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import cn.niuyannet.kaochang.android.MyApp.Companion.instance

class PhotoCaptureService : LifecycleService() {
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var detectionProcessor: SauceDetectionProcessor? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "PhotoCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "photo_capture_channel"
        private const val ACTION_INIT_SERVICE = "init_service"
        private const val ACTION_CAPTURE_AND_DETECT = "capture_and_detect"
        private const val ACTION_STOP_SERVICE = "stop_service"
        
        // 保存待回调任务，避免服务异步返回时找不到调用方。
        private val pendingCallbacks = mutableMapOf<String, (SauceDetectionProcessor.SausageInfo?) -> Unit>()
        private var callbackCounter = 0
        
        // 服务实例引用，便于页面直接复用已启动的后台服务。
        @Volatile
        private var serviceInstance: PhotoCaptureService? = null
        // 防止并发拍照把相机状态打乱，例如按钮连点或后台重复任务。
        @Volatile
        private var isCapturing = false

        @Volatile
        private var isRestarted = false
        
        /**
         * 启动并初始化服务。
         * 只负责拉起前台服务，不在这里强制初始化相机，真正拍照时再按需准备相机。
         */
        fun startService(context: Context) {
            LogUtils.d("【拍照服务】请求启动前台服务")
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                action = ACTION_INIT_SERVICE
            }
            ContextCompat.startForegroundService(context, intent)
            isRestarted = false
        }
        
        /**
         * 停止服务。
         */
        fun stopService(context: Context) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }

        // 供页面直接获取当前服务实例。
        fun getInstance(): PhotoCaptureService? {
            return serviceInstance
        }

        /**
         * 拍照并检测（异步）
         * @param context 上下文
         * @param roiFlag 0: 运输台; 1: 售卖台
         * @param callback 检测结果回调
         */
        fun captureAndDetect(
            context: Context,
            roiFlag: Int,
            isRetry: Boolean,
            callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
        ) {
            val callbackId = "callback_${callbackCounter++}"
            pendingCallbacks[callbackId] = callback


            // 服务已存在时尽量直接复用，减少重复拉起和重复绑定。
            serviceInstance?.let { service ->
                if (service.isInitializedService && !isRestarted && isRetry) {
                    LogUtils.d("【拍照服务】复用已启动服务直接执行拍照：roiFlag=$roiFlag, callbackId=$callbackId")
                    service.captureAndDetectInternal(roiFlag, callbackId)
                } else {
                    // 服务已存在但尚未就绪，短暂等待初始化完成后再执行，避免误判为拍照失败。
                    LogUtils.d("【拍照服务】服务未就绪，等待初始化完成：roiFlag=$roiFlag, callbackId=$callbackId")
                    service.serviceScope.launch {
                        // 最多等待 5 秒，避免调用方一直挂起。
                        var waited = 0
                        while (!(service.isInitializedService && !isRestarted) && waited < 5000) {
                            delay(100)
                            waited += 100
                        }
                        if (service.isInitializedService && !isRestarted) {
                            service.captureAndDetectInternal(roiFlag, callbackId)
                        } else {
                            Log.e(TAG, "拍照服务初始化超时")
                            LogUtils.w("【拍照服务】等待服务初始化超时，放弃本次拍照：roiFlag=$roiFlag, callbackId=$callbackId")
                            invokeCallback(callbackId, null)
                        }
                    }
                }
            } ?: run {
                // 服务尚未启动，先拉起前台服务并把这次拍照请求一起带上。
                LogUtils.d("【拍照服务】服务未启动，准备拉起前台服务后执行拍照：roiFlag=$roiFlag, callbackId=$callbackId")
                val intent = Intent(context, PhotoCaptureService::class.java).apply {
                    action = ACTION_CAPTURE_AND_DETECT
                    putExtra("roiFlag", roiFlag)
                    putExtra("callbackId", callbackId)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
        
        internal fun invokeCallback(callbackId: String, result: SauceDetectionProcessor.SausageInfo?) {
            val callback = pendingCallbacks.remove(callbackId)
            if (callback != null) {
                try {
                    callback(result)
                    LogUtils.d("【拍照服务】拍照结果回调完成：callbackId=$callbackId, hasResult=${result != null}")
                    Log.d(TAG, "拍照结果回调完成，callbackId=$callbackId")
                } catch (e: Exception) {
                    LogUtils.e("【拍照服务】调用拍照结果回调失败：callbackId=$callbackId", e)
                    Log.e(TAG, "调用拍照结果回调失败", e)
                }
            } else {
                LogUtils.w("【拍照服务】未找到回调对象，可能已超时或已被释放：callbackId=$callbackId")
                Log.w(TAG, "未找到回调对象，callbackId=$callbackId")
            }
        }
        
        internal fun setServiceInstance(service: PhotoCaptureService?) {
            serviceInstance = service
        }
    }

    // 相机初始化状态。
    @Volatile
    var isInitialized = false
        private set
    // 服务初始化状态。
    var isInitializedService = false
    private val initializationLock = Any()
    // 专门为拍照事务准备的锁，避免同一时刻多次拍照并发进入。
    private val captureLock = Any()
    
    override fun onCreate() {
        super.onCreate()
        LogUtils.d("【拍照服务】前台服务已创建")
        createNotificationChannel()
        Companion.setServiceInstance(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_INIT_SERVICE -> {
                // 仅启动前台服务，真正拍照时再准备相机。
                startForegroundService()
                isInitializedService = true
            }
            ACTION_CAPTURE_AND_DETECT -> {
                val roiFlag = intent.getIntExtra("roiFlag", 0)
                val callbackId = intent.getStringExtra("callbackId") ?: ""
                // 服务未初始化时先拉起前台服务，再继续本次拍照。
                if (!isInitializedService) {
                    startForegroundService()
                    isInitializedService = true
                    serviceScope.launch {
                        captureAndDetectInternal(roiFlag, callbackId)
                    }
                } else {
                    captureAndDetectInternal(roiFlag, callbackId)
                }
            }
            ACTION_STOP_SERVICE -> {
                stopService()
                isRestarted = true
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台拍照服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台自动拍照服务通知"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundService() {
        val notification = createNotification("拍照服务运行中")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 按需准备相机。
     * 只有真正需要拍照时才初始化，避免长期占用相机导致别的页面无法使用。
     */
    private suspend fun ensureCameraReady() {
        synchronized(initializationLock) {
            if (isInitialized) return

            LogUtils.d("【拍照服务】开始按需初始化相机")

            val cameraProviderFuture =
                ProcessCameraProvider.getInstance(this@PhotoCaptureService)
            cameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .setTargetResolution(Size(832, 448))
                .build()

            cameraProvider!!.unbindAll()
            cameraProvider!!.bindToLifecycle(
                this@PhotoCaptureService,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageCapture
            )

            if (detectionProcessor == null) {
                detectionProcessor = SauceDetectionProcessor(this@PhotoCaptureService)
            }

            isInitialized = true
            LogUtils.d("【拍照服务】相机绑定完成，已可执行拍照")
        }
    }

    /**
     * 统一释放相机资源。
     * 当服务停止、重启或需要主动让出相机时都走这里，避免相机状态残留。
     */
    private fun releaseCamera() {
        synchronized(initializationLock) {
            LogUtils.d("【拍照服务】释放相机资源")
            cameraProvider?.unbindAll()
            cameraProvider = null
            imageCapture = null
            isInitialized = false
            detectionProcessor = null
        }
    }

    /**
     * 旧版初始化入口，当前仍保留给少数历史流程复用。
     */
    private suspend fun initializeCameraOnce() {
        synchronized(initializationLock) {
            if (isInitialized) {
                LogUtils.d("【拍照服务】相机已初始化，跳过重复初始化")
                return
            }
            
            try {
                LogUtils.d("【拍照服务】开始初始化相机")
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@PhotoCaptureService)
                cameraProvider = cameraProviderFuture.get()
                
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setTargetResolution(Size(832, 448))
                    .build()
                
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this@PhotoCaptureService,
                    cameraSelector,
                    imageCapture
                )
                
                // 初始化检测处理器。
                if (detectionProcessor == null) {
                    detectionProcessor = SauceDetectionProcessor(this@PhotoCaptureService)
                }
                
                isInitialized = true
                LogUtils.d("【拍照服务】相机初始化成功")
                updateNotification("拍照服务就绪")
            } catch (e: Exception) {
                Log.e(TAG, "初始化相机失败", e)
                LogUtils.e("【拍照服务】初始化相机失败：${e.message}", e)
                updateNotification("相机初始化失败：${e.message}")
            }
        }
    }
    
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("后台拍照服务")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 内部停止入口：释放资源但不主动 stopSelf，供重启场景复用。
     */
    private fun stopServiceSafe() {
        isInitializedService = false
        // 若当前仍在拍照，直接结束本次事务，防止服务退出时挂住。
        synchronized(captureLock) {
            isCapturing = false
        }
        releaseCamera()
        stopForeground(true)
    }

    private fun stopService() {
        // 若当前仍在拍照，直接结束本次事务，防止销毁时状态残留。
        synchronized(captureLock) {
            isCapturing = false
        }
        isInitializedService = false
        stopForeground(true)
        stopSelf()
    }

    /**
     * 页面侧主动停止前台服务。
     */
    fun stopServicePublic(callback: (success: Boolean) -> Unit) {
        serviceScope.launch {
            try {
                LogUtils.d("【拍照服务】收到页面侧关闭服务请求")
                isRestarted = true
                stopServiceSafe()
                delay(500)
                callback(true)
            } catch (e: Exception) {
                LogUtils.e("【拍照服务】停止服务失败", e)
                callback(false)
            }
        }
    }

    /**
     * 主动重启相机。
     * 主要用于相机状态异常时强制重新绑定。
     */
    fun restartCamera() {
        serviceScope.launch {
            while (isCapturing) delay(500)
            releaseCamera()
        }
        serviceScope.launch {
            synchronized(initializationLock) {
                isInitialized = false
                cameraProvider?.unbindAll()
                imageCapture = null

                // 重新初始化 ImageCapture 并绑定。
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@PhotoCaptureService)
                cameraProvider = cameraProviderFuture.get()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(832, 448))
                    .build()

                cameraProvider?.bindToLifecycle(
                    this@PhotoCaptureService,
                    cameraSelector,
                    imageCapture
                )

                isInitialized = true
                updateNotification("相机已重启")
                LogUtils.d("【拍照服务】相机已重启并重新绑定")
            }
        }
    }

    /**
     * 执行一次完整的拍照与检测。
     * 这里会串行化拍照流程，防止并发拍照导致相机状态混乱。
     */
    fun captureAndDetectInternal(roiFlag: Int, callbackId: String) {
        synchronized(captureLock) {
            if (isCapturing) {
                LogUtils.w("【拍照服务】当前已有拍照任务在执行，直接返回空结果：roiFlag=$roiFlag, callbackId=$callbackId")
                invokeCallback(callbackId, null)
                return
            }
            isCapturing = true
        }

        serviceScope.launch {
            try {
                // 每次使用前确认相机已准备好。
                ensureCameraReady()

                detectionProcessor?.roiFlag = roiFlag
                updateNotification("正在拍照检测...")
                LogUtils.d("【拍照服务】开始拍照并检测：roiFlag=$roiFlag, callbackId=$callbackId")

                imageCapture!!.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {

                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            try {
                                val img = imageProxyToMat(imageProxy)
                                val result = detectSausageSync(img)
                                invokeCallback(callbackId, result)
                            } catch (e: Exception) {
                                LogUtils.e("【拍照服务】拍照成功后处理图像失败：callbackId=$callbackId, error=${e.message}", e)
                                invokeCallback(callbackId, null)
                            } finally {
                                imageProxy.close()
                                updateNotification("拍照服务就绪")
                                synchronized(captureLock) {
                                    isCapturing = false
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "拍照失败", exception)
                            LogUtils.e("【拍照服务】拍照失败：callbackId=$callbackId, error=${exception.message}", exception)
                            invokeCallback(callbackId, null)
                            //releaseCamera()       // ✅ 出错也解绑
                            updateNotification("拍照失败")
                            // 释放
                            synchronized(captureLock) {
                                isCapturing = false
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                LogUtils.e("【拍照服务】拍照流程异常：roiFlag=$roiFlag, callbackId=$callbackId", e)
                invokeCallback(callbackId, null)
                synchronized(captureLock) {
                    isCapturing = false
                }
            }
        }
    }

    /**
     * 旧版拍照入口，已废弃，仅保留做兼容兜底。
     */
    @Deprecated("使用每次拍照再开启相机的版本", ReplaceWith("captureAndDetectInternal(roiFlag, callbackId)"))
    fun captureAndDetectInternalOld(roiFlag: Int, callbackId: String) {
        // 旧逻辑要求相机已提前初始化，否则直接失败。
        if (!isInitialized || imageCapture == null || detectionProcessor == null) {
            LogUtils.w("【拍照服务】旧版拍照入口发现相机未初始化，无法继续拍照")
            Log.e(TAG, "相机未初始化，无法拍照")
            invokeCallback(callbackId, null)
            return
        }
        
        detectionProcessor?.roiFlag = roiFlag
        updateNotification("正在拍照检测...")
        LogUtils.d("【拍照服务】开始执行旧版拍照入口：roiFlag=$roiFlag, callbackId=$callbackId")

        LogUtils.d("【拍照服务】执行器状态：isShutdown=${cameraExecutor.isShutdown}, isTerminated=${cameraExecutor.isTerminated}")
        LogUtils.d("【拍照服务】当前 ImageCapture 实例：$imageCapture")
        imageCapture!!.takePicture(
            cameraExecutor,
            object : OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        LogUtils.d("【拍照服务】拍照成功，进入图像处理回调")
                        val img = imageProxyToMat(imageProxy)
                        if (img == null) {
                            LogUtils.w("【拍照服务】图像转换失败，无法继续识别")
                            invokeCallback(callbackId, null)
                        } else {
                            LogUtils.d("【拍照服务】图像转换完成，开始执行识别")
                            val result = detectSausageSync(img)
                            invokeCallback(callbackId, result)
                        }
                    } catch (e: Exception) {
                        LogUtils.e("【拍照服务】旧版拍照入口处理图像失败：${e.message}", e)
                        invokeCallback(callbackId, null)
                    } finally {
                        imageProxy.close()
                        LogUtils.d("【拍照服务】旧版拍照入口执行结束，服务恢复就绪")
                        updateNotification("拍照服务就绪")
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    LogUtils.e(TAG, "拍照失败", exception)
                    Log.e(TAG, "拍照失败", exception)
                    invokeCallback(callbackId, null)
                    updateNotification("拍照失败: ${exception.message}")
                }
            }
        )
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
            LogUtils.e("【拍照服务】ImageProxy 转 Mat 失败：${e.message}", e)
            null
        }
    }
    
    private fun detectSausageSync(img: Mat?): SauceDetectionProcessor.SausageInfo? {
        return try {
            if (img == null || detectionProcessor == null) {
                LogUtils.w("【拍照服务】执行烤肠识别时图像或处理器为空")
                return null
            }
            
            val platformType = if (detectionProcessor!!.roiFlag == 0) "运输台" else "售卖台"
            LogUtils.d("【拍照服务】开始识别${platformType}烤肠：size=${img.width()}x${img.height()}, channels=${img.channels()}")
            
            val result = detectionProcessor!!.processImage(img)
            
            // 售卖台检测逻辑
            if (detectionProcessor!!.roiFlag == 1) {
                // 售卖台：processImage 应该总是返回至少一个结果
                if (result.sausages.isNotEmpty()) {
                    val sausage = result.sausages[0]
                    val resultText = String.format(
                        "售卖台检测结果:\n结果: (%d) \n像素坐标: (%d, %d) \n世界坐标: (%.2f, %.2f, %.2f)",
                        sausage.code, sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                    )
                    LogUtils.d(resultText)
                    return sausage
                } else {
                    LogUtils.d("【拍照服务】售卖台识别结果为空")
                    return null
                }
            }

            // 运输台识别逻辑。
            if (result.sausages.isEmpty()) {
                LogUtils.d("【拍照服务】${platformType}未检测到烤肠")
                return null
            } else {
                val sausage = result.sausages[0]
                val resultText = String.format(
                    "检测到烤肠:\n像素坐标: (%d, %d)\n世界坐标: (%.2f, %.2f, %.2f)",
                    sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                )
                LogUtils.d(resultText)
                return sausage
            }
        } catch (e: Exception) {
            LogUtils.e("【拍照服务】识别烤肠失败：${e.message}", e)
            null
        } finally {
            img?.release()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        LogUtils.d("【拍照服务】服务销毁，开始释放资源")
        // 销毁时若还有拍照任务，直接结束，避免退出过程卡住。
        synchronized(captureLock) {
            isCapturing = false
        }
        releaseCamera()
        isInitializedService = false
        cameraExecutor.shutdown()
        serviceScope.cancel()
        Companion.setServiceInstance(null)
    }
}
