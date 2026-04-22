package cn.niuyannet.kaochang.android.utils

import android.annotation.SuppressLint
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import cn.niuyannet.kaochang.android.R
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import java.util.*
import kotlin.concurrent.schedule

//loading框
@SuppressLint("StaticFieldLeak")
private var loadingDialog: MaterialDialog? = null
private var showTime : Long = 0;
private const val showLoadingTime = 1000;
/**
 * 打开等待框
 */
fun ComponentActivity.showLoadingExt(message: String = "请求接口服务...") {
    if (!this.isFinishing) {
        if (loadingDialog == null) {
            loadingDialog = MaterialDialog(this)
                .cancelable(true)
                .cancelOnTouchOutside(false)
                .cornerRadius(12f)
                .customView(R.layout.layout_custom_progress_dialog_view)
                .lifecycleOwner(this)
            loadingDialog?.getCustomView()?.run {
                this.findViewById<TextView>(R.id.loading_tips).text = message
//                this.findViewById<ProgressBar>(R.id.progressBar).indeterminateTintList = SettingUtil.getOneColorStateList(this@showLoadingExt)
            }
        }
        showTime = System.currentTimeMillis();
        loadingDialog?.show()
    }
}

/**
 * 打开等待框
 */
fun Fragment.showLoadingExt(message: String = "请求接口服务...") {
    activity?.let {
        if (!it.isFinishing) {
            if (loadingDialog == null) {
                loadingDialog = MaterialDialog(it)
                    .cancelable(true)
                    .cancelOnTouchOutside(false)
                    .cornerRadius(12f)
                    .customView(R.layout.layout_custom_progress_dialog_view)
                    .lifecycleOwner(this)
                loadingDialog?.getCustomView()?.run {
                    this.findViewById<TextView>(R.id.loading_tips).text = message
//                    this.findViewById<ProgressBar>(R.id.progressBar).indeterminateTintList = SettingUtil.getOneColorStateList(it)
                }
            }
            showTime = System.currentTimeMillis();
            loadingDialog?.show()
        }
    }
}

/**
 * 关闭等待框
 */
fun ComponentActivity.dismissLoadingExt() {
    loadingDialog?.dismiss()
    loadingDialog = null
//    if (System.currentTimeMillis() - showTime < showLoadingTime){
//        Timer().schedule(showLoadingTime-(System.currentTimeMillis() - showTime )){
//            loadingDialog?.dismiss()
//            loadingDialog = null        }
//    }else{
//
//    }
}

/**
 * 关闭等待框
 */
fun Fragment.dismissLoadingExt() {
    if (System.currentTimeMillis() - showTime < showLoadingTime){
        Timer().schedule(showLoadingTime -(System.currentTimeMillis() - showTime )){
            loadingDialog?.dismiss()
            loadingDialog = null        }
    }else{
        loadingDialog?.dismiss()
        loadingDialog = null
    }
}
