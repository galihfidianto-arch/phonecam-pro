package com.gsh.phonecam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Camera2 engine dengan ImageReader YUV→JPEG (MJPEG server).
 * Fitur Pro lengkap:
 * - switch kamera depan/belakang
 * - torch on/off
 * - zoom digital (crop scaler)
 * - pilih resolusi 720p / 1080p / 4K
 */
class CameraEngine(private val ctx: Context) {

    @Volatile var latestJpeg: ByteArray? = null
        private set
    @Volatile var fps = 0.0
        private set
    @Volatile var resolution = ""
        private set

    @Volatile var useFront = Prefs.useFrontCamera
    @Volatile var torch = Prefs.torch
    @Volatile private var zoom = 1f

    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var targetSize: Size = Size(1920, 1080)
    private val frameTimes = ConcurrentLinkedQueue<Long>()

    fun start() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("cam").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
        openCamera()
    }

    fun release() {
        try {
            captureSession?.close(); captureSession = null
            imageReader?.close(); imageReader = null
            cameraDevice?.close(); cameraDevice = null
        } catch (_: Exception) {}
    }

    fun fullStop() {
        release()
        try { backgroundThread?.quitSafely() } catch (_: Exception) {}
        backgroundThread = null; backgroundHandler = null
    }

    private fun cameraId(): String = cameraManager.cameraIdList.firstOrNull { id ->
        val f = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        f == (if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK)
    } ?: cameraManager.cameraIdList.first()

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = cameraId()
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val wanted = when (Prefs.quality) {
            0 -> listOf(Size(1280, 720))
            2 -> listOf(Size(3840, 2160), Size(2560, 1440), Size(1920, 1080))
            else -> listOf(Size(1920, 1080))
        }
        targetSize = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.firstOrNull { s -> wanted.any { w -> s.width == w.width && s.height == w.height } }
            ?: map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.filter { it.width <= 3840 }
                ?.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
        resolution = "${targetSize.width}x${targetSize.height}"

        imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val jpeg = yuvToJpeg(image)
                if (jpeg != null) {
                    latestJpeg = jpeg
                    val now = System.currentTimeMillis()
                    frameTimes.add(now)
                    while (frameTimes.size > 30) frameTimes.poll()
                    val oldest = frameTimes.peek() ?: now
                    val dt = (now - oldest).coerceAtLeast(1)
                    fps = if (frameTimes.size > 1) (frameTimes.size - 1) * 1000.0 / dt else 0.0
                }
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, backgroundHandler)

        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                startSession()
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
        }, backgroundHandler)
    }

    @SuppressLint("Recycle")
    private fun startSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val surface = reader.surface

        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        if (torch) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
    }

    /** Konversi YUV_420_888 → JPEG (dengan zoom digital crop + mirror kamera depan). */
    private fun yuvToJpeg(image: android.media.Image): ByteArray? {
        val w = image.width
        val h = image.height
        val yBuf = image.planes[0].buffer
        val uBuf = image.planes[1].buffer
        val vBuf = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixStride = image.planes[1].pixelStride

        val nv21 = ByteArray(w * h * 3 / 2)
        // Salin Y
        var pos = 0
        if (yRowStride == w) {
            yBuf.get(nv21, 0, w * h); pos = w * h
        } else {
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, pos, w); pos += w
            }
        }
        // Salin VU interleaved (NV21)
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val vIndex = row * uvRowStride + col * uvPixStride
                val uIndex = row * uvRowStride + col * uvPixStride
                nv21[pos++] = vBuf.get(vIndex)
                nv21[pos++] = uBuf.get(uIndex)
            }
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), 82, out)
        var jpegBytes = out.toByteArray()

        // Zoom digital: crop tengah lalu scale kembali
        if (zoom > 1.01f) {
            val opts = BitmapFactory.Options()
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return jpegBytes
            val cw = (bmp.width / zoom).toInt()
            val ch = (bmp.height / zoom).toInt()
            val x = (bmp.width - cw) / 2
            val y = (bmp.height - ch) / 2
            val cropped = Bitmap.createBitmap(bmp, x, y, cw, ch)
            val scaled = Bitmap.createScaledBitmap(cropped, bmp.width, bmp.height, true)
            val bout = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, bout)
            if (bmp != scaled && bmp != cropped) bmp.recycle()
            cropped.recycle(); scaled.recycle()
            jpegBytes = bout.toByteArray()
        }

        // Mirror untuk kamera depan
        if (useFront) {
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
            val m = Matrix().apply { postScale(-1f, 1f, bmp.width / 2f, bmp.height / 2f) }
            val mirrored = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val bout = ByteArrayOutputStream()
            mirrored.compress(Bitmap.CompressFormat.JPEG, 82, bout)
            bmp.recycle(); if (mirrored != bmp) mirrored.recycle()
            jpegBytes = bout.toByteArray()
        }
        return jpegBytes
    }

    // --- Kontrol Pro ---

    fun switchCamera() {
        useFront = !useFront
        Prefs.useFrontCamera = useFront
        restart()
    }

    fun setTorch(on: Boolean) {
        torch = on; Prefs.torch = on
        restart()
    }

    fun setZoom(factor: Float) {
        zoom = factor.coerceIn(1f, 5f)
        // Zoom diaplikasikan saat konversi frame — tidak perlu restart
    }

    private fun restart() {
        backgroundHandler?.post {
            release()
            openCamera()
        }
    }
}
