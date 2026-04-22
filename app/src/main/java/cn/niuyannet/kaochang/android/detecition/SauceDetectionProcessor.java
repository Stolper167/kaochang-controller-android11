package cn.niuyannet.kaochang.android.detecition;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import cn.niuyannet.kaochang.android.R;

/**
 * 烤肠检测处理器
 * 将Python的sauce_detect.py主要功能翻译成Java代码
 */
public class SauceDetectionProcessor {
    private static final String TAG = "SauceDetectionProcessor";

    // 图像尺寸
    private int width = 832;
    private int height = 448;

    // 识别区域标识
    private int roiFlag = 0; // 0: 运输台; 1: 售卖台

    // 相机参数
    private Mat cameraMatrix;
    private MatOfDouble distCoeffs;
    private Mat imgTag0;
    private Mat imgTag1;
    private Mat kaopanRoiMask1; // 运输台掩码
    private Mat kaopanRoiMask2; // 售卖台掩码

    // 上下文
    private Context context;
    
    // ArUco辅助类
    private ArucoHelper arucoHelper;

    // 标定角点坐标
    private MatOfPoint2f corners;
    
    // 标定矩形实际尺寸 (mm)
    private double markerSizeW = 25; 
    private double markerSizeH = 25; 

    /**
     * 构造函数
     *
     * @param context 上下文
     */
    public SauceDetectionProcessor(Context context) {
        this.context = context;
        // 初始化ArUco辅助类
        this.arucoHelper = new ArucoHelper(context);
        // 初始化相机参数
        initCameraParameters();
    }

    /**
     * 设置ROI标志
     *
     * @param roiFlag 0: 运输台; 1: 售卖台
     */
    public void setRoiFlag(int roiFlag) {
        this.roiFlag = roiFlag;
    }

    /**
     * 获取当前ROI标志
     *
     * @return ROI标志 0: 运输台; 1: 售卖台
     */
    public int getRoiFlag() {
        return roiFlag;
    }

    /**
     * 初始化相机参数
     */
    private void initCameraParameters() {
        try {
            // 使用与calibration_data.npz文件中相同的相机参数值
            cameraMatrix = new Mat(3, 3, CvType.CV_64F);
            // 从calibration_data.npz中提取的camera_matrix值
            cameraMatrix.put(0, 0, 1006.8, 0, 416.5);
            cameraMatrix.put(1, 0, 0, 1008.6, 240.3);
            cameraMatrix.put(2, 0, 0, 0, 1.0);
            
            Log.d(TAG, "Camera matrix: " + cameraMatrix.dump());
            
            // 从calibration_data.npz中提取的dist_coeffs值
            double[] distCoeffsValues = {0.1, -0.2, 0.01, 0.02, 0.1};
            distCoeffs = new MatOfDouble(distCoeffsValues);
            
            Log.d(TAG, "Distortion coefficients: " + distCoeffs.dump());
            
            // 加载标记图像
            InputStream isTag0 = context.getResources().openRawResource(R.raw.tag0);
            Bitmap bitmapTag0 = BitmapFactory.decodeStream(isTag0);
            isTag0.close();
            imgTag0 = new Mat();
            Utils.bitmapToMat(bitmapTag0, imgTag0);
            Imgproc.resize(imgTag0, imgTag0, new Size(width, height));
            
            InputStream isTag1 = context.getResources().openRawResource(R.raw.tag1);
            Bitmap bitmapTag1 = BitmapFactory.decodeStream(isTag1);
            isTag1.close();
            imgTag1 = new Mat();
            Utils.bitmapToMat(bitmapTag1, imgTag1);
            Imgproc.resize(imgTag1, imgTag1, new Size(width, height));

            // 创建ROI掩码 - 使用三通道掩码与Python代码保持一致
            kaopanRoiMask1 = new Mat(height, width, CvType.CV_8UC3, new Scalar(0, 0, 0));
            kaopanRoiMask2 = new Mat(height, width, CvType.CV_8UC3, new Scalar(0, 0, 0));

            Log.d(TAG, "Camera parameters loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error loading camera parameters: " + e.getMessage());
            e.printStackTrace();
            
            // 使用默认值
            cameraMatrix = Mat.eye(3, 3, CvType.CV_64F);
            cameraMatrix.put(0, 0, 500.0); // fx
            cameraMatrix.put(1, 1, 500.0); // fy
            cameraMatrix.put(0, 2, width / 2.0); // cx
            cameraMatrix.put(1, 2, height / 2.0); // cy
            
            distCoeffs = new MatOfDouble(0, 0, 0, 0, 0);
            
            // 创建空白标记图像
            imgTag0 = new Mat(height, width, CvType.CV_8UC3, new Scalar(255, 255, 255));
            imgTag1 = new Mat(height, width, CvType.CV_8UC3, new Scalar(255, 255, 255));
            
            // 创建ROI掩码
            kaopanRoiMask1 = Mat.zeros(height, width, CvType.CV_8UC1);
            kaopanRoiMask2 = Mat.zeros(height, width, CvType.CV_8UC1);
        }
    }

    /**
     * 处理图像
     *
     * @param img 输入图像
     * @return 处理结果，包含检测到的烤肠位置和世界坐标
     */
    public DetectionResult processImage(Mat img) {
        // 调整图像大小
        Mat resizedImg = new Mat();
        Imgproc.resize(img, resizedImg, new Size(width, height));

        // 根据ROI标志选择不同的处理流程
        if (roiFlag == 0) {
            return processTransportPlatform(resizedImg);
        } else {
            return processSalesPlatform(resizedImg);
        }
    }

    /**
     * 处理运输台图像
     *
     * @param img 输入图像
     * @return 处理结果
     */
    private DetectionResult processTransportPlatform(Mat img) {
        // 尝试从ArucoHelper获取标定角点坐标
        loadCorners();

        // 世界坐标 - 与Python代码保持一致
        MatOfPoint3f dstPts = new MatOfPoint3f(
                new Point3(0, -markerSizeH, 0),
                new Point3(markerSizeW, -markerSizeH, 0),
                new Point3(markerSizeW, 0, 0),
                new Point3(0, 0, 0)
        );

        // 根据外参估计相机位姿
        Mat rvec = new Mat();
        Mat tvec = new Mat();
        Calib3d.solvePnP(
                dstPts,
                corners,
                cameraMatrix,
                distCoeffs,
                rvec,
                tvec
        );

        // 将旋转向量转换为旋转矩阵
        Mat rMat = new Mat();
        Calib3d.Rodrigues(rvec, rMat);

        // 用于二值化的阈值 - 与Python代码保持一致
        Scalar lower = new Scalar(0, 0, 50);
        Scalar upper = new Scalar(20, 255, 255);

        // 应用ROI掩码 - 与Python代码保持一致的动态计算方式
        updateRoiMasks();
        Mat workPlace = new Mat();
        
        try {
            // 确保掩码与图像尺寸匹配
            if (img.size().equals(kaopanRoiMask1.size())) {
                // 直接将图像与掩码相乘，与Python代码保持一致
                Core.multiply(img, kaopanRoiMask1, workPlace);
                
                // 保存中间结果用于调试
                if (SauceDetector.SAVE_DEBUG_IMAGES) {
                    SauceDetector.saveDebugImage(workPlace, "transport_roi_applied");
                }
            } else {
                Log.e(TAG, "Matrix size mismatch: img(" + img.width() + "x" + img.height() + 
                      "), mask(" + kaopanRoiMask1.width() + "x" + kaopanRoiMask1.height() + ")");
                
                // 如果尺寸不匹配，使用原图像继续处理
                img.copyTo(workPlace);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying ROI mask: " + e.getMessage());
            // 出错时使用原图像继续处理
            img.copyTo(workPlace);
        }

        // 保存处理后的工作区图像用于调试
        if (SauceDetector.SAVE_DEBUG_IMAGES) {
            SauceDetector.saveDebugImage(workPlace, "transport_platform_before_detection");
        }
        
        // 检测烤肠
        Log.d(TAG, "开始检测烤肠，HSV阈值范围: 低阈值=" + lower + ", 高阈值=" + upper);
        SauceDetector.DetectionResult result = SauceDetector.detectSausageCentroid(workPlace, lower, upper, "transport_platform");

        // 处理检测结果
        DetectionResult detectionResult = new DetectionResult();
        if (!result.sausages.isEmpty()) {
            Log.d(TAG, "检测到" + result.sausages.size() + "个烤肠");

            // 处理每个检测到的烤肠
            for (Point sauce : result.sausages) {
                int cx = (int) sauce.x;
                int cy = (int) sauce.y;

                // 像素坐标转世界坐标
                double[] worldCoords = SauceDetector.pixelToWorld(cx, cy, rMat, cameraMatrix, tvec);
                Log.d(TAG, String.format("烤肠像素坐标: (%d, %d), 世界坐标: (%.2f, %.2f, %.2f)",
                        cx, cy, worldCoords[0], worldCoords[1], worldCoords[2]));

                // 添加到结果中
                SausageInfo sausageInfo = new SausageInfo();
                sausageInfo.pixelX = cx;
                sausageInfo.pixelY = cy;
                sausageInfo.worldX = worldCoords[0];
                sausageInfo.worldY = worldCoords[1];
                sausageInfo.worldZ = worldCoords[2];
                detectionResult.sausages.add(sausageInfo);
                
                // 在图像上绘制检测结果
                Imgproc.circle(workPlace, new Point(cx, cy), 10, new Scalar(0, 255, 0), 2);
                Imgproc.putText(workPlace, String.format("(%.1f,%.1f)", worldCoords[0], worldCoords[1]),
                        new Point(cx + 15, cy), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 255, 0), 1);

                // 在图像上标记烤肠位置
                Imgproc.circle(img, new Point(cx, cy), 5, new Scalar(0, 255, 0), -1);
                Imgproc.putText(img, String.format(" %.0f (mm)", worldCoords[1]), 
                        new Point(cx + 60, cy), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, 
                        new Scalar(0, 255, 0), 2);
            }
        }

        // 设置处理后的图像
        detectionResult.processedImage = img;
        return detectionResult;
    }

    /**
     * 处理售卖台图像
     *
     * @param img 输入图像
     * @return 处理结果
     */
    private DetectionResult processSalesPlatform(Mat img) {
        // 尝试从ArucoHelper获取标定角点坐标
        loadCorners();

        // 世界坐标 - 与Python代码保持一致
        MatOfPoint3f dstPts = new MatOfPoint3f(
                new Point3(0, -markerSizeH, 0),
                new Point3(markerSizeW, -markerSizeH, 0),
                new Point3(markerSizeW, 0, 0),
                new Point3(0, 0, 0)
        );

        // 根据外参估计相机位姿
        Mat rvec = new Mat();
        Mat tvec = new Mat();
        Calib3d.solvePnP(
                dstPts,
                corners,
                cameraMatrix,
                distCoeffs,
                rvec,
                tvec
        );

        // 将旋转向量转换为旋转矩阵
        Mat rMat = new Mat();
        Calib3d.Rodrigues(rvec, rMat);

        // 用于二值化的阈值 - 与Python代码保持一致
        Scalar lower = new Scalar(0, 0, 50);
        Scalar upper = new Scalar(15, 255, 255); // 将H通道上限从20改为15，与Python代码保持一致

        // 应用ROI掩码 - 与Python代码保持一致的动态计算方式
        updateRoiMasks();
        Mat workPlace = new Mat();
        
        try {
            // 确保掩码与图像尺寸匹配
            if (img.size().equals(kaopanRoiMask2.size())) {
                // 直接将图像与掩码相乘，与Python代码保持一致
                Core.multiply(img, kaopanRoiMask2, workPlace);
                
                // 保存中间结果用于调试
                if (SauceDetector.SAVE_DEBUG_IMAGES) {
                    SauceDetector.saveDebugImage(workPlace, "sales_roi_applied");
                }
            } else {
                Log.e(TAG, "Matrix size mismatch: img(" + img.width() + "x" + img.height() + 
                      "), mask(" + kaopanRoiMask2.width() + "x" + kaopanRoiMask2.height() + ")");
                
                // 如果尺寸不匹配，使用原图像继续处理
                img.copyTo(workPlace);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying ROI mask: " + e.getMessage());
            // 出错时使用原图像继续处理
            img.copyTo(workPlace);
        }

        // 保存处理后的工作区图像用于调试
        if (SauceDetector.SAVE_DEBUG_IMAGES) {
            SauceDetector.saveDebugImage(workPlace, "sales_platform_before_detection");
        }
        
        // 检测烤肠
        SauceDetector.DetectionResult result = SauceDetector.detectSausageCentroid(workPlace, lower, upper, "sales_platform");

        // 处理检测结果
        DetectionResult detectionResult = new DetectionResult();
        if (!result.sausages.isEmpty()) {
            // 处理每个检测到的烤肠
            for (Point sauce : result.sausages) {
                int cx = (int) sauce.x;
                int cy = (int) sauce.y;

                // 像素坐标转世界坐标
                double[] worldCoords = SauceDetector.pixelToWorld(cx, cy, rMat, cameraMatrix, tvec);

                // 添加到结果中
                SausageInfo sausageInfo = new SausageInfo();
                sausageInfo.pixelX = cx;
                sausageInfo.pixelY = cy;
                sausageInfo.worldX = worldCoords[0];
                sausageInfo.worldY = worldCoords[1];
                sausageInfo.worldZ = worldCoords[2];
                sausageInfo.code = 2;
                detectionResult.sausages.add(sausageInfo);
                
                // 在图像上绘制检测结果
                Imgproc.circle(workPlace, new Point(cx, cy), 10, new Scalar(0, 255, 0), 2);
                Imgproc.putText(workPlace, String.format("(%.1f,%.1f)", worldCoords[0], worldCoords[1]),
                        new Point(cx + 15, cy), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 255, 0), 1);

                // 在图像上标记烤肠位置
                Imgproc.circle(img, new Point(cx, cy), 5, new Scalar(0, 255, 0), -1);
                Imgproc.putText(img, String.format(" %.0f (mm)", worldCoords[1]), 
                        new Point(cx - 10, cy), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, 
                        new Scalar(0, 255, 0), 2);
            }
        }
        else {
            SausageInfo sausageInfo = new SausageInfo();
            sausageInfo.pixelX = 0;
            sausageInfo.pixelY = 0;
            sausageInfo.worldX = 0;
            sausageInfo.worldY = 0;
            sausageInfo.worldZ = 0;
            sausageInfo.code = 1;
            detectionResult.sausages.add(sausageInfo);
        }

        // 设置处理后的图像
        detectionResult.processedImage = img;
        return detectionResult;
    }
    
    /**
     * 从ArucoHelper加载角点坐标
     */
    private void loadCorners() {
        try {
            // 尝试从ArucoHelper获取标定角点坐标
            Mat savedCorners = arucoHelper.loadCorners();
            
            if (savedCorners != null && !savedCorners.empty()) {
                // 检查保存的角点是否有足够的点数
                int pointCount = savedCorners.rows() * savedCorners.cols() / 2; // 每个点有x,y两个值
                
                if (pointCount >= 4) {
                    Log.d(TAG, "使用从ArucoHelper获取的角点坐标，点数: " + pointCount);
                    
                    // 创建包含4个点的MatOfPoint2f
                    Point[] points = new Point[4];
                    float[] cornersData = new float[8]; // 4个点，每个点2个坐标
                    savedCorners.get(0, 0, cornersData);
                    
                    for (int i = 0; i < 4; i++) {
                        points[i] = new Point(cornersData[i*2], cornersData[i*2+1]);
                        Log.d(TAG, "角点 " + i + ": (" + points[i].x + ", " + points[i].y + ")");
                    }
                    
                    corners = new MatOfPoint2f(points);
                } else {
                    Log.e(TAG, "从ArucoHelper获取的角点数量不足，需要4个点，实际点数: " + pointCount);
                    Log.d(TAG, "使用默认角点坐标");
                    // 使用默认角点坐标
                    setDefaultCorners();
                }
            } else {
                Log.d(TAG, "未能从ArucoHelper获取角点坐标，使用默认值");
                // 使用默认角点坐标
                setDefaultCorners();
            }
        } catch (Exception e) {
            Log.e(TAG, "处理角点坐标时出错: " + e.getMessage(), e);
            Log.d(TAG, "使用默认角点坐标");
            // 使用默认角点坐标
            setDefaultCorners();
        }
    }
    
    /**
     * 设置默认角点坐标
     */
    private void setDefaultCorners() {
        corners = new MatOfPoint2f(
                new Point(181, 226),
                new Point(210, 226),
                new Point(199, 418),
                new Point(172, 418)
        );
    }
    
    /**
     * 更新ROI掩码 - 按照Python代码的方式动态计算
     */
    private void updateRoiMasks() {
        try {
            if (corners != null && corners.total() >= 4) {
                // 创建新的掩码
                kaopanRoiMask1 = new Mat(height, width, CvType.CV_8UC3, new Scalar(0, 0, 0));
                kaopanRoiMask2 = new Mat(height, width, CvType.CV_8UC3, new Scalar(0, 0, 0));
                
                // 获取角点3的坐标（Python代码中使用的是corners[3]）
                float[] cornerData = new float[2];
                corners.get(3, 0, cornerData);
                double cornerX = cornerData[0];
                double cornerY = cornerData[1];
                
                // 计算运输台ROI区域 - 与Python代码保持一致
                // kaopan_Roi_mask_1[int(corners[3][1]): int(corners[3][1] + 7.5 * 25), int(corners[3][0] - 2.5 * 25): int(corners[3][0]-8)] = 1
                int h1Start = (int)cornerY;
                int h1End = (int)(cornerY + 7.5 * markerSizeH);
                int w1Start = (int)(cornerX - 3.2 * markerSizeW);
                int w1End = (int)(cornerX - 10);
                
                // 确保坐标在有效范围内
                h1Start = Math.max(0, h1Start);
                h1End = Math.min(height, h1End);
                w1Start = Math.max(0, w1Start);
                w1End = Math.min(width, w1End);
                
                if (h1Start < h1End && w1Start < w1End) {
                    Mat roi1 = kaopanRoiMask1.submat(h1Start, h1End, w1Start, w1End);
                    roi1.setTo(new Scalar(1, 1, 1)); // 三通道掩码，所有通道都设为1
                }
                
                // 计算售卖台ROI区域 - 与Python代码保持一致
                // kaopan_Roi_mask_2[0: int(corners[3][1] - 1.5 * 25), int(corners[3][0] - 2 - 5 * 25): int(corners[3][0] - 4 - 3 * 25)] = 1
                int h2Start = 0;
                int h2End = (int)(cornerY - 1.6 * markerSizeH);
                int w2Start = (int)(cornerX - 2 - 4 * markerSizeW);
                int w2End = (int)(cornerX - 4 - 2.7 * markerSizeW);
                
                // 确保坐标在有效范围内
                h2Start = Math.max(0, h2Start);
                h2End = Math.min(height, h2End);
                w2Start = Math.max(0, w2Start);
                w2End = Math.min(width, w2End);
                
                if (h2Start < h2End && w2Start < w2End) {
                    Mat roi2 = kaopanRoiMask2.submat(h2Start, h2End, w2Start, w2End);
                    roi2.setTo(new Scalar(1, 1, 1)); // 三通道掩码，所有通道都设为1
                }
                
                Log.d(TAG, "ROI masks updated successfully based on corner coordinates");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating ROI masks: " + e.getMessage());
        }
    }

    /**
     * 烤肠信息类
     */
    public static class SausageInfo {
        public int pixelX;      // 像素坐标X
        public int pixelY;      // 像素坐标Y
        public double worldX;   // 世界坐标X
        public double worldY;   // 世界坐标Y
        public double worldZ;   // 世界坐标Z

        public int code = 0;  // 用于售卖台状态，0为拍照错误，1为未识别到烤肠，2为识别到烤肠
    }


    /**
     * 检测结果类
     */
    public static class DetectionResult {
        public List<SausageInfo> sausages = new ArrayList<>();  // 检测到的烤肠列表
        public Mat processedImage;                            // 处理后的图像
    }
}