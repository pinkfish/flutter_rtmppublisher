package  com.whelksoft.rtmppublisher;

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.*

/**
 * Created by pedro on 22/02/17.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class RtmpOnSurface(private val surface: Surface, context: Context) : CameraDevice.StateCallback(), ImageReader.OnImageAvailableListener {
    private val TAG = "RtmpOnSurface"
    private var cameraDevice: CameraDevice? = null
    private val cameraManager: CameraManager
    private var cameraHandler: Handler? = null

    //output
    private var imageReader: ImageReader? = null
    private var width = 640
    private var height = 480
    private var fps = 30
    private var imageFormat = ImageFormat.YUV_420_888
    fun setSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    fun setFps(fps: Int) {
        this.fps = fps
    }

    fun setImageFormat(imageFormat: Int) {
        this.imageFormat = imageFormat
    }

    private fun startPreview(cameraDevice: CameraDevice) {
        try {
            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(@NonNull cameraCaptureSession: CameraCaptureSession) {
                            try {
                                cameraCaptureSession.setRepeatingBurst(
                                        Arrays.asList(createCaptureRequest(), drawPreview()), null, cameraHandler)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(@NonNull cameraCaptureSession: CameraCaptureSession) {
                            cameraCaptureSession.close()
                            Log.e(TAG, "configuration failed")
                        }
                    }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun drawPreview(): CaptureRequest? {
        return try {
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureRequestBuilder.build()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            null
        }
    }

    private fun createCaptureRequest(): CaptureRequest? {
        return try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(imageReader!!.surface)
            builder.build()
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.message)
            null
        }
    }

    fun openCamera() {
        openCameraId(0)
    }

    fun openCameraId(cameraId: Int) {
        val cameraHandlerThread = HandlerThread("$TAG Id = $cameraId")
        cameraHandlerThread.start()
        cameraHandler = Handler(cameraHandlerThread.looper)
        try {
            imageReader = ImageReader.newInstance(width, height, imageFormat, fps)
            imageReader.setOnImageAvailableListener(this, cameraHandler)
            cameraManager.openCamera(cameraId.toString(), this, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun openCameraFront() {
        try {
            if (cameraManager.getCameraCharacteristics("0").get(CameraCharacteristics.LENS_FACING)
                    == CameraCharacteristics.LENS_FACING_FRONT) {
                openCameraId(0)
            } else {
                openCameraId(1)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun openCameraBack() {
        try {
            if (cameraManager.getCameraCharacteristics("0").get(CameraCharacteristics.LENS_FACING)
                    == CameraCharacteristics.LENS_FACING_BACK) {
                openCameraId(0)
            } else {
                openCameraId(1)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun switchCamera() {
        if (cameraDevice != null) {
            val cameraId = if (cameraDevice!!.id.toInt() == 1) 0 else 1
            closeCamera()
            openCameraId(cameraId)
        }
    }

    fun closeCamera() {
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        if (imageReader != null) {
            imageReader!!.close()
            imageReader = null
        }
        cameraHandler!!.looper.quitSafely()
    }

    override fun onOpened(@NonNull cameraDevice: CameraDevice) {
        this.cameraDevice = cameraDevice
        startPreview(cameraDevice)
        Log.i(TAG, "camera opened")
    }

    override fun onDisconnected(@NonNull cameraDevice: CameraDevice) {
        cameraDevice.close()
        Log.i(TAG, "camera disconnected")
    }

    override fun onError(@NonNull cameraDevice: CameraDevice, i: Int) {
        cameraDevice.close()
        Log.e(TAG, "open failed")
    }

    override fun onImageAvailable(imageReader: ImageReader) {
        Log.i(TAG, "new frame")
        val image = imageReader.acquireLatestImage()
        image.close()
    }

    init {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
}