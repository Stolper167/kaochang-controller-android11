package cn.niuyannet.kaochang.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cn.niuyannet.kaochang.android.detecition.SauceDetectionLauncher
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.SauceDetectionUitls

/**
 * 视觉检测
 */
abstract class BaseSauceDetection: BannerActivity() {
    private val REQUEST_CODE_PERMISSIONS: Int = 10
    private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.CAMERA
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 检查OpenCV是否可用
        if (!SauceDetectionLauncher.isOpenCVAvailable()) {
            Toast.makeText(this, "OpenCV库不可用，请确保已正确安装", Toast.LENGTH_LONG).show()
            return
        }
        // 初始化烤肠检测处理器
        SauceDetectionUitls.processor = SauceDetectionProcessor(this)
        // 检查相机权限（后台服务需要相机权限）
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
              REQUEST_CODE_PERMISSIONS
            )
        } else {
            LogUtils.d("页面初始，获得相机权限（后台服务将使用相机）")
        }
    }

    /**
     * 检查是否已授予所有所需权限
     */
    //private fun allPermissionsGranted(): Boolean {
    protected fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                LogUtils.d("获得相机权限（后台服务将使用相机）")
            } else {
                Toast.makeText(this, "需要相机权限才能使用视觉检测功能", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 检查相机权限（后台服务需要）
        if (!allPermissionsGranted()) {
            LogUtils.d("base 重新获取权限")
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 后台服务管理相机资源，这里不再需要释放
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 后台服务管理相机资源，这里不再需要释放
    }


}