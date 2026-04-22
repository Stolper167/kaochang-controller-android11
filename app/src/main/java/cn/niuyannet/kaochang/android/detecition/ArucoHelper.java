package cn.niuyannet.kaochang.android.detecition;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.opencv.android.Utils;
import org.opencv.aruco.Aruco;
import org.opencv.aruco.DetectorParameters;
import org.opencv.aruco.Dictionary;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import cn.niuyannet.kaochang.android.R;

/**
 * ArUco 标记检测与相机标定辅助类。
 * 对外保留原有接口，内部增加镜像兜底检测，
 * 兼容 Android 12 外接相机画面可能被水平镜像输出的情况。
 */
public class ArucoHelper {
    private static final String TAG = "ArucoHelper";
    private static final int DEFAULT_WIDTH = 832;
    private static final int DEFAULT_HEIGHT = 448;
    private static final String PREF_NAME = "aruco_helper_prefs";
    private static final String KEY_CORNERS = "corners_data";
    private static final int TARGET_MARKER_ID = 1;

    private final Context context;
    private final SharedPreferences sharedPreferences;
    private Mat cameraMatrix;
    private Mat distCoeffs;

    public ArucoHelper(Context context) {
        this.context = context;
        this.sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadCalibrationData();
    }

    private void loadCalibrationData() {
        try {
            InputStream inputStream = context.getResources().openRawResource(R.raw.calibration_data);
            Map<String, byte[]> npzData = readNpzFile(inputStream);

            if (npzData.containsKey("camera_matrix.npy") && npzData.containsKey("dist_coeffs.npy")) {
                cameraMatrix = parseNumpyArray(npzData.get("camera_matrix.npy"), 3, 3);
                distCoeffs = parseNumpyArray(npzData.get("dist_coeffs.npy"), 1, 5);
                Log.d(TAG, "已从 raw 资源成功加载相机标定数据");
            } else {
                Log.e(TAG, "calibration_data.npz 中缺少必需的标定数据");
            }
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "raw 资源中未找到相机标定文件：" + e.getMessage(), e);
        } catch (IOException e) {
            Log.e(TAG, "加载相机标定数据失败：" + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "加载相机标定数据时发生异常：" + e.getMessage(), e);
        }
    }

    private Map<String, byte[]> readNpzFile(InputStream inputStream) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        ZipInputStream zipInputStream = new ZipInputStream(inputStream);

        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            String name = entry.getName();
            int size = (int) entry.getSize();

            if (size == -1) {
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                byte[] tempBuffer = new byte[1024];
                int bytesRead;

                while ((bytesRead = zipInputStream.read(tempBuffer)) != -1) {
                    if (buffer.position() + bytesRead > buffer.capacity()) {
                        ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
                        buffer.flip();
                        newBuffer.put(buffer);
                        buffer = newBuffer;
                    }
                    buffer.put(tempBuffer, 0, bytesRead);
                }

                buffer.flip();
                byte[] data = new byte[buffer.limit()];
                buffer.get(data);
                result.put(name, data);
            } else {
                byte[] data = new byte[size];
                int offset = 0;
                int bytesRead;
                while (offset < size && (bytesRead = zipInputStream.read(data, offset, size - offset)) != -1) {
                    offset += bytesRead;
                }
                result.put(name, data);
            }

            zipInputStream.closeEntry();
        }

        zipInputStream.close();
        return result;
    }

    private Mat parseNumpyArray(byte[] data, int rows, int cols) {
        int headerSize = 128;
        if (data == null || data.length <= headerSize) {
            Log.e(TAG, "NumPy 数组数据无效");
            return null;
        }

        Mat mat = new Mat(rows, cols, CvType.CV_64F);
        ByteBuffer buffer = ByteBuffer.wrap(data, headerSize, data.length - headerSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        double[] matData = new double[rows * cols];
        for (int i = 0; i < rows * cols; i++) {
            if (buffer.remaining() >= 8) {
                matData[i] = buffer.getDouble();
            }
        }

        mat.put(0, 0, matData);
        return mat;
    }

    public static class DetectionResult {
        public Bitmap resultImage;
        public int markersCount;
        public List<Integer> markerIds;
        public boolean targetFound;
        public List<List<Double>> targetCorners;

        public DetectionResult(Bitmap resultImage) {
            this.resultImage = resultImage;
            this.markersCount = 0;
            this.markerIds = new ArrayList<>();
            this.targetFound = false;
            this.targetCorners = new ArrayList<>();
        }
    }

    public DetectionResult detectArucoMarkers(Bitmap bitmap, int targetId) {
        if (cameraMatrix == null || distCoeffs == null) {
            Log.e(TAG, "相机标定数据未加载，无法执行 ArUco 检测");
            return new DetectionResult(bitmap);
        }

        Mat imageMat = new Mat();
        Utils.bitmapToMat(bitmap, imageMat);

        Mat bgrMat = toBgr(imageMat);
        imageMat.release();
        if (bgrMat == null || bgrMat.empty()) {
            Log.e(TAG, "转换后的 BGR 图像为空");
            if (bgrMat != null) {
                bgrMat.release();
            }
            return new DetectionResult(bitmap);
        }

        Dictionary dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_4X4_50);
        DetectorParameters parameters = buildDetectorParameters();

        DetectionResult result = detectWithFallbackVariants(bgrMat, bitmap, dictionary, parameters, targetId);
        bgrMat.release();
        return result;
    }

    private Mat toBgr(Mat imageMat) {
        if (imageMat == null || imageMat.empty()) {
            return null;
        }

        Mat bgrMat = new Mat();
        int channels = imageMat.channels();
        if (channels == 4) {
            Imgproc.cvtColor(imageMat, bgrMat, Imgproc.COLOR_RGBA2BGR);
        } else if (channels == 1) {
            Imgproc.cvtColor(imageMat, bgrMat, Imgproc.COLOR_GRAY2BGR);
        } else if (channels == 3) {
            bgrMat = imageMat.clone();
        } else {
            Log.w(TAG, "检测到未知图像通道数：" + channels);
            imageMat.copyTo(bgrMat);
        }
        return bgrMat;
    }

    private DetectorParameters buildDetectorParameters() {
        DetectorParameters parameters = DetectorParameters.create();
        parameters.set_adaptiveThreshConstant(4);
        parameters.set_minMarkerPerimeterRate(0.01);
        parameters.set_maxMarkerPerimeterRate(4.0);
        parameters.set_minDistanceToBorder(3);
        return parameters;
    }

    private DetectionResult detectWithFallbackVariants(
        Mat bgrMat,
        Bitmap originalBitmap,
        Dictionary dictionary,
        DetectorParameters parameters,
        int targetId
    ) {
        Mat resizedImage = new Mat();
        Imgproc.resize(bgrMat, resizedImage, new Size(DEFAULT_WIDTH, DEFAULT_HEIGHT));

        DetectionResult directResult = runDetection(resizedImage, originalBitmap, dictionary, parameters, targetId);
        if (directResult.markersCount > 0) {
            Log.d(TAG, "在原始画面中检测到 ArUco 标记");
            resizedImage.release();
            return directResult;
        }

        Mat mirroredImage = new Mat();
        Core.flip(resizedImage, mirroredImage, 1);
        DetectionResult mirroredResult = runDetection(mirroredImage, originalBitmap, dictionary, parameters, targetId);
        if (mirroredResult.markersCount > 0) {
            Log.w(TAG, "仅在水平镜像后检测到 ArUco，外接相机画面很可能被镜像输出");
            resizedImage.release();
            mirroredImage.release();
            return mirroredResult;
        }

        Log.d(TAG, "原始画面和镜像画面都未检测到 ArUco 标记");
        resizedImage.release();
        mirroredImage.release();
        return directResult;
    }

    private DetectionResult runDetection(
        Mat sourceImage,
        Bitmap fallbackBitmap,
        Dictionary dictionary,
        DetectorParameters parameters,
        int targetId
    ) {
        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        List<Mat> rejectedCandidates = new ArrayList<>();
        Aruco.detectMarkers(sourceImage, dictionary, corners, ids, parameters, rejectedCandidates);

        DetectionResult result = new DetectionResult(fallbackBitmap);
        Mat outputImage = sourceImage.clone();

        if (!ids.empty()) {
            Aruco.drawDetectedMarkers(outputImage, corners, ids);
            result.markersCount = ids.rows();

            for (int i = 0; i < ids.rows(); i++) {
                int id = (int) ids.get(i, 0)[0];
                result.markerIds.add(id);

                if (id == targetId || id == TARGET_MARKER_ID) {
                    result.targetFound = true;
                    Mat cornerMat = corners.get(i);
                    saveCorners(cornerMat);
                    appendCornerPoints(cornerMat, result.targetCorners);
                }
            }
        }

        Bitmap resultBitmap = Bitmap.createBitmap(outputImage.cols(), outputImage.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(outputImage, resultBitmap);
        result.resultImage = resultBitmap;

        ids.release();
        outputImage.release();
        for (Mat corner : corners) {
            corner.release();
        }
        for (Mat rejected : rejectedCandidates) {
            rejected.release();
        }

        return result;
    }

    private void appendCornerPoints(Mat cornerMat, List<List<Double>> targetCorners) {
        for (int row = 0; row < cornerMat.rows(); row++) {
            for (int col = 0; col < cornerMat.cols(); col++) {
                List<Double> point = new ArrayList<>();
                double[] values = cornerMat.get(row, col);
                if (values != null) {
                    for (double value : values) {
                        point.add(value);
                    }
                }
                targetCorners.add(point);
            }
        }
    }

    private void saveCorners(Mat cornerMat) {
        try {
            List<List<Double>> cornersList = new ArrayList<>();
            appendCornerPoints(cornerMat, cornersList);

            Gson gson = new Gson();
            String cornersJson = gson.toJson(cornersList);

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(KEY_CORNERS, cornersJson);
            editor.apply();

            Log.d(TAG, "已将角点坐标保存到 SharedPreferences");
        } catch (Exception e) {
            Log.e(TAG, "保存角点坐标失败：" + e.getMessage(), e);
        }
    }

    public Mat loadCorners() {
        try {
            String cornersJson = sharedPreferences.getString(KEY_CORNERS, null);
            if (cornersJson == null) {
                Log.e(TAG, "SharedPreferences 中未找到角点坐标数据");
                return null;
            }

            Gson gson = new Gson();
            Type type = new TypeToken<List<List<Double>>>() {}.getType();
            List<List<Double>> cornersList = gson.fromJson(cornersJson, type);

            if (cornersList == null || cornersList.isEmpty()) {
                Log.e(TAG, "角点坐标数据格式无效");
                return null;
            }

            Mat corners = new Mat(4, 2, CvType.CV_32F);
            float[] cornersData = new float[8];

            int index = 0;
            for (List<Double> point : cornersList) {
                if (point == null) {
                    continue;
                }
                for (Double value : point) {
                    if (index >= cornersData.length) {
                        break;
                    }
                    cornersData[index++] = value.floatValue();
                }
                if (index >= cornersData.length) {
                    break;
                }
            }

            corners.put(0, 0, cornersData);
            return corners;
        } catch (Exception e) {
            Log.e(TAG, "加载角点坐标失败：" + e.getMessage(), e);
            return null;
        }
    }
}
