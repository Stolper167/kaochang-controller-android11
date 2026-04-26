package cn.niuyannet.kaochang.android.detecition;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 烤肠检测工具类
 * 将Python的sauce_detect.py翻译成Java代码
 */
public class SauceDetector {
    private static final String TAG = "SauceDetector";
    
    // 是否保存中间处理结果的图像用于调试
    public static final boolean SAVE_DEBUG_IMAGES = true;
    private static final String DEBUG_IMAGE_DIR_NAME = "vision_debug";

    public static class DetectionTuning {
        public final int kernelSize;
        public final int openIterations;
        public final int closeIterations;
        public final double minArea;
        public final double maxArea;
        public final double minAspectRatio;
        public final double maxAspectRatio;
        public final int minCenterX;
        public final String label;

        public DetectionTuning(
                int kernelSize,
                int openIterations,
                int closeIterations,
                double minArea,
                double maxArea,
                double minAspectRatio,
                double maxAspectRatio,
                int minCenterX,
                String label
        ) {
            this.kernelSize = kernelSize;
            this.openIterations = openIterations;
            this.closeIterations = closeIterations;
            this.minArea = minArea;
            this.maxArea = maxArea;
            this.minAspectRatio = minAspectRatio;
            this.maxAspectRatio = maxAspectRatio;
            this.minCenterX = minCenterX;
            this.label = label;
        }
    }

    public static final DetectionTuning DEFAULT_TUNING = new DetectionTuning(
            4,
            4,
            2,
            400,
            7000,
            0.25,
            2.0,
            65,
            "default"
    );

    public static final DetectionTuning SALES_PLATFORM_TUNING = new DetectionTuning(
            3,
            2,
            2,
            180,
            12000,
            0.12,
            4.0,
            0,
            "sales_platform_relaxed"
    );

    public static final DetectionTuning SALES_PLATFORM_FALLBACK_TUNING = new DetectionTuning(
            3,
            1,
            1,
            90,
            16000,
            0.08,
            6.0,
            0,
            "sales_platform_fallback"
    );

    static {
        // 初始化OpenCV库
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed");
        } else {
            Log.d(TAG, "OpenCV initialization succeeded");
        }
    }
    
    /**
     * 保存Mat图像用于调试
     * 
     * @param mat 要保存的Mat
     * @param fileName 文件名
     */
    public static void saveDebugImage(Mat mat, String fileName) {
        if (!SAVE_DEBUG_IMAGES) {
            return;
        }
        
        try {
            // 创建位图
            Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
            
            // 如果是单通道图像，转换为三通道
            Mat matToSave = mat;
            if (mat.channels() == 1) {
                matToSave = new Mat();
                Imgproc.cvtColor(mat, matToSave, Imgproc.COLOR_GRAY2RGBA);
            }
            
            // 转换为位图
            Utils.matToBitmap(matToSave, bitmap);
            
            // 保存到固定目录，文件名带时间戳，便于一次测试保留完整中间过程。
            File debugDir = getDebugImageDir();
            String actualFileName = System.currentTimeMillis() + "_" + fileName + ".jpg";
            File file = new File(debugDir, actualFileName);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
            
            Log.i(TAG, "【视觉调试】保存调试图像：name=" + actualFileName + "，path=" + file.getAbsolutePath());
            
            // 释放资源
            if (matToSave != mat) {
                matToSave.release();
            }
            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "【视觉调试】保存调试图像失败：" + e.getMessage(), e);
        }
    }

    public static String getDebugImageDirPath() {
        return getDebugImageDir().getAbsolutePath();
    }

    private static File getDebugImageDir() {
        return new File(com.blankj.utilcode.util.Utils.getApp().getCacheDir(), DEBUG_IMAGE_DIR_NAME);
    }

    /**
     * 应用自适应直方图均衡化（CLAHE）
     * 与Python代码保持一致：
     * def apply_clahe(img):
     *     clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
     *     img[:, :, 2] = clahe.apply(img[:, :, 2])
     *     return img
     *
     * @param img 输入图像
     * @return 处理后的图像
     */
    public static Mat applyClahe(Mat img) {
        try {
            // 创建CLAHE对象，与Python代码保持一致
            CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
            
            // 分离通道
            List<Mat> channels = new ArrayList<>();
            Core.split(img, channels);
            
            // 对V通道应用CLAHE
            clahe.apply(channels.get(2), channels.get(2));
            
            // 保存中间结果用于调试
            if (SAVE_DEBUG_IMAGES) {
                Mat vChannel = new Mat();
                channels.get(2).copyTo(vChannel);
                saveDebugImage(vChannel, "clahe_v_channel");
                vChannel.release();
            }
            
            // 合并通道
            Core.merge(channels, img);
            
            // 释放通道资源
            for (Mat channel : channels) {
                channel.release();
            }
            
            return img;
        } catch (Exception e) {
            Log.e(TAG, "应用CLAHE失败: " + e.getMessage());
            return img;
        }
    }

    /**
     * 检测烤肠质心
     *
     * @param frame 输入图像
     * @param lower 低阈值
     * @param upper 高阈值
     * @param imgTag 图像标识符，用于调试图像的文件名
     * @return 烤肠质心坐标列表和掩码
     */
    public static class DetectionResult {
        public List<Point> sausages;
        public Mat mask;

        public DetectionResult(List<Point> sausages, Mat mask) {
            this.sausages = sausages;
            this.mask = mask;
        }
    }

    public static DetectionResult detectSausageCentroid(Mat frame, Scalar lower, Scalar upper, String imgTag) {
        return detectSausageCentroid(frame, lower, upper, imgTag, DEFAULT_TUNING);
    }

    public static DetectionResult detectSausageCentroid(
            Mat frame,
            Scalar lower,
            Scalar upper,
            String imgTag,
            DetectionTuning tuning
    ) {
        // 使用传入的字符串标识符，如果为空则使用默认值
        String debugTag = (imgTag != null && !imgTag.isEmpty()) ? imgTag : "sauce_detect";
        DetectionTuning effectiveTuning = tuning != null ? tuning : DEFAULT_TUNING;
        
        // 添加调试日志
        Log.d(TAG, "开始检测烤肠，输入图像尺寸: " + frame.width() + "x" + frame.height() + ", 通道数: " + frame.channels());
        Log.d(TAG, "HSV阈值范围: 低阈值=" + lower.toString() + ", 高阈值=" + upper.toString());
        Log.d(
                TAG,
                "检测调参: label=" + effectiveTuning.label +
                        ", kernelSize=" + effectiveTuning.kernelSize +
                        ", openIterations=" + effectiveTuning.openIterations +
                        ", closeIterations=" + effectiveTuning.closeIterations +
                        ", areaRange=[" + effectiveTuning.minArea + "," + effectiveTuning.maxArea + "]" +
                        ", aspectRatioRange=[" + effectiveTuning.minAspectRatio + "," + effectiveTuning.maxAspectRatio + "]" +
                        ", minCenterX=" + effectiveTuning.minCenterX
        );
        
        // 保存输入图像
        saveDebugImage(frame, debugTag + "_1_input");
        
        // 高斯去模糊（去噪）
        Mat blur = new Mat();
        Imgproc.GaussianBlur(frame, blur, new Size(5, 5), 0);
        saveDebugImage(blur, debugTag + "_2_blur");
        
        // BGR转HSV
        Mat hsv = new Mat();
        Imgproc.cvtColor(blur, hsv, Imgproc.COLOR_BGR2HSV);
        Log.d(TAG, "HSV转换完成，图像尺寸: " + hsv.width() + "x" + hsv.height() + ", 通道数: " + hsv.channels());
        saveDebugImage(hsv, debugTag + "_3_hsv");
        
        // 直方图均衡化
        hsv = applyClahe(hsv);
        Log.d(TAG, "CLAHE应用完成");
        saveDebugImage(hsv, debugTag + "_4_clahe");
        
        // 二值化 - 确保与Python代码完全一致
        Mat mask = new Mat();
        Core.inRange(hsv, lower, upper, mask);
        Log.d(TAG, "二值化完成，掩码尺寸: " + mask.width() + "x" + mask.height() + ", 通道数: " + mask.channels());
        Log.d(TAG, "二值化阈值: lower=" + lower.toString() + ", upper=" + upper.toString());
        saveDebugImage(mask, debugTag + "_5_binary_mask");
        
        // 形态学处理 - 确保与Python代码完全一致
        int kernelSize = effectiveTuning.kernelSize;
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(kernelSize, kernelSize));
        Log.d(TAG, "形态学处理核大小: " + kernelSize);
        
        // 通过开运算和闭运算去除噪点、分离粘连 - 与Python代码保持一致
        // 开运算4次
        for (int i = 0; i < effectiveTuning.openIterations; i++) {
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);
        }
        saveDebugImage(mask, debugTag + "_6_after_opening");
        
        // 闭运算2次
        for (int i = 0; i < effectiveTuning.closeIterations; i++) {
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
        }
        Log.d(
                TAG,
                "形态学处理完成（开运算" + effectiveTuning.openIterations +
                        "次，闭运算" + effectiveTuning.closeIterations + "次）"
        );
        saveDebugImage(mask, debugTag + "_7_after_closing");
        
        // 寻找轮廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        Log.d(TAG, "找到轮廓数量: " + contours.size());
        
        // 创建一个彩色图像用于绘制轮廓
        Mat contoursImage = new Mat();
        frame.copyTo(contoursImage);
        Imgproc.drawContours(contoursImage, contours, -1, new Scalar(0, 0, 255), 2);
        saveDebugImage(contoursImage, debugTag + "_8_contours");
        contoursImage.release();
        
        // 筛选与计数 - 确保与Python代码完全一致
        List<Point> sausages = new ArrayList<>();
        if (contours.isEmpty()) {
            Log.d(TAG, "未找到任何轮廓");
            return new DetectionResult(sausages, mask);
        }
        
        // 创建一个彩色图像用于绘制筛选后的轮廓和质心
        Mat resultImage = new Mat();
        frame.copyTo(resultImage);
        
        for (MatOfPoint cnt : contours) {
            // 轮廓面积
            double area = Imgproc.contourArea(cnt);
            Rect rect = Imgproc.boundingRect(cnt);
            
            // 长宽比
            double aspectRatio = rect.width / (float) rect.height;
            
            // 记录所有轮廓的信息用于调试
            Log.d(TAG, "轮廓信息: 面积=" + area + ", 长宽比=" + aspectRatio + ", 位置=" + rect.x + "," + rect.y + "," + rect.width + "," + rect.height);
            
            if (effectiveTuning.minArea < area &&
                    area < effectiveTuning.maxArea &&
                    effectiveTuning.minAspectRatio < aspectRatio &&
                    aspectRatio < effectiveTuning.maxAspectRatio) {
                // 根据一阶矩和二阶矩计算中心点
                Moments M = Imgproc.moments(cnt);
                // 完全按照Python代码的方式计算质心
                int cx = (int) (M.get_m10() / M.get_m00()) - kernelSize;
                int cy = (int) (M.get_m01() / M.get_m00()) - kernelSize / 2;
                
                if (cx > effectiveTuning.minCenterX) {
                    sausages.add(new Point(cx, cy));
                    
                    // 在结果图像上绘制轮廓和质心
                    Imgproc.drawContours(resultImage, Arrays.asList(cnt), -1, new Scalar(0, 255, 255), 2);
                    Imgproc.circle(resultImage, new Point(cx, cy), 5, new Scalar(0, 255, 0), -1);
                    
                    // 添加详细调试日志
                    Log.d(TAG, "检测到烤肠: cx=" + cx + ", cy=" + cy + ", 面积=" + area + ", 长宽比=" + aspectRatio);
                    Log.d(TAG, "原始质心: x=" + (M.get_m10() / M.get_m00()) + ", y=" + (M.get_m01() / M.get_m00()));
                } else {
                    Log.d(TAG, "轮廓因质心X过小被过滤: cx=" + cx + ", minCenterX=" + effectiveTuning.minCenterX);
                }
            } else {
                Log.d(
                        TAG,
                        "轮廓未通过筛选: area=" + area +
                                ", aspectRatio=" + aspectRatio +
                                ", requiredArea=[" + effectiveTuning.minArea + "," + effectiveTuning.maxArea + "]" +
                                ", requiredAspectRatio=[" + effectiveTuning.minAspectRatio + "," + effectiveTuning.maxAspectRatio + "]"
                );
            }
        }
        
        // 保存筛选后的结果图像
        saveDebugImage(resultImage, debugTag + "_9_filtered_contours");
        resultImage.release();
        
        return new DetectionResult(sausages, mask);
    }

    /**
     * 像素坐标转世界坐标
     *
     * @param u 像素x坐标
     * @param v 像素y坐标
     * @param rMat 旋转矩阵
     * @param cameraMatrix 相机内参矩阵
     * @param t 平移向量
     * @return 世界坐标
     */
    public static double[] pixelToWorld(int u, int v, Mat rMat, Mat cameraMatrix, Mat t) {
        try {
            // 添加详细日志，记录输入参数
            Log.d(TAG, "pixelToWorld输入: u=" + u + ", v=" + v);
            Log.d(TAG, "相机矩阵: " + cameraMatrix.dump());
            Log.d(TAG, "旋转矩阵: " + rMat.dump());
            Log.d(TAG, "平移向量: " + t.dump());
            
            // 确保所有矩阵使用相同的数据类型 CV_64F
            int matType = CvType.CV_64F;
            
            // 创建像素坐标点
            Mat pt = Mat.zeros(3, 1, matType);
            pt.put(0, 0, (double)u);
            pt.put(1, 0, (double)v);
            pt.put(2, 0, 1.0);
            Log.d(TAG, "像素坐标点: " + pt.dump());
            
            // 确保相机矩阵是正确的类型
            Mat camMatDouble = new Mat();
            cameraMatrix.convertTo(camMatDouble, matType);
            
            // 计算相机内参矩阵的逆
            Mat camMatInv = new Mat();
            Core.invert(camMatDouble, camMatInv);
            Log.d(TAG, "相机矩阵逆: " + camMatInv.dump());
            
            // 计算相机坐标系下射线方向
            Mat dc = new Mat();
            Core.gemm(camMatInv, pt, 1, new Mat(), 0, dc);
            Log.d(TAG, "相机坐标系下射线方向: " + dc.dump());
            
            // 确保旋转矩阵是正确的类型
            Mat rMatDouble = new Mat();
            rMat.convertTo(rMatDouble, matType);
            
            // 计算 R 的转置
            Mat rT = new Mat();
            Core.transpose(rMatDouble, rT);
            Log.d(TAG, "旋转矩阵转置: " + rT.dump());
            
            // 确保平移向量是正确的类型
            Mat tDouble = new Mat();
            t.convertTo(tDouble, matType);
            
            // 计算 s，使得 X_w(s) 在平面 Z=0
            // 世界->相机: X_c = R * X_w + t, 所以 X_w = R^T (X_c - t)
            // 平面法向量 n=[0,0,1], d=0，公式：
            // s = ([R^T t]_z) / ([R^T d_c]_z)
            Mat rTt = new Mat();
            Core.gemm(rT, tDouble, 1, new Mat(), 0, rTt);
            double num = rTt.get(2, 0)[0];
            Log.d(TAG, "R^T * t 的 z 分量: " + num);
            
            Mat rTdc = new Mat();
            Core.gemm(rT, dc, 1, new Mat(), 0, rTdc);
            double den = rTdc.get(2, 0)[0];
            Log.d(TAG, "R^T * dc 的 z 分量: " + den);
            
            if (Math.abs(den) < 1e-6) {
                throw new IllegalArgumentException("射线与平面平行或数值不稳定");
            }
            
            double s = num / den;
            Log.d(TAG, "计算的比例因子 s: " + s);
            
            // 相机坐标系下的三维点
            Mat xc = new Mat();
            Core.multiply(dc, Scalar.all(s), xc);
            Log.d(TAG, "相机坐标系下的三维点: " + xc.dump());
            
            // 计算 X_c - t
            Mat xcMinusT = new Mat();
            Core.subtract(xc, tDouble, xcMinusT);
            Log.d(TAG, "X_c - t: " + xcMinusT.dump());
            
            // 世界坐标系下的点
            Mat xw = new Mat();
            Core.gemm(rT, xcMinusT, 1, new Mat(), 0, xw);
            Log.d(TAG, "世界坐标系下的点: " + xw.dump());
            
            // 返回世界坐标
            double[] worldCoords = new double[3];
            worldCoords[0] = xw.get(0, 0)[0];
            worldCoords[1] = xw.get(1, 0)[0];
            worldCoords[2] = xw.get(2, 0)[0];
            
            Log.d(TAG, "最终世界坐标: [" + worldCoords[0] + ", " + worldCoords[1] + ", " + worldCoords[2] + "]");
            return worldCoords;
        } catch (Exception e) {
            Log.e(TAG, "Error in pixelToWorld: " + e.getMessage());
            e.printStackTrace();
            // 返回默认值
            return new double[]{0, 0, 0};
        }
    }
}
