package cn.niuyannet.kaochang.android

import android.app.Application
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.parser.ParserConfig
import com.lzy.okgo.OkGo
import com.lzy.okgo.interceptor.HttpLoggingInterceptor
import com.tencent.bugly.crashreport.CrashReport
import okhttp3.OkHttpClient
import java.util.logging.Level
import kotlin.properties.Delegates

class MyApp: Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化日志系统
        LogUtils.init(this)
        // 记录应用启动日志
        LogUtils.i("Application started")
        
        // 配置 OkGo 取消毫无意义的 HTTP 明文长日志（极大地缓解死循环刷屏）
        val builder = OkHttpClient.Builder()
        val loggingInterceptor = HttpLoggingInterceptor("OkGo")
        loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
        loggingInterceptor.setColorLevel(Level.INFO)
        builder.addInterceptor(loggingInterceptor)
        
        OkGo.getInstance().init(this).setOkHttpClient(builder.build())
        //烤盘初始化 （改到MainActivity中再次初始化）
        //KaoPanHelper.init()
        //网络数据初始化更新
        DataManagementAPI.dataSyncServer()
        //是否初始化，如果没有初始化就先进行初始化， 否则进行初始化
        ParserConfig.getGlobalInstance().isAutoTypeSupport = true
        //bugly 奔溃记录
        CrashReport.initCrashReport(applicationContext, "5905e69ba3", false)
    }
    companion object {
        private var instance: MyApp by Delegates.notNull()
        fun instance() = instance
    }
}