package cn.niuyannet.kaochang.android.model.bean;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * 口味数据结构
 */
public class Taste implements Serializable {
    @SerializedName("taste_code")
    private String tasteCode; // 口味编号
    @SerializedName("taste_id")
    private int tasteId; // 口味编号
    @SerializedName("taste_name")
    private String tasteName; // 口味名称

    @SerializedName("product_id")
    private int productId;
    @SerializedName("product_name")
    private String productName;


    public int getTasteId() {
        return tasteId;
    }

    public void setTasteId(int tasteId) {
        this.tasteId = tasteId;
    }
    public int getProductId() {
        return productId;
    }

    public void setProductId(int productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }


    // 默认构造函数
    public Taste() {
        this.tasteCode = "";
        this.tasteName = "";
        this.productId = 0;
        this.productName = "";
    }
    public Taste(String tasteCode) {
        this.tasteCode = tasteCode;
        this.tasteName = "";
        this.productId = 0;
        this.productName = "";
    }
    // 带参数的构造函数
    public Taste(int tasteId,String tasteCode, String tasteName,int productId,String productName) {
        this.tasteId = tasteId;
        this.tasteCode = tasteCode;
        this.tasteName = tasteName;
        this.productId=productId;
        this.productName=productName;
    }
    
    // Getter 和 Setter 方法
    public String getTasteCode() {
        return tasteCode;
    }
    
    public void setTasteCode(String tasteCode) {
        this.tasteCode = tasteCode;
    }
    
    public String getTasteName() {
        return tasteName;
    }
    
    public void setTasteName(String tasteName) {
        this.tasteName = tasteName;
    }
}
