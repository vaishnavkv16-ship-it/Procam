package com.procam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ProCam - Camera2 based pro camera.
 * Photo: JPEG (quality 100) + optional RAW/DNG, manual ISO/shutter/focus/WB, zoom, lens picker.
 * Video: HEVC/H.264 with user bitrate, resolution, fps, OIS/EIS, manual exposure.
 */
class MainActivity : Activity() {

    private enum class Mode { PHOTO, VIDEO }
    private data class Lens(val logicalId: String, val physId: String?, val label: String)
    private data class Stab(val label: String, val ois: Boolean, val vs: Int)
    private class ChipDef(val name: String, val value: () -> String, val tap: () -> Unit)
    private class Slider(
        val title: String,
        val fmt: () -> String,
        val get: () -> Int,
        val set: (Int) -> Unit,
        val manualGet: (() -> Boolean)? = null,
        val manualSet: ((Boolean) -> Unit)? = null
    )

    private class GridView(c: Context) : View(c) {
        var on = false
        private val p = Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 2f }
        override fun onDraw(cv: Canvas) {
            if (!on) return
            for (i in 1..2) {
                cv.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), p)
                cv.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, p)
            }
        }
    }

    // ---------- Camera plumbing ----------
    private lateinit var cm: CameraManager
    private var chars: CameraCharacteristics? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private lateinit var bg: Handler
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executor { r -> bg.post(r) }

    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recSurface: Surface? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var recording = false
    private var recStart = 0L
    private var textureReady = false
    private var opening = false

    // ---------- Capabilities ----------
    private val lenses = mutableListOf<Lens>()
    private var lensIdx = 0
    private var sensorOri = 90
    private var frontFacing = false
    private var photoSizes = listOf<Size>()
    private var photoIdx = 0
    private var rawSize: Size? = null
    private var rawSupported = false
    private var rawOn = false
    private var videoSizes = listOf<Size>()
    private var videoIdx = 0
    private var fpsList = listOf(30)
    private var fpsIdx = 0
    private val bitrates = listOf(20, 35, 50, 80, 100, 150)
    private var bitrateIdx = 2
    private var hevc = true
    private var stabs = listOf<Stab>()
    private var stabIdx = 0
    private var hasOis = false
    private var hasVs = false
    private var nrModes = IntArray(0)
    private var nrIdx = 0
    private var edgeModes = IntArray(0)
    private var edgeIdx = 0
    private var wbModes = listOf<Int>()
    private var wbIdx = 0
    private var isoMin = 100
    private var isoMax = 800
    private var expMin = 100_000L
    private var expMax = 1_000_000_000L
    private var evMin = 0
    private var evMax = 0
    private var evStep = 0.33f
    private var minFocus = 0f
    private var zoomMin = 1f
    private var zoomMax = 1f

    // ---------- User settings ----------
    private var mode = Mode.PHOTO
    private var aeManual = false
    private var iso = 200
    private var expNs = 8_000_000L
    private var evIdx = 0
    private var afManual = false
    private var focus = 0f
    private var kelvin = 5500
    private var zoom = 1f
    private var flash = 0 // 0 off, 1 flash (photo), 2 torch
    private val timers = listOf(0, 3, 10)
    private var timerIdx = 0
    private var grid = false
    private var afRegion: MeteringRectangle? = null

    private var lastIso = 200
    private var lastExp = 8_000_000L
    private var lastFocus = 0f

    // ---------- UI ----------
    private lateinit var root: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var gridView: GridView
    private lateinit var status: TextView
    private lateinit var sliderPanel: LinearLayout
    private lateinit var sliderTitle: TextView
    private lateinit var seek: SeekBar
    private lateinit var autoBtn: Button
    private lateinit var chipRow: LinearLayout
    private lateinit var modeBtn: Button
    private lateinit var captureBtn: View
    private var activeSlider: Slider? = null
    private var chipDefs = listOf<ChipDef>()
    private val chipViews = mutableListOf<TextView>()
    private val screenW get() = resources.displayMetrics.widthPixels

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    // =====================================================================
    // Lifecycle
    // =====================================================================
    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(CAMERA_SERVICE) as CameraManager
        buildLenses()
        buildUi()
        if (!hasPerms()) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (hasCameraPerm()) openCamera() else toast("Camera permission is required")
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam").also { it.start() }
        bg = Handler(bgThread!!.looper)
        openCamera()
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        super.onPause()
    }

    private fun hasCameraPerm() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasAudioPerm() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasPerms() = hasCameraPerm() && hasAudioPerm()

    // =====================================================================
    // Lens discovery (logical + physical cameras)
    // =====================================================================
    private fun focalLabel(ch: CameraCharacteristics): String {
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return "?"
        val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return "%.1fmm".format(f)
        val diag = Math.hypot(s.width.toDouble(), s.height.toDouble())
        return "${(f * 43.27 / diag).roundToInt()}mm"
    }

    private fun buildLenses() {
        lenses.clear()
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
            val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val side = if (front) "FRONT" else "BACK"
            lenses += Lens(id, null, "$side ${focalLabel(ch)}")
            for (pid in ch.physicalCameraIds) {
                val pch = try { cm.getCameraCharacteristics(pid) } catch (e: Exception) { continue }
                lenses += Lens(id, pid, "$side ${focalLabel(pch)} (cam $pid)")
            }
        }
        lensIdx = lenses.indexOfFirst { it.physId == null && !it.label.startsWith("FRONT") }.coerceAtLeast(0)
    }

    // =====================================================================
    // Open / close
    // =====================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!hasCameraPerm() || !textureReady || camera != null || opening || lenses.isEmpty() || bgThread == null) return
        opening = true
        try {
            cm.openCamera(lenses[lensIdx].logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    camera = d
                    loadCaps()
                    runOnUiThread { rebuildChips(); updateModeUi() }
                    startSession()
                }
                override fun onDisconnected(d: CameraDevice) { opening = false; d.close(); camera = null }
                override fun onError(d: CameraDevice, e: Int) {
                    opening = false; d.close(); camera = null; toast("Camera error $e")
                }
            }, bg)
        } catch (e: Exception) {
            opening = false
            toast("Cannot open camera: ${e.message}")
        }
    }

    private fun closeCamera() {
        if (recording) stopRecording(false)
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
    }

    private fun switchLens() {
        if (recording || lenses.size < 2) return
        closeCamera()
        lensIdx = (lensIdx + 1) % lenses.size
        zoom = 1f; afRegion = null
        openCamera()
    }

    // =====================================================================
    // Capabilities
    // =====================================================================
    private fun loadCaps() {
        val ch = cm.getCameraCharacteristics(lenses[lensIdx].logicalId)
        chars = ch
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        sensorOri = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        frontFacing = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

        val jpeg = (map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()).toList() +
            (map.getHighResolutionOutputSizes(ImageFormat.JPEG) ?: emptyArray()).toList()
        photoSizes = jpeg.distinct().sortedByDescending { it.width.toLong() * it.height }
        photoIdx = 0

        rawSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        rawSize = (map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()).maxByOrNull { it.width.toLong() * it.height }
        if (rawSize == null) { rawSupported = false }
        if (!rawSupported) rawOn = false

        val all = (map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()).toList()
        val wanted = listOf(Size(7680, 4320), Size(3840, 2160), Size(2560, 1440), Size(1920, 1080), Size(1280, 720))
        val std = wanted.filter { all.contains(it) }
        videoSizes = if (std.isNotEmpty()) std else all.sortedByDescending { it.width.toLong() * it.height }.take(4)
        videoIdx = videoSizes.indexOfFirst { it.width == 3840 }.takeIf { it >= 0 } ?: 0
        refreshFps()

        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoMin = it.lower; isoMax = it.upper }
        ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expMin = it.lower; expMax = min(it.upper, 4_000_000_000L) }
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { evMin = it.lower; evMax = it.upper }
        evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f
        minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (Build.VERSION.SDK_INT >= 30) {
            ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { zoomMin = it.lower; zoomMax = it.upper }
        } else {
            zoomMin = 1f; zoomMax = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }
        zoom = zoom.coerceIn(zoomMin, max(zoomMin, zoomMax))
        iso = iso.coerceIn(isoMin, isoMax)
        expNs = expNs.coerceIn(expMin, expMax)
        evIdx = evIdx.coerceIn(evMin, evMax)

        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val vs = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        hasOis = ois.contains(1)
        hasVs = vs.isNotEmpty()
        val sl = mutableListOf(Stab("OFF", false, 0))
        if (hasOis) sl += Stab("OIS", true, 0)
        if (vs.contains(1)) sl += Stab("EIS", false, 1)
        if (hasOis && vs.contains(1)) sl += Stab("OIS+EIS", true, 1)
        if (vs.contains(2)) sl += Stab("PREVIEW-STAB", false, 2)
        stabs = sl
        stabIdx = sl.indexOfFirst { it.label == "OIS+EIS" }.takeIf { it >= 0 }
            ?: sl.indexOfFirst { it.label == "OIS" }.takeIf { it >= 0 }
            ?: sl.indexOfFirst { it.label == "EIS" }.takeIf { it >= 0 } ?: 0

        nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
        nrIdx = nrModes.indexOf(2).takeIf { it >= 0 } ?: 0
        edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
        edgeIdx = edgeModes.indexOf(2).takeIf { it >= 0 } ?: 0

        val awb = (ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(1)).toMutableList()
        if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) awb += -1
        wbModes = awb
        wbIdx = 0
    }

    private fun refreshFps() {
        val ch = chars ?: return
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val size = videoSizes.getOrNull(videoIdx) ?: return
        val dur = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        val maxFps = if (dur > 0) 1e9 / dur else 30.0
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(30, 30))
        val prev = fpsList.getOrNull(fpsIdx) ?: 30
        fpsList = listOf(24, 30, 60, 120).filter { v -> ranges.any { it.upper == v } && v <= maxFps + 1 }.ifEmpty { listOf(30) }
        fpsIdx = fpsList.indexOf(prev).takeIf { it >= 0 } ?: fpsList.indexOf(30).takeIf { it >= 0 } ?: 0
    }

    private fun pickPreview(target: Size): Size {
        val map = chars!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val list = (map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).toList()
        val ar = target.width.toFloat() / target.height
        return list.filter { abs(it.width.toFloat() / it.height - ar) < 0.02f && it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.width * it.height }
            ?: list.filter { it.width <= 1920 }.minByOrNull { abs(it.width.toFloat() / it.height - ar) }
            ?: list.first()
    }

    // =====================================================================
    // Sessions
    // =====================================================================
    private fun preparePreview(target: Size): Surface? {
        val st = texture.surfaceTexture ?: return null
        val p = pickPreview(target)
        st.setDefaultBufferSize(p.width, p.height)
        previewSurface?.release()
        previewSurface = Surface(st)
        runOnUiThread {
            val lp = previewBox.layoutParams as FrameLayout.LayoutParams
            lp.width = screenW
            lp.height = (screenW.toLong() * p.width / p.height).toInt()
            previewBox.layoutParams = lp
        }
        return previewSurface
    }

    private fun createSession(targets: List<Surface>, onReady: (CameraCaptureSession) -> Unit) {
        val cam = camera ?: return
        val phys = lenses[lensIdx].physId
        val configs = targets.map { s ->
            OutputConfiguration(s).also { c -> if (phys != null) c.setPhysicalCameraId(phys) }
        }
        try {
            cam.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, configs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; onReady(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        toast("Session config failed (try lower size / RAW off)")
                        if (rawOn) { rawOn = false; runOnUiThread { refreshChips() } }
                    }
                }))
        } catch (e: Exception) {
            toast("Session error: ${e.message}")
        }
    }

    private fun startSession() {
        if (camera == null || !textureReady) return
        try { session?.close() } catch (_: Exception) {}
        session = null
        if (mode == Mode.PHOTO) startPhotoSession() else startVideoPreviewSession()
    }

    private fun startPhotoSession() {
        val size = photoSizes.getOrNull(photoIdx) ?: return
        val prev = preparePreview(size) ?: return
        jpegReader?.close(); rawReader?.close(); rawReader = null
        jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3).also { r ->
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
                try { saveJpeg(img) } finally { img.close() }
            }, bg)
        }
        val targets = mutableListOf(prev, jpegReader!!.surface)
        if (rawOn && rawSize != null) {
            rawReader = ImageReader.newInstance(rawSize!!.width, rawSize!!.height, ImageFormat.RAW_SENSOR, 2).also { r ->
                r.setOnImageAvailableListener({ rd ->
                    val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
                    synchronized(rawLock) { pendingRaw?.close(); pendingRaw = img }
                    tryWriteDng()
                }, bg)
            }
            targets += rawReader!!.surface
        }
        createSession(targets) { updatePreview() }
    }

    private fun startVideoPreviewSession() {
        val size = videoSizes.getOrNull(videoIdx) ?: return
        val prev = preparePreview(size) ?: return
        createSession(listOf(prev)) { updatePreview() }
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        private var n = 0
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            if (++n % 5 == 0) runOnUiThread { updateStatus() }
        }
    }

    private fun updatePreview(triggerAf: Boolean = false) {
        val s = session ?: return
        val cam = camera ?: return
        val prev = previewSurface ?: return
        try {
            val video = mode == Mode.VIDEO
            val b = cam.createCaptureRequest(if (video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(prev)
            recSurface?.let { b.addTarget(it) }
            applyCommon(b, video)
            if (triggerAf) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, bg)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            s.setRepeatingRequest(b.build(), previewCb, bg)
        } catch (e: CameraAccessException) {
        } catch (e: IllegalStateException) {
        }
    }

    // =====================================================================
    // Request parameters (all pro controls live here)
    // =====================================================================
    private fun fpsRange(): Range<Int>? {
        val ch = chars ?: return null
        val f = fpsList.getOrElse(fpsIdx) { 30 }
        val rs = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return rs.firstOrNull { it.lower == f && it.upper == f } ?: rs.filter { it.upper == f }.maxByOrNull { it.lower }
    }

    private fun kelvinGains(k: Int): RggbChannelVector {
        val t = k / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val bl = if (t >= 66) 255.0 else if (t <= 19) 1.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
        val rc = r.coerceIn(1.0, 255.0); val gc = g.coerceIn(1.0, 255.0); val bc = bl.coerceIn(1.0, 255.0)
        return RggbChannelVector((gc / rc).toFloat(), 1f, 1f, (gc / bc).toFloat())
    }

    private fun applyCommon(b: CaptureRequest.Builder, video: Boolean) {
        val ch = chars ?: return
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val fps = fpsList.getOrElse(fpsIdx) { 30 }
        val frameNs = 1_000_000_000L / fps

        // Exposure
        if (aeManual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, if (video) min(expNs, frameNs) else expNs)
            if (video) b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIdx)
            if (video) fpsRange()?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }

        // Focus
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (afManual && minFocus > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, minFocus))
        } else {
            val pref = if (video) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            b.set(CaptureRequest.CONTROL_AF_MODE, if (afModes.contains(pref)) pref else CaptureRequest.CONTROL_AF_MODE_AUTO)
            afRegion?.let { r ->
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
                if (!aeManual && (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
            }
        }

        // White balance
        val wb = wbModes.getOrElse(wbIdx) { 1 }
        if (wb == -1) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinGains(kelvin))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)))
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, wb)
        }

        // Zoom
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { a ->
                val w = (a.width() / zoom).toInt(); val h = (a.height() / zoom).toInt()
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2))
            }
        }

        // Stabilization
        stabs.getOrNull(stabIdx)?.let { st ->
            if (hasOis) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (st.ois) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            if (hasVs) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (video) st.vs else 0)
        }

        // Quality processing
        if (nrModes.isNotEmpty()) b.set(CaptureRequest.NOISE_REDUCTION_MODE, nrModes[nrIdx])
        if (edgeModes.isNotEmpty()) b.set(CaptureRequest.EDGE_MODE, edgeModes[edgeIdx])

        // Torch
        if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            b.set(CaptureRequest.FLASH_MODE, if (flash == 2) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }
    }

    // =====================================================================
    // Photo capture (JPEG + optional DNG)
    // =====================================================================
    private val rawLock = Any()
    private var pendingRaw: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private val stamp get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun onShutter() {
        val t = timers[timerIdx]
        if (t == 0) takePhoto() else {
            toast("Capturing in ${t}s")
            ui.postDelayed({ takePhoto() }, t * 1000L)
        }
    }

    private fun takePhoto() {
        val s = session ?: return
        val cam = camera ?: return
        val jr = jpegReader ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            previewSurface?.let { b.addTarget(it) }
            b.addTarget(jr.surface)
            if (rawOn) rawReader?.let { b.addTarget(it.surface) }
            applyCommon(b, false)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOri)
            if (flash == 1 && !aeManual) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
            }
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (rawOn) { synchronized(rawLock) { pendingResult = res }; tryWriteDng() }
                }
            }, bg)
        } catch (e: Exception) {
            toast("Capture failed: ${e.message}")
        }
    }

    private fun saveToStore(collection: Uri, name: String, mime: String, rel: String, write: (OutputStream) -> Unit): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, v) ?: return null
        contentResolver.openOutputStream(uri)?.use { write(it) }
        v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        return uri
    }

    private fun saveJpeg(img: Image) {
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
        saveToStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "PRO_${stamp}.jpg", "image/jpeg", "Pictures/ProCam") { it.write(bytes) }
        toast("Saved JPEG (${img.width}x${img.height})")
    }

    private fun tryWriteDng() {
        var img: Image? = null
        var res: TotalCaptureResult? = null
        synchronized(rawLock) {
            if (pendingRaw != null && pendingResult != null) {
                img = pendingRaw; res = pendingResult; pendingRaw = null; pendingResult = null
            }
        }
        val i = img ?: return
        val r = res ?: return
        try {
            val dng = DngCreator(chars!!, r)
            dng.setOrientation(when (sensorOri) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 })
            saveToStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "PRO_${stamp}.dng", "image/x-adobe-dng", "Pictures/ProCam") { dng.writeImage(it, i) }
            dng.close()
            toast("Saved RAW DNG")
        } catch (e: Exception) {
            toast("DNG failed: ${e.message}")
        } finally { i.close() }
    }

    // =====================================================================
    // Video recording
    // =====================================================================
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun onRecordToggle() { if (recording) stopRecording(true) else startRecording() }

    private fun startRecording() {
        val size = videoSizes.getOrNull(videoIdx) ?: return
        val prev = previewSurface ?: return
        val fps = fpsList.getOrElse(fpsIdx) { 30 }
        try {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "PRO_${stamp}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ProCam")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            recUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
            recPfd = contentResolver.openFileDescriptor(recUri!!, "w")
            val r = newRecorder()
            val audio = hasAudioPerm()
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(recPfd!!.fileDescriptor)
            r.setVideoEncodingBitRate(bitrates[bitrateIdx] * 1_000_000)
            r.setVideoFrameRate(fps)
            r.setVideoSize(size.width, size.height)
            r.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(256_000)
                r.setAudioSamplingRate(48_000)
            }
            r.setOrientationHint(sensorOri)
            r.prepare()
            recorder = r
            recSurface = r.surface
            try { session?.close() } catch (_: Exception) {}
            session = null
            createSession(listOf(prev, r.surface)) {
                updatePreview()
                try {
                    r.start()
                    recording = true
                    recStart = SystemClock.elapsedRealtime()
                    runOnUiThread { updateModeUi(); updateStatus() }
                    ui.post(tick)
                } catch (e: Exception) {
                    toast("Recorder start failed: ${e.message}")
                    stopRecording(true)
                }
            }
        } catch (e: Exception) {
            toast("Cannot record: ${e.message}")
            cleanupRecorder(true)
        }
    }

    private val tick = object : Runnable {
        override fun run() { if (recording) { updateStatus(); ui.postDelayed(this, 500) } }
    }

    private fun cleanupRecorder(discard: Boolean) {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null; recSurface = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { u ->
            if (discard) try { contentResolver.delete(u, null, null) } catch (_: Exception) {}
            else {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(u, v, null, null)
            }
        }
        recUri = null
    }

    private fun stopRecording(restart: Boolean) {
        var ok = recording
        recording = false
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
        try { recorder?.stop() } catch (e: RuntimeException) { ok = false }
        cleanupRecorder(!ok)
        if (ok) toast("Video saved to Movies/ProCam") else if (restart) toast("Recording too short - discarded")
        runOnUiThread { updateModeUi(); updateStatus() }
        if (restart) startSession()
    }

    // =====================================================================
    // Tap to focus
    // =====================================================================
    private fun focusAt(nx0: Float, ny: Float) {
        val ch = chars ?: return
        if (afManual) return
        val act = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val nx = if (frontFacing) 1f - nx0 else nx0
        val (sx, sy) = when (sensorOri) {
            90 -> ny to 1f - nx
            270 -> 1f - ny to nx
            180 -> 1f - nx to 1f - ny
            else -> nx to ny
        }
        val cx = act.left + (sx * act.width()).toInt()
        val cy = act.top + (sy * act.height()).toInt()
        val half = act.width() / 16
        val rect = Rect(
            (cx - half).coerceAtLeast(act.left), (cy - half).coerceAtLeast(act.top),
            (cx + half).coerceAtMost(act.right - 1), (cy + half).coerceAtMost(act.bottom - 1))
        afRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        updatePreview(true)
    }

    // =====================================================================
    // Formatting helpers
    // =====================================================================
    private fun fmtShutter(ns: Long) =
        if (ns >= 400_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"

    private fun wbName(m: Int) = when (m) {
        -1 -> "${kelvin}K"; 1 -> "AUTO"; 2 -> "INCAND"; 3 -> "FLUOR"; 4 -> "WARM FL"
        5 -> "DAYLIGHT"; 6 -> "CLOUDY"; 7 -> "TWILIGHT"; 8 -> "SHADE"; else -> "WB$m"
    }
    private fun nrName(m: Int) = when (m) { 0 -> "OFF"; 1 -> "FAST"; 2 -> "HQ"; 3 -> "MIN"; 4 -> "ZSL"; else -> "$m" }
    private fun sizeLabel(s: Size) = "%.0fMP".format(s.width.toLong() * s.height / 1e6) + " ${s.width}x${s.height}"
    private fun resLabel(s: Size) = when (s.width) { 7680 -> "8K"; 3840 -> "4K"; 2560 -> "1440p"; 1920 -> "1080p"; 1280 -> "720p"; else -> "${s.width}x${s.height}" }
    private fun focusLabel() = if (focus < 0.05f) "∞" else "%.0fcm".format(100f / focus)

    private fun logToProg(v: Double, lo: Double, hi: Double) =
        if (hi <= lo) 0 else (ln(v / lo) / ln(hi / lo) * 1000).toInt().coerceIn(0, 1000)
    private fun progToLog(p: Int, lo: Double, hi: Double) = lo * (hi / lo).pow(p / 1000.0)

    private fun updateStatus() {
        val sb = StringBuilder()
        sb.append("ISO ").append(if (aeManual) iso else lastIso)
        sb.append("  ").append(fmtShutter(if (aeManual) expNs else lastExp))
        if (mode == Mode.VIDEO) {
            sb.append("  ").append(resLabel(videoSizes.getOrElse(videoIdx) { Size(0, 0) }))
            sb.append(" ").append(fpsList.getOrElse(fpsIdx) { 30 }).append("fps")
            sb.append(" ").append(bitrates[bitrateIdx]).append("Mbps ").append(if (hevc) "HEVC" else "H264")
        }
        if (recording) {
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            sb.append("\n● REC %02d:%02d".format(s / 60, s % 60))
        }
        status.text = sb.toString()
    }

    // =====================================================================
    // Sliders
    // =====================================================================
    private fun setAeManual(on: Boolean) {
        if (on && !aeManual) { iso = lastIso.coerceIn(isoMin, isoMax); expNs = lastExp.coerceIn(expMin, expMax) }
        aeManual = on
    }
    private fun setAfManual(on: Boolean) {
        if (on && !afManual) focus = lastFocus.coerceIn(0f, minFocus)
        afManual = on
    }

    private val isoSlider by lazy {
        Slider("ISO", { "ISO $iso" }, { logToProg(iso.toDouble(), isoMin.toDouble(), isoMax.toDouble()) },
            { p -> setAeManual(true); iso = progToLog(p, isoMin.toDouble(), isoMax.toDouble()).roundToInt() },
            { aeManual }, { setAeManual(it) })
    }
    private val shutterSlider by lazy {
        Slider("SHUTTER", { fmtShutter(expNs) }, { logToProg(expNs.toDouble(), expMin.toDouble(), expMax.toDouble()) },
            { p -> setAeManual(true); expNs = progToLog(p, expMin.toDouble(), expMax.toDouble()).toLong() },
            { aeManual }, { setAeManual(it) })
    }
    private val evSlider by lazy {
        Slider("EXPOSURE COMP", { "%+.1f EV".format(evIdx * evStep) },
            { ((evIdx - evMin).toFloat() / max(1, evMax - evMin) * 1000).toInt() },
            { p -> setAeManual(false); evIdx = evMin + (p / 1000f * (evMax - evMin)).roundToInt() })
    }
    private val focusSlider by lazy {
        Slider("FOCUS", { "Focus ${focusLabel()}" }, { if (minFocus <= 0f) 0 else (focus / minFocus * 1000).toInt() },
            { p -> setAfManual(true); focus = p / 1000f * minFocus },
            { afManual }, { setAfManual(it) })
    }
    private val zoomSlider by lazy {
        Slider("ZOOM", { "%.1fx".format(zoom) }, { logToProg(zoom.toDouble(), zoomMin.toDouble(), max(zoomMin, zoomMax).toDouble()) },
            { p -> zoom = progToLog(p, zoomMin.toDouble(), max(zoomMin, zoomMax).toDouble()).toFloat() })
    }
    private val kelvinSlider by lazy {
        Slider("WHITE BALANCE", { "${kelvin}K" }, { ((kelvin - 2000) / 8000f * 1000).toInt() },
            { p -> kelvin = 2000 + (p / 1000f * 8000).roundToInt() })
    }

    private fun showSlider(s: Slider?) {
        if (s == null || (activeSlider === s && sliderPanel.visibility == View.VISIBLE && s.manualGet == null)) {
            activeSlider = null; sliderPanel.visibility = View.GONE; return
        }
        activeSlider = s
        sliderPanel.visibility = View.VISIBLE
        seek.progress = s.get()
        sliderTitle.text = "${s.title}   ${s.fmt()}"
        if (s.manualGet != null) {
            autoBtn.visibility = View.VISIBLE
            autoBtn.text = if (s.manualGet.invoke()) "MANUAL  (tap for AUTO)" else "AUTO  (tap for MANUAL)"
        } else autoBtn.visibility = View.GONE
    }

    // =====================================================================
    // Chips
    // =====================================================================
    private fun cycleWb() {
        wbIdx = (wbIdx + 1) % wbModes.size
        if (wbModes[wbIdx] == -1) showSlider(kelvinSlider) else if (activeSlider === kelvinSlider) showSlider(null)
        updatePreview()
    }

    private fun defs(): List<ChipDef> {
        val l = mutableListOf<ChipDef>()
        l += ChipDef("ISO", { if (aeManual) "$iso" else "AUTO" }) { showSlider(isoSlider) }
        l += ChipDef("SHUTTER", { if (aeManual) fmtShutter(expNs) else "AUTO" }) { showSlider(shutterSlider) }
        l += ChipDef("EV", { if (aeManual) "—" else "%+.1f".format(evIdx * evStep) }) { showSlider(evSlider) }
        l += ChipDef("FOCUS", { if (minFocus <= 0f) "FIXED" else if (afManual) focusLabel() else "AUTO" }) {
            if (minFocus > 0f) showSlider(focusSlider) else toast("Fixed focus lens")
        }
        l += ChipDef("WB", { wbName(wbModes.getOrElse(wbIdx) { 1 }) }) { cycleWb() }
        l += ChipDef("ZOOM", { "%.1fx".format(zoom) }) { showSlider(zoomSlider) }
        l += ChipDef("LENS", { lenses.getOrNull(lensIdx)?.label ?: "-" }) { switchLens() }
        if (mode == Mode.PHOTO) {
            l += ChipDef("SIZE", { photoSizes.getOrNull(photoIdx)?.let { sizeLabel(it) } ?: "-" }) {
                if (photoSizes.isNotEmpty()) { photoIdx = (photoIdx + 1) % photoSizes.size; startSession() }
            }
            l += ChipDef("RAW", { if (!rawSupported) "N/A" else if (rawOn) "ON" else "OFF" }) {
                if (!rawSupported) toast("RAW not exposed by this camera") else { rawOn = !rawOn; startSession() }
            }
            l += ChipDef("FLASH", { listOf("OFF", "ON", "TORCH")[flash] }) { flash = (flash + 1) % 3; updatePreview() }
            l += ChipDef("TIMER", { if (timers[timerIdx] == 0) "OFF" else "${timers[timerIdx]}s" }) { timerIdx = (timerIdx + 1) % timers.size }
        } else {
            l += ChipDef("RES", { videoSizes.getOrNull(videoIdx)?.let { resLabel(it) } ?: "-" }) {
                if (!recording && videoSizes.isNotEmpty()) { videoIdx = (videoIdx + 1) % videoSizes.size; refreshFps(); startSession() }
            }
            l += ChipDef("FPS", { "${fpsList.getOrElse(fpsIdx) { 30 }}" }) {
                if (!recording) { fpsIdx = (fpsIdx + 1) % fpsList.size; updatePreview() }
            }
            l += ChipDef("BITRATE", { "${bitrates[bitrateIdx]} Mbps" }) { if (!recording) bitrateIdx = (bitrateIdx + 1) % bitrates.size }
            l += ChipDef("CODEC", { if (hevc) "HEVC" else "H.264" }) { if (!recording) hevc = !hevc }
            l += ChipDef("TORCH", { if (flash == 2) "ON" else "OFF" }) { flash = if (flash == 2) 0 else 2; updatePreview() }
        }
        l += ChipDef("STAB", { stabs.getOrNull(stabIdx)?.label ?: "N/A" }) {
            if (stabs.isNotEmpty()) { stabIdx = (stabIdx + 1) % stabs.size; updatePreview() }
        }
        l += ChipDef("NOISE RED", { if (nrModes.isEmpty()) "N/A" else nrName(nrModes[nrIdx]) }) {
            if (nrModes.isNotEmpty()) { nrIdx = (nrIdx + 1) % nrModes.size; updatePreview() }
        }
        l += ChipDef("SHARPEN", { if (edgeModes.isEmpty()) "N/A" else nrName(edgeModes[edgeIdx]) }) {
            if (edgeModes.isNotEmpty()) { edgeIdx = (edgeIdx + 1) % edgeModes.size; updatePreview() }
        }
        l += ChipDef("GRID", { if (grid) "ON" else "OFF" }) { grid = !grid; gridView.on = grid; gridView.invalidate() }
        l += ChipDef("INFO", { "?" }) { showInfo() }
        return l
    }

    private fun rebuildChips() {
        chipRow.removeAllViews(); chipViews.clear()
        chipDefs = defs()
        for (d in chipDefs) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                minWidth = dp(68)
                setOnClickListener { d.tap(); refreshChips(); activeSlider?.let { s -> sliderTitle.text = "${s.title}   ${s.fmt()}" } }
            }
            chipViews += tv
            chipRow.addView(tv)
        }
        refreshChips()
    }

    private fun refreshChips() {
        runOnUiThread {
            chipDefs.forEachIndexed { i, d -> chipViews.getOrNull(i)?.text = "${d.name}\n${d.value()}" }
            updateStatus()
        }
    }

    private fun showInfo() {
        val ch = chars ?: return
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val names = mapOf(1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW", 6 to "BURST", 11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME", 16 to "ULTRA_HIGH_RES")
        val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; 4 -> "EXTERNAL"; else -> "?"
        }
        val txt = buildString {
            appendLine("Camera: ${lenses[lensIdx].label}")
            appendLine("Hardware level: $level")
            appendLine("Capabilities: " + caps.toList().mapNotNull { names[it] }.joinToString())
            appendLine("ISO: $isoMin - $isoMax")
            appendLine("Shutter: ${fmtShutter(expMax)} - ${fmtShutter(expMin)}")
            appendLine("Min focus distance: ${if (minFocus > 0) "%.0f cm".format(100f / minFocus) else "fixed"}")
            appendLine("Zoom: %.1fx - %.1fx".format(zoomMin, zoomMax))
            appendLine("OIS: $hasOis   Video stab modes: ${stabs.joinToString { it.label }}")
            appendLine("RAW: ${if (rawSupported) "${rawSize?.width}x${rawSize?.height}" else "not exposed"}")
            appendLine("Photo sizes: ${photoSizes.take(6).joinToString { sizeLabel(it) }}")
            appendLine("Video sizes: ${videoSizes.joinToString { resLabel(it) }}")
            appendLine("FPS options: ${fpsList.joinToString()}")
            appendLine("Cameras exposed: ${lenses.size}")
        }
        runOnUiThread { AlertDialog.Builder(this).setTitle("Camera capabilities").setMessage(txt).setPositiveButton("OK", null).show() }
    }

    // =====================================================================
    // Mode + UI
    // =====================================================================
    private fun switchMode() {
        if (recording) return
        mode = if (mode == Mode.PHOTO) Mode.VIDEO else Mode.PHOTO
        flash = 0; showSlider(null)
        rebuildChips(); updateModeUi()
        startSession()
    }

    private fun updateModeUi() {
        runOnUiThread {
            modeBtn.text = if (mode == Mode.PHOTO) "VIDEO" else "PHOTO"
            captureBtn.background = GradientDrawable().apply {
                if (mode == Mode.PHOTO) { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                else {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = if (recording) dp(10).toFloat() else dp(36).toFloat()
                    setColor(Color.rgb(230, 30, 40))
                }
                setStroke(dp(4), Color.WHITE)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewBox = FrameLayout(this)
        texture = TextureView(this)
        gridView = GridView(this)
        previewBox.addView(texture, FrameLayout.LayoutParams(-1, -1))
        previewBox.addView(gridView, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewBox, FrameLayout.LayoutParams(screenW, screenW * 4 / 3, Gravity.TOP))

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { textureReady = true; openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { textureReady = false; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        texture.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) focusAt(e.x / v.width, e.y / v.height)
            true
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(12), dp(28), dp(12), 0)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(0, dp(6), 0, dp(14))
        }

        sliderPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), 0, dp(16), dp(6)) }
        sliderTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        seek = SeekBar(this).apply { max = 1000 }
        autoBtn = Button(this).apply { textSize = 12f; setOnClickListener {
            activeSlider?.let { s ->
                s.manualSet?.invoke(!(s.manualGet?.invoke() ?: false))
                showSlider(s); updatePreview(); refreshChips()
            }
        } }
        sliderPanel.addView(sliderTitle); sliderPanel.addView(seek); sliderPanel.addView(autoBtn)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val s = activeSlider ?: return
                s.set(p)
                sliderTitle.text = "${s.title}   ${s.fmt()}"
                if (s.manualGet != null) autoBtn.text = if (s.manualGet.invoke()) "MANUAL  (tap for AUTO)" else "AUTO  (tap for MANUAL)"
                updatePreview(); refreshChips()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        bottom.addView(sliderPanel)

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(chipRow)
        bottom.addView(hs)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        modeBtn = Button(this).apply { textSize = 12f; setOnClickListener { switchMode() } }
        captureBtn = View(this).apply {
            setOnClickListener { if (mode == Mode.PHOTO) onShutter() else onRecordToggle() }
        }
        row.addView(modeBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(captureBtn, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginStart = dp(16); marginEnd = dp(16) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(row)

        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        updateModeUi()
    }
}
