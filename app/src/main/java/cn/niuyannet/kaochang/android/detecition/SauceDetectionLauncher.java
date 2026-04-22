package cn.niuyannet.kaochang.android.detecition;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.opencv.android.OpenCVLoader;

import cn.niuyannet.kaochang.android.ui.SauceDetectionActivity;

/**
 * 烤肠检测启动器。
 * 用于从应用任意位置拉起烤肠检测页面，并在启动前确认 OpenCV 可用。
 */
public class SauceDetectionLauncher {

    /**
     * 启动烤肠检测页面。
     *
     * @param context 上下文
     */
    public static void launch(Context context) {
        try {
            // 先确认 OpenCV 可以正常初始化，避免页面启动后立即报错
            if (!isOpenCVAvailable()) {
                Toast.makeText(context, "OpenCV 库不可用，请确认已正确安装", Toast.LENGTH_LONG).show();
                return;
            }

            Intent intent = new Intent(context, SauceDetectionActivity.class);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "启动烤肠检测失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查 OpenCV 是否可用。
     *
     * @return 是否可用
     */
    public static boolean isOpenCVAvailable() {
        try {
            // 反射检查类是否存在，兼容不同打包方式
            Class.forName("org.opencv.android.OpenCVLoader");
            return OpenCVLoader.initDebug();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
