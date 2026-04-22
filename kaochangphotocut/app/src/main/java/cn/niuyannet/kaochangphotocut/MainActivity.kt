package cn.niuyannet.kaochangphotocut

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.niuyannet.kaochangphotocut.ui.theme.KaochangphotocutTheme

class MainActivity : ComponentActivity() {
    
    private var hasCameraPermission by mutableStateOf(false)
    private var hasStoragePermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)
    private var isServiceRunning by mutableStateOf(false)
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        }
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        checkPermissions()
        
        setContent {
            KaochangphotocutTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        hasCameraPermission = hasCameraPermission,
                        hasStoragePermission = hasStoragePermission,
                        hasNotificationPermission = hasNotificationPermission,
                        isServiceRunning = isServiceRunning,
                        onRequestPermissions = { requestPermissions() },
                        onStartService = { 
                            PhotoCaptureService.startService(this)
                            isServiceRunning = true
                        },
                        onStopService = { 
                            PhotoCaptureService.stopService(this)
                            isServiceRunning = false
                        }
                    )
                }
            }
        }
    }
    
    private fun checkPermissions() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (!hasCameraPermission) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        
        if (!hasStoragePermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
        
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    hasCameraPermission: Boolean,
    hasStoragePermission: Boolean,
    hasNotificationPermission: Boolean,
    isServiceRunning: Boolean,
    onRequestPermissions: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "后台拍照服务",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 权限状态
        PermissionStatusCard(
            hasCameraPermission = hasCameraPermission,
            hasStoragePermission = hasStoragePermission,
            hasNotificationPermission = hasNotificationPermission
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 服务控制按钮
        if (!hasCameraPermission || !hasStoragePermission || !hasNotificationPermission) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("请求权限")
            }
        } else {
            if (isServiceRunning) {
                Button(
                    onClick = onStopService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("停止后台拍照")
                }
            } else {
                Button(
                    onClick = onStartService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("启动后台拍照")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 说明文本
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "使用说明：",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• 启动服务后，应用将在后台每5秒自动拍照一次",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• 照片将保存在应用的Pictures目录中",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• 服务会在通知栏显示运行状态",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    hasCameraPermission: Boolean,
    hasStoragePermission: Boolean,
    hasNotificationPermission: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "权限状态",
                fontWeight = FontWeight.Bold
            )
            PermissionItem("相机权限", hasCameraPermission)
            PermissionItem("存储权限", hasStoragePermission)
            PermissionItem("通知权限", hasNotificationPermission)
        }
    }
}

@Composable
fun PermissionItem(name: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name)
        Text(
            text = if (granted) "✓ 已授权" else "✗ 未授权",
            color = if (granted) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.error
        )
    }
}