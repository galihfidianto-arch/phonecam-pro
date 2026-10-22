package com.gsh.phonecam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Camera2 engine dengan ImageReader YUV→JPEG (MJPEG server).
 * Fitur Pro lengkap: switch kamera, torch, zoom digital, resolusi 720p/1080p/4K.
 *
 * Semua operasi kamera dijalankan di background handler.
 * Error dilaporkan via [errorListener] untuk ditampilkan di UI.
 */
class CameraEngine(private val ctx: Context) {

    interface ErrorListener { fun onError(msg: String) }
    var errorListener: ErrorListener? = null

    @Volatile var latestJpeg: ByteArray? = null
        private set
    @Volatile var fps = 0.0
        private set
    @Volatile var resolution = ""
        private set
    @Volatile var isStreaming = false
        private set

    @Volatile var useFront = Prefs.useFrontCamera
    @Volatile private var torchOn = Prefs.torch
    @Volatile private var zoom = 1f

    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var targetSize: Size = Size(1920, 1080)
    private val frameTimes = ConcurrentLinkedQueue<Long>()

    private fun report(msg: String) {
        android.util.Log.e("PhoneCamEngine", msg)
        errorListener?.onError(msg)
    }

    fun start() {
        backgroundHandler?.post {
            if (backgroundThread == null) {
                backgroundThread = HandlerThread("cam").also { it.start() }
                backgroundHandler = Handler(backgroundThread!!.looper)
            }
            openCamera()
        }
    }

    fun release() {
        try {
            captureSession?.close(); captureSession = null
            imageReader?.close(); imageReader = null
            cameraDevice?.close(); cameraDevice = null
        } catch (_: Exception) {}
        isStreaming = false
    }

    fun fullStop() {
        release()
        try { backgroundThread?.quitSafely() } catch (_: Exception) {}
        backgroundThread = null; backgroundHandler = null
    }

    private fun cameraId(): String = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            val f = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            f == (if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK)
        } ?: cameraManager.cameraIdList.first()
    } catch (e: Exception) {
        report("Kamera tidak tersedia: ${e.message}")
        ""
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val id = cameraId()
            if (id.isEmpty()) return
            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run { report("Konfigurasi kamera tidak didukung"); return }

            val wanted = when (Prefs.quality) {
                0 -> listOf(Size(1280, 720))
                2 -> listOf(Size(3840, 2160), Size(2560, 1440), Size(1920, 1080))
                else -> listOf(Size(1920, 1080))
            }
            targetSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.firstOrNull { s -> wanted.any { w -> s.width == w.width && s.height == w.height } }
                ?: map.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.filter { it.width <= 3840 }
                    ?.maxByOrNull { it.width * it.height }
                ?: Size(1280, 720)
            resolution = "${targetSize.width}x${targetSize.height}"

            imageReader?.close()
            imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.YUV_420_888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val jpeg = yuvToJpeg(image)
                    if (jpeg != null) {
                        latestJpeg = jpeg
                        isStreaming = true
                        val now = System.currentTimeMillis()
                        frameTimes.add(now)
                        while (frameTimes.size > 30) frameTimes.poll()
                        val oldest = frameTimes.peek() ?: now
                        val dt = (now - oldest).coerceAtLeast(1)
                        fps = if (frameTimes.size > 1) (frameTimes.size - 1) * 1000.0 / dt else 0.0
                    }
                } catch (e: Exception) {
                    report("Konversi frame gagal: ${e.message}")
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
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    report("Kamera error kode $error")
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            report("Izin kamera ditolak: ${e.message}")
        } catch (e: Exception) {
            report("Buka kamera gagal: ${e.message}")
        }
    }

    private fun startSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val surface = reader.surface

        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                if (torchOn) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                            }
                            session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                    report("Capture gagal: ${failure.reason}")
                                }
                            }, backgroundHandler)
                        } catch (e: Exception) {
                            report("Mulai stream gagal: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        report("Konfigurasi sesi kamera gagal (resolusi ${targetSize.width}x${targetSize.height} mungkin tak didukung)")
                    }
                }, backgroundHandler)
        } catch (e: Exception) {
            report("Sesi kamera gagal: ${e.message}")
        }
    }

    /** Konversi YUV_420_888 → JPEG. Stride dibaca per-plane (aman untuk semua device). */
    private fun yuvToJpeg(image: android.media.Image): ByteArray? {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val nv21 = ByteArray(w * h * 3 / 2)
        var pos = 0

        // Salin Y per-baris (rowStride bisa > width karena padding)
        val yBuf = yPlane.buffer
        val yStride = yPlane.rowStride
        if (yStride == w) {
            yBuf.get(nv21, 0, w * h); pos = w * h
        } else {
            for (row in 0 until h) {
                yBuf.position(row * yStride)
                yBuf.get(nv21, pos, w); pos += w
            }
        }

        // Salin VU interleaved (NV21) — stride per plane masing-masing
        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[pos++] = vBuf.get(row * vRow + col * vPix)
                nv21[pos++] = uBuf.get(row * uRow + col * uPix)
            }
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), 82, out)
        var jpegBytes = out.toByteArray()

        // Zoom digital: crop tengah lalu scale kembali
        if (zoom > 1.01f) {
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
            val cw = (bmp.width / zoom).toInt().coerceAtLeast(8)
            val ch = (bmp.height / zoom).toInt().coerceAtLeast(8)
            val x = (bmp.width - cw) / 2
            val y = (bmp.height - ch) / 2
            val cropped = Bitmap.createBitmap(bmp, x, y, cw, ch)
            val scaled = Bitmap.createScaledBitmap(cropped, bmp.width, bmp.height, true)
            val bout = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, bout)
            if (bmp != cropped) bmp.recycle()
            if (cropped != scaled) cropped.recycle()
            scaled.recycle()
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

    val isTorchOn: Boolean get() = torchOn

    fun setTorch(on: Boolean) {
        torchOn = on; Prefs.torch = on
        restart()
    }

    fun setZoom(factor: Float) {
        zoom = factor.coerceIn(1f, 5f)
    }

    private fun restart() {
        backgroundHandler?.post {
            release()
            openCamera()
        }
    }
}
