package cn.niuyannet.kaochang.android.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

object CameraFrameUtils {

    fun imageProxyToMat(imageProxy: ImageProxy, logPrefix: String): Mat? {
        val formatName = imageFormatName(imageProxy.format)
        val rotation = imageProxy.imageInfo.rotationDegrees
        val planeCount = imageProxy.planes.size
        LogUtils.d(
            "$logPrefix 开始转换图像：format=$formatName(${imageProxy.format}), size=${imageProxy.width}x${imageProxy.height}, rotation=$rotation, planes=$planeCount"
        )
        return try {
            val bitmap = imageProxyToBitmap(imageProxy, logPrefix)
            if (bitmap == null) {
                LogUtils.e("$logPrefix 图像转换失败：bmp == null")
                return null
            }

            var mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            if (mat.channels() == 4) {
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_RGBA2BGR)
                mat.release()
                mat = bgrMat
            } else if (mat.channels() == 1) {
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR)
                mat.release()
                mat = bgrMat
            }

            LogUtils.d("$logPrefix 图像转换完成：mat=${mat.width()}x${mat.height()}, channels=${mat.channels()}")
            mat
        } catch (e: Exception) {
            LogUtils.e("$logPrefix 图像转换失败：${e.message}", e)
            null
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy, logPrefix: String): Bitmap? {
        val bitmap = when (imageProxy.format) {
            ImageFormat.JPEG -> decodeJpegBitmap(imageProxy, logPrefix)
            ImageFormat.YUV_420_888 -> decodeYuv420Bitmap(imageProxy, logPrefix)
            else -> {
                LogUtils.e("$logPrefix 不支持的图像格式：${imageFormatName(imageProxy.format)}(${imageProxy.format})")
                null
            }
        } ?: return null

        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) {
            return bitmap
        }

        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun decodeJpegBitmap(imageProxy: ImageProxy, logPrefix: String): Bitmap? {
        val buffer = imageProxy.planes.firstOrNull()?.buffer ?: run {
            LogUtils.e("$logPrefix JPEG 图像没有可用平面")
            return null
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also {
            if (it == null) {
                LogUtils.e("$logPrefix JPEG 解码失败：byteCount=${bytes.size}")
            }
        }
    }

    private fun decodeYuv420Bitmap(imageProxy: ImageProxy, logPrefix: String): Bitmap? {
        val nv21 = yuv420888ToNv21(imageProxy, logPrefix) ?: return null
        val output = ByteArrayOutputStream()
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            95,
            output
        )
        val jpegBytes = output.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size).also {
            if (it == null) {
                LogUtils.e("$logPrefix YUV 转 JPEG 后解码失败：jpegByteCount=${jpegBytes.size}")
            }
        }
    }

    private fun yuv420888ToNv21(imageProxy: ImageProxy, logPrefix: String): ByteArray? {
        val width = imageProxy.width
        val height = imageProxy.height
        val planes = imageProxy.planes
        if (planes.size < 3) {
            LogUtils.e("$logPrefix YUV_420_888 平面数量异常：planes=${planes.size}")
            return null
        }

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        var position = 0
        for (row in 0 until height) {
            val rowOffset = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[position++] = yBuffer.get(rowOffset + col * yPlane.pixelStride)
            }
        }

        for (row in 0 until height / 2) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (col in 0 until width / 2) {
                nv21[position++] = vBuffer.get(vRowOffset + col * vPlane.pixelStride)
                nv21[position++] = uBuffer.get(uRowOffset + col * uPlane.pixelStride)
            }
        }

        LogUtils.d(
            "$logPrefix 已完成 YUV_420_888 -> NV21：yRowStride=${yPlane.rowStride}, uRowStride=${uPlane.rowStride}, vRowStride=${vPlane.rowStride}, yPixelStride=${yPlane.pixelStride}, uPixelStride=${uPlane.pixelStride}, vPixelStride=${vPlane.pixelStride}"
        )
        return nv21
    }

    private fun imageFormatName(format: Int): String {
        return when (format) {
            ImageFormat.JPEG -> "JPEG"
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.NV21 -> "NV21"
            else -> "UNKNOWN"
        }
    }
}
