package cn.niuyannet.kaochang.android.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import cn.niuyannet.kaochang.android.MyApp;
import cn.niuyannet.kaochang.android.R;
import cn.niuyannet.kaochang.android.detecition.ArucoHelper;
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor;
import cn.niuyannet.kaochang.android.detecition.SauceDetector;
import cn.niuyannet.kaochang.android.services.CameraService;
import cn.niuyannet.kaochang.android.utils.LogUtils;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * 调试识别页面。
 *
 * 这里不再维护第二套 CameraX 生命周期，统一复用 CameraService 进行按需拍照。
 * 当前页面不再提供实时预览，只保留“点击拍照 -> 显示结果图”的交互。
 */
public class SauceDetectionActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private ImageView backButton;
    private ImageView imageView;
    private PreviewView previewView;
    private TextView resultTextView;
    private Button detectButton;
    private Button arUcoButton;
    private Button transportButton;
    private Button salesButton;

    private int roiFlag = 0;
    private SauceDetectionProcessor processor;
    private ArucoHelper arucoHelper;

    private CameraService cameraService;
    private boolean isServiceBound = false;
    private volatile boolean captureInFlight = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CameraService.ClientBinder binder = (CameraService.ClientBinder) service;
            cameraService = binder.getService();
            isServiceBound = true;
            LogUtils.INSTANCE.d("【ArUco检测】已绑定 CameraService，进入按需拍照模式");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            cameraService = null;
            isServiceBound = false;
            LogUtils.INSTANCE.w("【ArUco检测】CameraService 已断开", null);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sauce_detection);

        backButton = findViewById(R.id.btn_back);
        imageView = findViewById(R.id.image_view);
        previewView = findViewById(R.id.preview_view);
        resultTextView = findViewById(R.id.result_text_view);
        detectButton = findViewById(R.id.detect_button);
        arUcoButton = findViewById(R.id.arUco_button);
        transportButton = findViewById(R.id.transport_button);
        salesButton = findViewById(R.id.sales_button);

        arucoHelper = new ArucoHelper(this);
        processor = new SauceDetectionProcessor(this);

        backButton.setOnClickListener(v -> finish());
        detectButton.setOnClickListener(v -> captureAndDetect());
        arUcoButton.setOnClickListener(v -> captureAndDetectAruco());

        transportButton.setOnClickListener(v -> {
            roiFlag = 0;
            processor.setRoiFlag(roiFlag);
            Toast.makeText(this, "已切换到运输台检测模式", Toast.LENGTH_SHORT).show();
            updateButtonState();
        });

        salesButton.setOnClickListener(v -> {
            roiFlag = 1;
            processor.setRoiFlag(roiFlag);
            Toast.makeText(this, "已切换到售卖台检测模式", Toast.LENGTH_SHORT).show();
            updateButtonState();
        });

        updateButtonState();
        showIdleState();

        if (allPermissionsGranted()) {
            bindCameraServiceIfNeeded();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                bindCameraServiceIfNeeded();
            } else {
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allPermissionsGranted()) {
            bindCameraServiceIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseAndUnbindCameraService();
    }

    @Override
    protected void onDestroy() {
        releaseAndUnbindCameraService();
        super.onDestroy();
    }

    private void bindCameraServiceIfNeeded() {
        if (isServiceBound) {
            return;
        }
        Intent intent = new Intent(this, CameraService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void releaseAndUnbindCameraService() {
        if (!isServiceBound) {
            cameraService = null;
            return;
        }
        CameraService boundService = cameraService;
        if (boundService != null) {
            boundService.releaseNow(new Runnable() {
                @Override
                public void run() {
                    safeUnbindCameraService();
                }
            });
        } else {
            safeUnbindCameraService();
        }
    }

    private void safeUnbindCameraService() {
        if (!isServiceBound) {
            return;
        }
        try {
            unbindService(serviceConnection);
        } catch (IllegalArgumentException ignored) {
        }
        isServiceBound = false;
        cameraService = null;
        LogUtils.INSTANCE.d("【ArUco检测】已释放并解绑 CameraService");
    }

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

    private void showIdleState() {
        previewView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);
        resultTextView.setText(
            "当前模式：" + (roiFlag == 0 ? "运输台检测" : "售卖台检测") + "\n" +
                "已切换为按需拍照模式，点击按钮时才会调用相机。"
        );
        detectButton.setText("开始检测");
        detectButton.setOnClickListener(v -> captureAndDetect());
    }

    private void captureAndDetectAruco() {
        Toast.makeText(this, "正在检测 ArUco 标记...", Toast.LENGTH_SHORT).show();
        LogUtils.INSTANCE.d("【ArUco检测】开始拍照执行 ArUco 检测");
        captureBitmapWithPrewarm(new BitmapCaptureCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                runOnUiThread(() -> {
                    previewView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);

                    ArucoHelper.DetectionResult result;
                    try {
                        result = arucoHelper.detectArucoMarkers(bitmap, 1);
                        if (result != null && result.resultImage != null) {
                            imageView.setImageBitmap(result.resultImage);
                        } else {
                            imageView.setImageBitmap(bitmap);
                            resultTextView.setText("ArUco 标记检测失败，请重试");
                            return;
                        }
                    } catch (Exception e) {
                        LogUtils.INSTANCE.e("【ArUco检测】执行检测失败：" + e.getMessage(), e);
                        imageView.setImageBitmap(bitmap);
                        resultTextView.setText("ArUco 标记检测失败：" + e.getMessage());
                        return;
                    }

                    StringBuilder resultText = new StringBuilder();
                    if (result != null && result.markersCount > 0) {
                        resultText.append("检测到 ").append(result.markersCount).append(" 个 ArUco 标记\n");
                        resultText.append("标记 ID: ");
                        for (int i = 0; i < result.markerIds.size(); i++) {
                            resultText.append(result.markerIds.get(i));
                            if (i < result.markerIds.size() - 1) {
                                resultText.append(", ");
                            }
                        }

                        if (result.targetFound) {
                            resultText.append("\n\n目标标记(ID=1) 已找到");
                            if (!result.targetCorners.isEmpty()) {
                                resultText.append("\n角点坐标：");
                                for (int i = 0; i < result.targetCorners.size() && i < 4; i++) {
                                    List<Double> point = result.targetCorners.get(i);
                                    if (point.size() >= 2) {
                                        resultText.append("\n").append(i + 1).append(": (")
                                            .append(String.format("%.1f", point.get(0))).append(", ")
                                            .append(String.format("%.1f", point.get(1))).append(")");
                                    }
                                }
                            }
                        } else {
                            resultText.append("\n\n未找到目标标记(ID=1)");
                        }
                    } else {
                        resultText.append("未检测到任何 ArUco 标记");
                    }

                    resultTextView.setText(resultText.toString());
                    LogUtils.INSTANCE.d("【ArUco检测】检测完成：" + resultText);
                    detectButton.setText("返回待拍照");
                    detectButton.setOnClickListener(v -> showIdleState());
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                    Toast.makeText(SauceDetectionActivity.this, "ArUco 拍照失败：" + message, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void captureAndDetect() {
        Toast.makeText(this, "正在拍照...", Toast.LENGTH_SHORT).show();
        LogUtils.INSTANCE.d("【ArUco检测】开始拍照执行" + (roiFlag == 0 ? "运输台" : "售卖台") + "检测");
        captureBitmapWithPrewarm(new BitmapCaptureCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                Mat img = bitmapToBgrMat(bitmap);
                if (img == null) {
                    runOnUiThread(() ->
                        Toast.makeText(SauceDetectionActivity.this, "图像处理失败", Toast.LENGTH_SHORT).show()
                    );
                    return;
                }

                runOnUiThread(() -> {
                    previewView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    detectSausage(bitmap, img);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                    Toast.makeText(SauceDetectionActivity.this, "拍照失败：" + message, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void captureBitmap(BitmapCaptureCallback callback) {
        CameraService boundService = cameraService;
        if (boundService == null || !isServiceBound) {
            bindCameraServiceIfNeeded();
            callback.onError("相机服务尚未就绪，请稍后重试");
            return;
        }

        boundService.takePhoto(new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                try {
                    Bitmap bitmap = imageProxyToBitmap(imageProxy);
                    if (bitmap == null) {
                        callback.onError("图像转换失败");
                    } else {
                        callback.onSuccess(bitmap);
                    }
                } catch (Exception e) {
                    LogUtils.INSTANCE.e("【ArUco检测】拍照成功后处理图像失败：" + e.getMessage(), e);
                    callback.onError("图像处理失败：" + e.getMessage());
                } finally {
                    imageProxy.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                LogUtils.INSTANCE.e("【ArUco检测】拍照失败：" + exception.getMessage(), exception);
                callback.onError(exception.getMessage() == null ? "未知错误" : exception.getMessage());
            }
        });
    }

    private void captureBitmapWithPrewarm(BitmapCaptureCallback callback) {
        CameraService boundService = cameraService;
        if (boundService == null || !isServiceBound) {
            bindCameraServiceIfNeeded();
            callback.onError("相机服务尚未就绪，请稍后重试");
            return;
        }

        if (captureInFlight) {
            callback.onError("相机正在处理中，请稍后再试");
            return;
        }
        captureInFlight = true;

        boundService.prewarm(1500L, new Function1<Boolean, Unit>() {
            @Override
            public Unit invoke(Boolean success) {
                if (!success) {
                    captureInFlight = false;
                    callback.onError("相机预热失败，请稍后重试");
                    return Unit.INSTANCE;
                }

                boundService.takePhoto(new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        try {
                            Bitmap bitmap = imageProxyToBitmap(imageProxy);
                            if (bitmap == null) {
                                callback.onError("图像转换失败");
                            } else {
                                callback.onSuccess(bitmap);
                            }
                        } catch (Exception e) {
                            LogUtils.INSTANCE.e("【ArUco检测】拍照成功后处理图像失败：" + e.getMessage(), e);
                            callback.onError("图像处理失败：" + e.getMessage());
                        } finally {
                            captureInFlight = false;
                            imageProxy.close();
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        captureInFlight = false;
                        LogUtils.INSTANCE.e("【ArUco检测】拍照失败：" + exception.getMessage(), exception);
                        callback.onError(exception.getMessage() == null ? "未知错误" : exception.getMessage());
                    }
                });
                return Unit.INSTANCE;
            }
        });
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            Bitmap bitmap;
            if (imageProxy.getFormat() == ImageFormat.JPEG) {
                ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } else if (imageProxy.getFormat() == ImageFormat.YUV_420_888) {
                byte[] nv21 = yuv420888ToNv21(imageProxy);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                YuvImage yuvImage = new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(),
                    null
                );
                yuvImage.compressToJpeg(
                    new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
                    95,
                    output
                );
                byte[] jpegBytes = output.toByteArray();
                bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            } else {
                LogUtils.INSTANCE.e("【ArUco检测】不支持的图像格式：" + imageProxy.getFormat(), null);
                return null;
            }
            if (bitmap == null) {
                return null;
            }
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            return bitmap;
        } catch (Exception e) {
            LogUtils.INSTANCE.e("【ArUco检测】ImageProxy 转 Bitmap 失败：" + e.getMessage(), e);
            return null;
        }
    }

    private byte[] yuv420888ToNv21(ImageProxy imageProxy) {
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;
        byte[] nv21 = new byte[ySize + uvSize * 2];

        ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = imageProxy.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = imageProxy.getPlanes()[2];

        int position = 0;
        for (int row = 0; row < height; row++) {
            int rowOffset = row * yPlane.getRowStride();
            for (int col = 0; col < width; col++) {
                nv21[position++] = yPlane.getBuffer().get(rowOffset + col * yPlane.getPixelStride());
            }
        }

        for (int row = 0; row < height / 2; row++) {
            int uRowOffset = row * uPlane.getRowStride();
            int vRowOffset = row * vPlane.getRowStride();
            for (int col = 0; col < width / 2; col++) {
                nv21[position++] = vPlane.getBuffer().get(vRowOffset + col * vPlane.getPixelStride());
                nv21[position++] = uPlane.getBuffer().get(uRowOffset + col * uPlane.getPixelStride());
            }
        }
        return nv21;
    }

    private Mat bitmapToBgrMat(Bitmap bitmap) {
        try {
            Mat mat = new Mat();
            Utils.bitmapToMat(bitmap, mat);

            if (mat.channels() == 4) {
                Mat bgrMat = new Mat();
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_RGBA2BGR);
                mat.release();
                mat = bgrMat;
            } else if (mat.channels() == 1) {
                Mat bgrMat = new Mat();
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR);
                mat.release();
                mat = bgrMat;
            }

            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                SauceDetector.saveDebugImage(mat, "camera_captured_image");
            }
            return mat;
        } catch (Exception e) {
            LogUtils.INSTANCE.e("【ArUco检测】Bitmap 转 Mat 失败：" + e.getMessage(), e);
            return null;
        }
    }

    private void detectSausage(Bitmap capturedBitmap, Mat img) {
        try {
            String platformType = processor.getRoiFlag() == 0 ? "运输台" : "售卖台";
            LogUtils.INSTANCE.d("【ArUco检测】开始检测" + platformType + "上的烤肠");

            SauceDetectionProcessor.DetectionResult result = processor.processImage(img);

            if (result.processedImage != null) {
                displayImage(result.processedImage);
            } else {
                imageView.setImageBitmap(capturedBitmap);
            }

            if (result.sausages.isEmpty()) {
                resultTextView.setText("未检测到烤肠");
                LogUtils.INSTANCE.d("【ArUco检测】" + platformType + "未检测到烤肠");
            } else {
                SauceDetectionProcessor.SausageInfo sausage = result.sausages.get(0);
                String resultText = String.format(
                    "检测到烤肠:%n像素坐标: (%d, %d)%n世界坐标: (%.2f, %.2f, %.2f)",
                    sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                );
                resultTextView.setText(resultText);
                LogUtils.INSTANCE.d("【ArUco检测】" + platformType + "检测到烤肠，count=" + result.sausages.size());
            }

            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                String cacheDir = MyApp.Companion.instance().getCacheDir().getAbsolutePath();
                LogUtils.INSTANCE.d("【ArUco检测】调试图像已保存到：" + cacheDir);
            }

            img.release();
            if (result.processedImage != null && result.processedImage != img) {
                result.processedImage.release();
            }
            detectButton.setText("返回待拍照");
            detectButton.setOnClickListener(v -> showIdleState());
        } catch (Exception e) {
            LogUtils.INSTANCE.e("【ArUco检测】检测烤肠失败：" + e.getMessage(), e);
            Toast.makeText(this, "检测失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            img.release();
        }
    }

    private void displayImage(Mat img) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(img.cols(), img.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(img, bitmap);
            imageView.setImageBitmap(bitmap);
        } catch (Exception e) {
            LogUtils.INSTANCE.e("【ArUco检测】显示图像失败：" + e.getMessage(), e);
        }
    }

    private interface BitmapCaptureCallback {
        void onSuccess(Bitmap bitmap);

        void onError(String message);
    }
}
