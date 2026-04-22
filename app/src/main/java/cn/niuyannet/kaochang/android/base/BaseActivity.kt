package cn.niuyannet.kaochang.android.base

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.shuyu.gsyvideoplayer.GSYVideoManager

abstract class BaseActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置全屏显示
    }

    override fun onPause() {
        super.onPause()
        GSYVideoManager.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        GSYVideoManager.releaseAllVideos()
    }


}