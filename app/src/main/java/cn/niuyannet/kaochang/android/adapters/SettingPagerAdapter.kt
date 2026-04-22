package cn.niuyannet.kaochang.android.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import cn.niuyannet.kaochang.android.fragments.ConfigFragment
import cn.niuyannet.kaochang.android.fragments.MaintenanceBoxFragment
import cn.niuyannet.kaochang.android.fragments.MaintenanceFragment
import cn.niuyannet.kaochang.android.fragments.TestFragment

class SettingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MaintenanceFragment()
            1 -> MaintenanceBoxFragment()
            2 -> ConfigFragment()
            3 -> TestFragment()
            else -> throw IllegalArgumentException("Invalid position $position")
        }
    }
} 