package cn.niuyannet.kaochang.android.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.InputStream;

import cn.niuyannet.kaochang.android.MyApp;
import cn.niuyannet.kaochang.android.R;
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor;
import cn.niuyannet.kaochang.android.detecition.SauceDetector;

/**
 * 烤肠检测示例活动
 */
public class SauceDetectionActivity_备份 extends AppCompatActivity {
    private static final String TAG = "SauceDetectionActivity";

    private ImageView imageView;
    private TextView resultTextView;
    private Button detectButton;
    private Button transportButton;
    private Button salesButton;


    // 识别区域标识
    private int roiFlag = 0; // 0: 运输台; 1: 售卖台
    
    // 烤肠检测处理器
    private SauceDetectionProcessor processor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sauce_detection);

        // 初始化视图
        imageView = findViewById(R.id.image_view);
        resultTextView = findViewById(R.id.result_text_view);
        detectButton = findViewById(R.id.detect_button);
        transportButton = findViewById(R.id.transport_button);
        salesButton = findViewById(R.id.sales_button);

        // 初始化烤肠检测处理器
        processor = new SauceDetectionProcessor(this);

        // 设置检测按钮点击事件
        detectButton.setOnClickListener(v -> detectSausage());
        
        // 设置运输台按钮点击事件
        transportButton.setOnClickListener(v -> {
            roiFlag = 0;
            processor.setRoiFlag(roiFlag);
            Toast.makeText(this, "已切换到运输台检测模式", Toast.LENGTH_SHORT).show();
            updateButtonState();
        });
        
        // 设置售卖台按钮点击事件
        salesButton.setOnClickListener(v -> {
            roiFlag = 1;
            processor.setRoiFlag(roiFlag);
            Toast.makeText(this, "已切换到售卖台检测模式", Toast.LENGTH_SHORT).show();
            updateButtonState();
        });
        
        // 初始化按钮状态
        updateButtonState();
    }

    /**
     * 更新按钮状态
     */
    private void updateButtonState() {
        if (roiFlag == 0) {
            transportButton.setEnabled(false);
            transportButton.setAlpha(0.5f);
            salesButton.setEnabled(true);
            salesButton.setAlpha(1.0f);
            resultTextView.setText("当前模式：运输台检测");
        } else {
            transportButton.setEnabled(true);
            transportButton.setAlpha(1.0f);
            salesButton.setEnabled(false);
            salesButton.setAlpha(0.5f);
            resultTextView.setText("当前模式：售卖台检测");
        }
    }



    /**
     * 执行烤肠检测
     */
    private void detectSausage() {
        try {
            // 加载测试图像
            // 在实际应用中，这应该是从相机捕获的图像
            Mat img = loadTestImage();
            if (img == null) {
                Toast.makeText(this, "加载测试图像失败", Toast.LENGTH_SHORT).show();
                return;
            }

            // 显示调试信息
            String platformType = processor.getRoiFlag() == 0 ? "运输台" : "售卖台";
            Toast.makeText(this, "正在检测" + platformType + "上的烤肠...", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "开始检测" + platformType + "上的烤肠，图像尺寸: " + img.width() + "x" + img.height() + ", 通道数: " + img.channels());

            // 使用SauceDetectionProcessor处理图像，传递roiFlag参数
            SauceDetectionProcessor.DetectionResult result = processor.processImage(img);
            
            // 显示处理后的图像
            displayImage(result.processedImage);
            
            // 显示检测结果
            if (result.sausages.isEmpty()) {
                resultTextView.setText("未检测到烤肠");
                Log.d(TAG, platformType + "未检测到烤肠");
            } else {
                // 显示第一个检测到的烤肠信息
                SauceDetectionProcessor.SausageInfo sausage = result.sausages.get(0);
                String resultText = String.format("检测到烤肠:\n像素坐标: (%d, %d)\n世界坐标: (%.2f, %.2f, %.2f)",
                        sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ);
                resultTextView.setText(resultText);
                
                Log.d(TAG, platformType + "检测到烤肠: " + result.sausages.size() + "个");
                for (int i = 0; i < result.sausages.size(); i++) {
                    SauceDetectionProcessor.SausageInfo s = result.sausages.get(i);
                    Log.d(TAG, String.format("烤肠 #%d - 像素坐标: (%d, %d), 世界坐标: (%.2f, %.2f, %.2f)",
                            i+1, s.pixelX, s.pixelY, s.worldX, s.worldY, s.worldZ));
                }
            }
            
            // 显示调试图像保存位置
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                String cacheDir = MyApp.Companion.instance().getCacheDir().getAbsolutePath();
                Log.d(TAG, "调试图像已保存到: " + cacheDir);
                Toast.makeText(this, "调试图像已保存到: " + cacheDir, Toast.LENGTH_LONG).show();
            }
            
            // 释放资源
            img.release();
            if (result.processedImage != null && !result.processedImage.equals(img)) {
                result.processedImage.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "检测烤肠时出错: " + e.getMessage());
            Toast.makeText(this, "检测失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 加载测试图像
     */
    private Mat loadTestImage() {
        try {
            // 从资源加载测试图像
            InputStream is = getResources().openRawResource(R.raw.test_image);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();

            // 确保图像格式正确
            Mat img = new Mat();
            Utils.bitmapToMat(bitmap, img);
            
            // 确保图像是BGR格式，与Python代码保持一致
            if (img.channels() == 4) { // RGBA格式
                Mat bgrImg = new Mat();
                Imgproc.cvtColor(img, bgrImg, Imgproc.COLOR_RGBA2BGR);
                img.release();
                img = bgrImg;
            } else if (img.channels() == 1) { // 灰度图
                Mat bgrImg = new Mat();
                Imgproc.cvtColor(img, bgrImg, Imgproc.COLOR_GRAY2BGR);
                img.release();
                img = bgrImg;
            }
            
            // 保存原始图像用于调试
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                SauceDetector.saveDebugImage(img, "original_test_image");
            }
            
            return img;
        } catch (IOException e) {
            Log.e(TAG, "Error loading test image: " + e.getMessage());
            return null;
        }
    }



    /**
     * 显示图像
     */
    private void displayImage(Mat img) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(img.cols(), img.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(img, bitmap);
            imageView.setImageBitmap(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error displaying image: " + e.getMessage());
        }
    }
}