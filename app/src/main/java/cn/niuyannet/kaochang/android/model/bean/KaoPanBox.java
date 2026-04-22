package cn.niuyannet.kaochang.android.model.bean;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * 烤盘箱数据结构
 */
public class KaoPanBox implements Serializable {


    @SerializedName("id")
    private int id;
    @SerializedName("device_id")
    private String deviceId;
    @SerializedName("product_id")
    private String productId ;
    @SerializedName("sku_id")
    private String skuId ;
    @SerializedName("position_sn")
    private int positionSn; // 位置序号 1-22
    @SerializedName("last_position_sn")
    private int lastPositionSn; // 上一次取的位置
    @SerializedName("num")
    private int num; // 当前箱位库存数量，新的加满口径为16根
    @SerializedName("taste_code")
    private String tasteCode; // 口味CODE
    @SerializedName("taste_name")
    private String tasteName; // 口味名称
    @SerializedName("cmd_value_take")
    private int cmdValueTake; // 从烤肠箱取出烤肠 cmd 值【201-222】
    
    @SerializedName("cmd_status_take")
    private int cmdStatusTake; // 命令执行状态 （无符号16位整型，0为正常，1为运动中，其余为错误码）

    @SerializedName("is_check_status")
    private Boolean isCheckStatus; // 是否选中
    // 默认构造函数
    public KaoPanBox() {
        this.id=0;
        this.positionSn = 0;
        this.lastPositionSn = 0;
        this.num = 0;
        this.tasteCode = "";
        this.cmdValueTake = 0;
        this.cmdStatusTake = 0;
        this.isCheckStatus=false;
    }
    // 带参数的构造函数
    public KaoPanBox(int positionSn, int lastPositionSn, int num, 
                    String tasteCode, int cmdValueTake, int cmdStatusTake) {
        this.positionSn = positionSn;
        this.lastPositionSn = lastPositionSn;
        this.num = num;
        this.tasteCode = tasteCode;
        this.cmdValueTake = cmdValueTake;
        this.cmdStatusTake = cmdStatusTake;
    }
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }
    // Getter 和 Setter 方法
    public String getTasteName() {
        return tasteName;
    }

    public void setTasteName(String tasteName) {
        this.tasteName = tasteName;
    }

    public Boolean getCheckStatus() {
        return isCheckStatus;
    }

    public void setCheckStatus(Boolean checkStatus) {
        isCheckStatus = checkStatus;
    }
    public int getPositionSn() {
        return positionSn;
    }
    
    public void setPositionSn(int positionSn) {
        this.positionSn = positionSn;
    }
    
    public int getLastPositionSn() {
        return lastPositionSn;
    }
    
    public void setLastPositionSn(int lastPositionSn) {
        this.lastPositionSn = lastPositionSn;
    }
    
    public int getNum() {
        return num;
    }
    
    public void setNum(int num) {
        this.num = num;
    }
    
    public String getTasteCode() {
        return tasteCode;
    }
    
    public void setTasteCode(String tasteCode) {
        this.tasteCode = tasteCode;
    }
    
    public int getCmdValueTake() {
        return cmdValueTake;
    }
    
    public void setCmdValueTake(int cmdValueTake) {
        this.cmdValueTake = cmdValueTake;
    }
    
    public int getCmdStatusTake() {
        return cmdStatusTake;
    }
    
    public void setCmdStatusTake(int cmdStatusTake) {
        this.cmdStatusTake = cmdStatusTake;
    }
}
