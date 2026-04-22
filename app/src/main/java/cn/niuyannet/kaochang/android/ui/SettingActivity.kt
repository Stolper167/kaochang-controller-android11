package cn.niuyannet.kaochang.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import cn.niuyannet.kaochang.android.adapters.SettingPagerAdapter
import cn.niuyannet.kaochang.android.databinding.ActivitySettingBinding
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.google.android.material.tabs.TabLayoutMediator

class SettingActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingBinding
    private lateinit var viewPager: ViewPager2
    private val REQUEST_CODE_PERMISSIONS: Int = 10
    private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.CAMERA
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupViewPager()
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnExit.setOnClickListener {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
        
        // 检查相机权限（后台服务需要相机权限）
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        } else {
            LogUtils.d("设置页面初始，获得相机权限（后台服务将使用相机）")
        }
    }


    private fun setupViewPager() {
        viewPager = binding.viewPager
        viewPager.adapter = SettingPagerAdapter(this)
        // Connect TabLayout with ViewPager2
        TabLayoutMediator(binding.tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "烤盘信息"
                1 -> "烤肠箱信息"
                2 -> "配置信息"
                3 -> "测试下位机"
                else -> throw IllegalArgumentException("Invalid position $position")
            }
        }.attach()
    }

    companion object {
        fun start(context: Context?) {
            context?.startActivity(Intent(context, SettingActivity::class.java))
        }
    }
    
    /**
     * 检查是否已授予所有所需权限
     */
    private fun allPermissionsGranted(): Boolean {
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
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                LogUtils.d("设置页面获得相机权限（后台服务将使用相机）")
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 检查相机权限（后台服务需要）
        if (!allPermissionsGranted()) {
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