package cn.niuyannet.kaochang.android.mqtt;

import java.math.BigDecimal;

/**
 * 订单明细对象 kc_order_item
 * 
 * @author ruoyi
 * @date 2025-05-12
 */
public class KcOrderItem
{
    private static final long serialVersionUID = 1L;

    /** 明细ID */
    private int id;

    /** 订单ID */
    private int orderId;

    /** 订单编号 */

    private String orderNo;

    /** 商品ID */

    private int productId;

    /** 商品名称 */

    private String productName;

    /** 商品图片 */

    private String productImage;


    /** 商品数量 */

    private int quantity;


    /** 状态（1夹取中 2请取肠 3已完成 4已取消 5退款中） */

    private String status;

    private int productCountFinish;

    private String tasteCode;
    public String getTasteCode() {
        return tasteCode;
    }

    public void setTasteCode(String tasteCode) {
        this.tasteCode = tasteCode;
    }



    public int getProductCountFinish() {
        return productCountFinish;
    }

    public void setProductCountFinish(int productCountFinish) {
        this.productCountFinish = productCountFinish;
    }
    public void setId(int id)
    {
        this.id = id;
    }

    public int getId()
    {
        return id;
    }

    public void setOrderId(int orderId)
    {
        this.orderId = orderId;
    }

    public int getOrderId()
    {
        return orderId;
    }

    public void setOrderNo(String orderNo) 
    {
        this.orderNo = orderNo;
    }

    public String getOrderNo() 
    {
        return orderNo;
    }

    public void setProductId(int productId)
    {
        this.productId = productId;
    }

    public int getProductId()
    {
        return productId;
    }

    public void setProductName(String productName) 
    {
        this.productName = productName;
    }

    public String getProductName() 
    {
        return productName;
    }

    public void setProductImage(String productImage) 
    {
        this.productImage = productImage;
    }

    public String getProductImage() 
    {
        return productImage;
    }


    public void setQuantity(int quantity)
    {
        this.quantity = quantity;
    }

    public int getQuantity()
    {
        return quantity;
    }


    public void setStatus(String status) 
    {
        this.status = status;
    }

    public String getStatus() 
    {
        return status;
    }

}
