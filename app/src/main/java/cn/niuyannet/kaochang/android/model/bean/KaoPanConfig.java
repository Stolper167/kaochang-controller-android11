package cn.niuyannet.kaochang.android.model.bean;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

import cn.niuyannet.kaochang.android.init.BusinessTime;

/**
 * 烤盘配置数据结构
 */
public class KaoPanConfig implements Serializable {
    @SerializedName("mode_type")
    private int modeType; // 模式类型：1低并发，2高并发

    //营业时间
    @SerializedName("business_time")
    private BusinessTime businessTime;

    @SerializedName("onlineStatus")
    private int onlineStatus;

    @SerializedName("transitionMode")
    private int transitionMode;//过渡模式0是默认，1是从低并发过渡到高并发，2是高并发过渡到低并发

    @SerializedName("errorStatus")
    private int errorStatus;//0没有错误，1是有错误。

    // 默认构造函数
    public KaoPanConfig() {
        this.modeType = 1;

    }

    public BusinessTime getBusinessTime() {
        return businessTime;
    }

    public void setBusinessTime(BusinessTime businessTime) {
        this.businessTime = businessTime;
    }

    public int getOnlineStatus() {
        return onlineStatus;
    }

    public void setOnlineStatus(int onlineStatus) {
        this.onlineStatus = onlineStatus;
    }


    // Getter 和 Setter 方法
    public int getModeType() {
        return modeType;
    }
    
    public void setModeType(int modeType) {
        this.modeType = modeType;
    }
    

}
