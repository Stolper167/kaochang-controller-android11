package cn.niuyannet.kaochang.android.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import cn.niuyannet.kaochang.android.adapter.PlayAdapter
import cn.niuyannet.kaochang.android.adapter.PlayResourcesBean
import cn.niuyannet.kaochang.android.databinding.MainActivityBinding
import cn.niuyannet.kaochang.android.net.NetApi
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.youth.banner.listener.OnPageChangeListener

abstract class BannerActivity : LoadNetActivity() {

    val loopTime = 6000L
    var currentPos = 1

    val taskHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    private val resourcesList by lazy {
        arrayListOf<PlayResourcesBean>()
    }

    val adapter by lazy {
        PlayAdapter(this, resourcesList, this)
    }

    abstract fun initData()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initBanner()
        initData()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun initAdsData() {
        NetApi.getAds { code, content ->
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json.getIntValue("code") == 200) {
                    resourcesList.clear()
                    val jsonArray = json.getJSONArray("data")
                    for (i in 0 until jsonArray.size) {
                        val item = jsonArray.getJSONObject(i)
                        val mediaType = item.getString("mediaType")
                        resourcesList.add(
                            PlayResourcesBean(
                                isImg = mediaType == "image",
                                title = item.getString("title"),
                                url = item.getString("mediaUrl")
                            )
                        )
                    }
                    adapter.setDatas(resourcesList)
                    adapter.notifyDataSetChanged()
                    tryStartSingleVideo()
                }
            } else {
                ToastUtils.showShort("缃戠粶閿欒")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        taskHandler.post(task)
    }

    override fun onPause() {
        taskHandler.removeCallbacks(task)
        super.onPause()
    }

    private fun initBanner() {
        binding.banner.addBannerLifecycleObserver(this)
            .setAdapter(adapter, true)
            .setLoopTime(loopTime)

        binding.banner.addOnPageChangeListener(object : ViewPager.OnPageChangeListener, OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) = Unit

            override fun onPageSelected(position: Int) {
                currentPos = when (position) {
                    binding.banner.realCount - 1 -> binding.banner.realCount
                    0 -> 1
                    else -> position + 1
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE && binding.banner.isInfiniteLoop) {
                    taskHandler.post(task)
                }
            }
        })
    }

    private fun tryStartSingleVideo() {
        if (resourcesList.size != 1 || resourcesList[0].isImg) {
            return
        }

        binding.banner.isAutoLoop(false)
        currentPos = 0
        binding.banner.postDelayed({
            if (isFinishing || isDestroyed) {
                return@postDelayed
            }
            val holder = adapter.getVHMap()[currentPos] as? PlayAdapter.VideoHolder ?: return@postDelayed
            GSYVideoManager.onPause()
            holder.video.isLooping = true
            holder.video.startPlayLogic()
        }, 2000)
    }

    val task = Runnable {
        if (isFinishing || isDestroyed) {
            return@Runnable
        }

        val holder = adapter.getVHMap()[currentPos]
        if (holder is PlayAdapter.VideoHolder) {
            GSYVideoManager.onPause()
            holder.video.startPlayLogic()
            if (resourcesList.size <= 1 && resourcesList.firstOrNull()?.isImg == false) {
                binding.banner.isAutoLoop(false)
                binding.banner.stop()
            } else {
                binding.banner.isAutoLoop(true)
                binding.banner.stop()
                binding.banner.isAutoLoop(false)
            }
        } else {
            GSYVideoManager.onPause()
            binding.banner.isAutoLoop(true)
            binding.banner.start()
        }
    }

    override fun onDestroy() {
        taskHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        binding.banner.destroy()
    }
}
