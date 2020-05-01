package com.whelksoft.rtmppublisher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.Face
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.CameraHelper.Facing
import java.util.*


/**
 * Created by pedro on 4/03/17.
 *
 *
 *
 * Class for use surfaceEncoder to buffer encoder.
 * Advantage = you can use all resolutions.
 * Disadvantages = you cant control fps of the stream, because you cant know when the inputSurface
 * was renderer.
 *
 *
 * Note: you can use opengl for surfaceEncoder to buffer encoder on devices 21 < API > 16:
 * https://github.com/google/grafika
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class RtmpCamera2ApiManager(context: Context) : CameraDevice.StateCallback() {
    private val TAG: String = "Camera2ApiManager"
    private var cameraDevice: CameraDevice? = null
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null
    private var surfaceEncoder //input surfaceEncoder from videoEncoder
            : Surface? = null
    private val cameraManager: CameraManager
    private var cameraHandler: Handler? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    var isPrepared: Boolean = false
        private set
    private var cameraId: Int = -1
    var isFrontCamera: Boolean = false
        private set
    var cameraCharacteristics: CameraCharacteristics? = null
        private set
    private var builderInputSurface: CaptureRequest.Builder? = null
    private var fingerSpacing: Float = 0f
    private var zoomLevel: Float = 1.0f
    var isLanternEnabled: Boolean = false
        private set
    var isRunning: Boolean = false
        private set

    //Face detector
    interface FaceDetectorCallback {
        fun onGetFaces(faces: Array<Face>?)
    }

    private var faceDetectorCallback: FaceDetectorCallback? = null
    private var faceDetectionEnabled: Boolean = false
    private var faceDetectionMode: Int = 0
    fun prepareCamera(surfaceView: SurfaceView?, surface: Surface?) {
        this.surfaceView = surfaceView
        surfaceEncoder = surface
        isPrepared = true
    }

    fun prepareCamera(textureView: TextureView?, surface: Surface?) {
        this.textureView = textureView
        surfaceEncoder = surface
        isPrepared = true
    }

    fun prepareCamera(surface: Surface?) {
        surfaceEncoder = surface
        isPrepared = true
    }

    fun prepareCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceEncoder = Surface(surfaceTexture)
        isPrepared = true
    }

    private fun startPreview(cameraDevice: CameraDevice) {
        try {
            val listSurfaces: MutableList<Surface?> = ArrayList()
            val preview: Surface? = addPreviewSurface()
            if (preview != null) listSurfaces.add(preview)
            if (surfaceEncoder != null) listSurfaces.add(surfaceEncoder)
            cameraDevice.createCaptureSession(listSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    this@RtmpCamera2ApiManager.cameraCaptureSession = cameraCaptureSession
                    try {
                        val captureRequest: CaptureRequest? = drawSurface(listSurfaces)
                        if (captureRequest != null) {
                            cameraCaptureSession.setRepeatingRequest(captureRequest,
                                    if (faceDetectionEnabled) cb else null, cameraHandler)
                            Log.i(TAG, "Camera configured")
                        } else {
                            Log.e(TAG, "Error, captureRequest is null")
                        }
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Error", e)
                    } catch (e: NullPointerException) {
                        Log.e(TAG, "Error", e)
                    } catch (e: IllegalStateException) {
                        reOpenCamera(if (cameraId != -1) cameraId else 0)
                    }
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    cameraCaptureSession.close()
                    Log.e(TAG, "Configuration failed")
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error", e)
        } catch (e: IllegalStateException) {
            reOpenCamera(if (cameraId != -1) cameraId else 0)
        }
    }

    private fun addPreviewSurface(): Surface? {
        var surface: Surface? = null
        if (surfaceView != null) {
            surface = surfaceView!!.holder.surface
        } else if (textureView != null) {
            val texture: SurfaceTexture = textureView!!.surfaceTexture
            surface = Surface(texture)
        } else {
            surface = this.surface
        }
        return surface
    }

    private fun drawSurface(surfaces: List<Surface?>): CaptureRequest? {
        try {
            builderInputSurface = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            for (surface: Surface? in surfaces) if (surface != null) builderInputSurface!!.addTarget(surface)
            return builderInputSurface!!.build()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error", e)
            return null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error", e)
            return null
        }
    }

    val levelSupported: Int
        get() {
            try {
                cameraCharacteristics = cameraManager.getCameraCharacteristics("0")
                return cameraCharacteristics!!.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error", e)
                return -1
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error", e)
                return -1
            }
        }

    fun openCamera() {
        openCameraBack()
    }

    fun openCameraBack() {
        openCameraFacing(Facing.BACK)
    }

    fun openCameraFront() {
        openCameraFacing(Facing.FRONT)
    }

    fun openLastCamera() {
        if (cameraId == -1) {
            openCameraBack()
        } else {
            openCameraId(cameraId)
        }
    }

    val cameraResolutionsBack: Array<Size?>
        get() {
            try {
                cameraCharacteristics = cameraManager.getCameraCharacteristics("0")
                if ((cameraCharacteristics!!.get(CameraCharacteristics.LENS_FACING)
                                != CameraCharacteristics.LENS_FACING_BACK)) {
                    cameraCharacteristics = cameraManager.getCameraCharacteristics("1")
                }
                val streamConfigurationMap: StreamConfigurationMap = cameraCharacteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                return streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error", e)
                return arrayOfNulls(0)
            }
        }

    val cameraResolutionsFront: Array<Size?>
        get() {
            try {
                cameraCharacteristics = cameraManager.getCameraCharacteristics("0")
                if ((cameraCharacteristics!!.get(CameraCharacteristics.LENS_FACING)
                                != CameraCharacteristics.LENS_FACING_FRONT)) {
                    cameraCharacteristics = cameraManager.getCameraCharacteristics("1")
                }
                val streamConfigurationMap: StreamConfigurationMap = cameraCharacteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                return streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error", e)
                return arrayOfNulls(0)
            }
        }

    /**
     * Select camera facing
     *
     * @param cameraFacing - CameraCharacteristics.LENS_FACING_FRONT, CameraCharacteristics.LENS_FACING_BACK,
     * CameraCharacteristics.LENS_FACING_EXTERNAL
     */
    fun openCameraFacing(cameraFacing: Facing) {
        val facing: Int = if (cameraFacing == Facing.BACK) CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics("0")
            if (cameraCharacteristics!!.get(CameraCharacteristics.LENS_FACING) == facing) {
                openCameraId(0)
            } else {
                openCameraId(cameraManager.cameraIdList.size - 1)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error", e)
        }
    }

    val isLanternSupported: Boolean
        get() = (if (cameraCharacteristics != null) cameraCharacteristics!!.get(
                CameraCharacteristics.FLASH_INFO_AVAILABLE) else false)

    /**
     * @required: <uses-permission android:name="android.permission.FLASHLIGHT"></uses-permission>
     */
    @Throws(Exception::class)
    fun enableLantern() {
        if ((cameraCharacteristics != null) && cameraCharacteristics!!.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
            if (builderInputSurface != null) {
                try {
                    builderInputSurface!!.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                    cameraCaptureSession!!.setRepeatingRequest(builderInputSurface!!.build(),
                            if (faceDetectionEnabled) cb else null, null)
                    isLanternEnabled = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error", e)
                }
            }
        } else {
            Log.e(TAG, "Lantern unsupported")
            throw Exception("Lantern unsupported")
        }
    }

    /**
     * @required: <uses-permission android:name="android.permission.FLASHLIGHT"></uses-permission>
     */
    fun disableLantern() {
        if ((cameraCharacteristics != null) && cameraCharacteristics!!.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
            if (builderInputSurface != null) {
                try {
                    builderInputSurface!!.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    cameraCaptureSession!!.setRepeatingRequest(builderInputSurface!!.build(),
                            if (faceDetectionEnabled) cb else null, null)
                    isLanternEnabled = false
                } catch (e: Exception) {
                    Log.e(TAG, "Error", e)
                }
            }
        }
    }

    fun enableFaceDetection(faceDetectorCallback: FaceDetectorCallback?) {
        val fd: IntArray = cameraCharacteristics!!.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        val maxFD: Int = cameraCharacteristics!!.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT)
        if (fd.size > 0) {
            val fdList: MutableList<Int> = ArrayList()
            for (FaceD: Int in fd) {
                fdList.add(FaceD)
            }
            if (maxFD > 0) {
                this.faceDetectorCallback = faceDetectorCallback
                faceDetectionEnabled = true
                faceDetectionMode = Collections.max(fdList)
                setFaceDetect(builderInputSurface, faceDetectionMode)
                prepareFaceDetectionCallback()
            } else {
                Log.e(TAG, "No face detection")
            }
        } else {
            Log.e(TAG, "No face detection")
        }
    }

    fun disableFaceDetection() {
        if (faceDetectionEnabled) {
            faceDetectorCallback = null
            faceDetectionEnabled = false
            faceDetectionMode = 0
            prepareFaceDetectionCallback()
        }
    }

    fun isFaceDetectionEnabled(): Boolean {
        return faceDetectorCallback != null
    }

    private fun setFaceDetect(requestBuilder: CaptureRequest.Builder?, faceDetectMode: Int) {
        if (faceDetectionEnabled) {
            requestBuilder!!.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
        }
    }

    private fun prepareFaceDetectionCallback() {
        try {
            cameraCaptureSession!!.stopRepeating()
            cameraCaptureSession!!.setRepeatingRequest(builderInputSurface!!.build(),
                    if (faceDetectionEnabled) cb else null, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error", e)
        }
    }

    private val cb: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest, result: TotalCaptureResult) {
            val faces: Array<Face> = result.get(CaptureResult.STATISTICS_FACES)
            if (faceDetectorCallback != null) {
                faceDetectorCallback!!.onGetFaces(faces)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun openCameraId(cameraId: Int) {
        this.cameraId = cameraId
        if (isPrepared) {
            val cameraHandlerThread: HandlerThread = HandlerThread("$TAG Id = $cameraId")
            cameraHandlerThread.start()
            cameraHandler = Handler(cameraHandlerThread.looper)
            try {
                cameraManager.openCamera(cameraId.toString(), this, cameraHandler)
                cameraCharacteristics = cameraManager.getCameraCharacteristics(Integer.toString(cameraId))
                isRunning = true
                isFrontCamera = (CameraMetadata.LENS_FACING_FRONT == cameraCharacteristics!!.get(CameraCharacteristics.LENS_FACING))
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Error", e)
            }
        } else {
            Log.e(TAG, "Camera2ApiManager need be prepared, Camera2ApiManager not enabled")
        }
    }

    fun switchCamera() {
        val cameraId: Int = if (cameraDevice == null) 0 else if (cameraDevice!!.id.toInt() == 1) 0 else 1
        reOpenCamera(cameraId)
    }

    private fun reOpenCamera(cameraId: Int) {
        if (cameraDevice != null) {
            closeCamera(false)
            if (textureView != null) {
                prepareCamera(textureView, surfaceEncoder)
            } else if (surfaceView != null) {
                prepareCamera(surfaceView, surfaceEncoder)
            } else {
                prepareCamera(surfaceEncoder)
            }
            openCameraId(cameraId)
        }
    }

    val maxZoom: Float
        get() {
            return (if (cameraCharacteristics != null) cameraCharacteristics!!.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM).toFloat() else 1.0f)
        }

    var zoom: Float
        get() {
            return zoomLevel
        }
        set(level) {
            try {
                val maxZoom: Float = maxZoom
                val m: Rect = cameraCharacteristics!!.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if ((level <= maxZoom) && (level >= 1)) {
                    zoomLevel = level
                    val minW: Int = (m.width() / (maxZoom * 10)).toInt()
                    val minH: Int = (m.height() / (maxZoom * 10)).toInt()
                    val difW: Int = m.width() - minW
                    val difH: Int = m.height() - minH
                    var cropW: Int = (difW / 10 * level).toInt()
                    var cropH: Int = (difH / 10 * level).toInt()
                    cropW -= cropW and 3
                    cropH -= cropH and 3
                    val zoom: Rect = Rect(cropW, cropH, m.width() - cropW, m.height() - cropH)
                    builderInputSurface!!.set(CaptureRequest.SCALER_CROP_REGION, zoom)
                    cameraCaptureSession!!.setRepeatingRequest(builderInputSurface!!.build(),
                            if (faceDetectionEnabled) cb else null, null)
                }
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error", e)
            }
        }

    fun setZoom(event: MotionEvent) {
        val currentFingerSpacing: Float
        if (event.pointerCount > 1) {
            // Multi touch logic
            currentFingerSpacing = CameraHelper.getFingerSpacing(event)
            if (fingerSpacing != 0f) {
                if (currentFingerSpacing > fingerSpacing && maxZoom > zoomLevel) {
                    zoomLevel += 0.1f
                } else if (currentFingerSpacing < fingerSpacing && zoomLevel > 1) {
                    zoomLevel -= 0.1f
                }
                zoom = zoomLevel
            }
            fingerSpacing = currentFingerSpacing
        }
    }

    private fun resetCameraValues() {
        isLanternEnabled = false
        zoomLevel = 1.0f
    }

    fun stopRepeatingEncoder() {
        if (cameraCaptureSession != null) {
            try {
                cameraCaptureSession!!.stopRepeating()
                surfaceEncoder = null
                val captureRequest: CaptureRequest? = drawSurface(listOf(addPreviewSurface()))
                if (captureRequest != null) {
                    cameraCaptureSession!!.setRepeatingRequest(captureRequest, null, cameraHandler)
                }
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error", e)
            }
        }
    }

    @JvmOverloads
    fun closeCamera(resetSurface: Boolean = true) {
        resetCameraValues()
        cameraCharacteristics = null
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
        }
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        if (cameraHandler != null) {
            cameraHandler!!.looper.quitSafely()
            cameraHandler = null
        }
        if (resetSurface) {
            surfaceEncoder = null
            builderInputSurface = null
        }
        isPrepared = false
        isRunning = false
    }

    override fun onOpened(cameraDevice: CameraDevice) {
        this.cameraDevice = cameraDevice
        startPreview(cameraDevice)
        Log.i(TAG, "Camera opened")
    }

    override fun onDisconnected(cameraDevice: CameraDevice) {
        cameraDevice.close()
        Log.i(TAG, "Camera disconnected")
    }

    override fun onError(cameraDevice: CameraDevice, i: Int) {
        cameraDevice.close()
        Log.e(TAG, "Open failed")
    }

    init {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
}