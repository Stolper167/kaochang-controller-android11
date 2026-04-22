package cn.niuyannet.kaochangphotocut

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhotoCaptureService : LifecycleService() {
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var captureJob: Job? = null
    private var isCapturing = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val TAG = "PhotoCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "photo_capture_channel"
        private const val ACTION_START_CAPTURE = "start_capture"
        private const val ACTION_STOP_CAPTURE = "stop_capture"
        
        // 拍照间隔（毫秒），默认5秒
        private const val CAPTURE_INTERVAL = 5000L
        
        fun startService(context: Context, intervalMillis: Long = CAPTURE_INTERVAL) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra("interval", intervalMillis)
            }
            ContextCompat.startForegroundService(context, intent)
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.stopService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val interval = intent.getLongExtra("interval", CAPTURE_INTERVAL)
                startForegroundService(interval)
            }
            ACTION_STOP_CAPTURE -> {
                stopCapturing()
                stopForeground(true)
                stopSelf()
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
    
    private fun startForegroundService(intervalMillis: Long) {
        val notification = createNotification("正在后台拍照...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 需要指定前台服务类型
            startForeground(
                NOTIFICATION_ID, 
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        serviceScope.launch {
            initializeCamera(intervalMillis)
        }
    }
    
    private suspend fun initializeCamera(intervalMillis: Long) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this@PhotoCaptureService)
            cameraProvider = cameraProviderFuture.get()
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this@PhotoCaptureService,
                cameraSelector,
                imageCapture
            )
            
            startPeriodicCapture(intervalMillis)
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化相机失败", e)
            updateNotification("相机初始化失败: ${e.message}")
        }
    }
    
    private fun startPeriodicCapture(intervalMillis: Long) {
        if (isCapturing) return
        
        isCapturing = true
        captureJob = serviceScope.launch {
            while (isCapturing && isActive) {
                capturePhoto()
                delay(intervalMillis)
            }
        }
    }
    
    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return
        
        val photoFile = createPhotoFile()
        if (photoFile == null) {
            Log.e(TAG, "创建照片文件失败")
            return
        }
        
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputFileOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "照片保存成功: ${photoFile.absolutePath}")
                    updateNotification("照片已保存: ${photoFile.name}")
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    updateNotification("拍照失败: ${exception.message}")
                }
            }
        )
    }
    
    private fun createPhotoFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "PHOTO_$timeStamp.jpg"
            
            val storageDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用应用专属目录
                getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            } else {
                // Android 9 及以下使用公共目录
                File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                ), "KaochangPhotoCut")
            }
            
            storageDir?.mkdirs()
            File(storageDir, imageFileName)
        } catch (e: Exception) {
            Log.e(TAG, "创建文件失败", e)
            null
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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun stopCapturing() {
        isCapturing = false
        captureJob?.cancel()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopCapturing()
        serviceScope.cancel()
    }
}

