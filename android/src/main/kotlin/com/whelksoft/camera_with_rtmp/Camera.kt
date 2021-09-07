package com.whelksoft.camera_with_rtmp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.CamcorderProfile
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.util.BitrateAdapter
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry.SurfaceTextureEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class Camera(
        val activity: Activity?,
        val flutterTexture: SurfaceTextureEntry,
        val dartMessenger: DartMessenger,
        val cameraName: String,
        val resolutionPreset: String?,
        val streamingPreset: String?,
        val enableAudio: Boolean,
        val useOpenGL: Boolean) : ConnectCheckerRtmp {
    private val cameraManager: CameraManager
    private val orientationEventListener: OrientationEventListener
    private val isFrontFacing: Boolean
    private val sensorOrientation: Int
    private val captureSize: Size
    private val previewSize: Size
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var pictureImageReader: ImageReader? = null
    private var imageStreamReader: ImageReader? = null
    private val recordingProfile: CamcorderProfile
    private val streamingProfile: CamcorderProfile
    private var currentOrientation = OrientationEventListener.ORIENTATION_UNKNOWN
    private var rtmpCamera: RtmpCameraConnector? = null
    private var bitrateAdapter: BitrateAdapter? = null
    private val maxRetries = 3
    private var currentRetries = 0
    private var publishUrl: String? = null

    // Mirrors camera.dart
    enum class ResolutionPreset {
        low, medium, high, veryHigh, ultraHigh, max
    }

    @Throws(IOException::class)
    private fun prepareCameraForRecordAndStream(fps: Int, bitrate: Int?) {
        if (rtmpCamera != null) {
            rtmpCamera!!.stopStream()
            rtmpCamera = null
        }
        Log.i(TAG, "prepareCameraForRecordAndStream(opengl=" + useOpenGL+ ", portrait: " + isPortrait +   ", currentOrientation: " + currentOrientation + ", mediaOrientation: " + mediaOrientation
         + ", frontfacing: " + isFrontFacing + ")" )
        rtmpCamera = RtmpCameraConnector(
                context = activity!!.applicationContext!!,
                useOpenGL = useOpenGL,
                isPortrait =  isPortrait,
                connectChecker = this)

        // Turn on audio if it is requested.
        if (enableAudio) {
            rtmpCamera!!.prepareAudio()
        }

        // Bitrate for the stream/recording.
        var bitrateToUse = bitrate
        if (bitrateToUse == null) {
            bitrateToUse = 1200 * 1024
        }

        rtmpCamera!!.prepareVideo(
                if (!isPortrait) streamingProfile.videoFrameWidth else streamingProfile.videoFrameHeight,
                if (!isPortrait) streamingProfile.videoFrameHeight else streamingProfile.videoFrameWidth,
                fps,
                bitrateToUse,
                !useOpenGL,
                mediaOrientation)
    }


    @SuppressLint("MissingPermission")
    @Throws(CameraAccessException::class)
    fun open(result: MethodChannel.Result) {
        pictureImageReader = ImageReader.newInstance(
                captureSize.width, captureSize.height, ImageFormat.JPEG, 2)


        // Used to steam image byte data to dart side.
        cameraManager.openCamera(
                cameraName,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        try {
                            startPreview()
                        } catch (e: CameraAccessException) {
                            result.error("CameraAccess", e.message, null)
                            close()
                            return
                        }
                        val reply: MutableMap<String, Any> = HashMap()
                        reply["textureId"] = flutterTexture.id()

                        if (isPortrait) {
                            reply["previewWidth"] = previewSize.width
                            reply["previewHeight"] = previewSize.height
                        } else {
                            reply["previewWidth"] = previewSize.height
                            reply["previewHeight"] = previewSize.width
                        }
                        reply["previewQuarterTurns"] = currentOrientation / 90
                        Log.i(TAG, "open: width: " + reply["previewWidth"] + " height: " + reply["previewHeight"] + " currentOrientation: " + currentOrientation + " quarterTurns: " + reply["previewQuarterTurns"])
                        result.success(reply)
                    }

                    override fun onClosed(camera: CameraDevice) {
                        dartMessenger.sendCameraClosingEvent()
                        super.onClosed(camera)
                    }

                    override fun onDisconnected(cameraDevice: CameraDevice) {
                        Log.v("Camera", "onDisconnected()")
                        close()
                        dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.")
                    }

                    override fun onError(cameraDevice: CameraDevice, errorCode: Int) {
                        Log.v("Camera", "onError(" + errorCode + ")")
                        close()
                        val errorDescription: String
                        errorDescription = when (errorCode) {
                            ERROR_CAMERA_IN_USE -> "The camera device is in use already."
                            ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                            ERROR_CAMERA_DISABLED -> "The camera device could not be opened due to a device policy."
                            ERROR_CAMERA_DEVICE -> "The camera device has encountered a fatal error"
                            ERROR_CAMERA_SERVICE -> "The camera service has encountered a fatal error."
                            else -> "Unknown camera error"
                        }
                        dartMessenger.send(DartMessenger.EventType.ERROR, errorDescription)
                    }
                },
                null)
    }

    @Throws(IOException::class)
    private fun writeToFile(buffer: ByteBuffer, file: File) {
        FileOutputStream(file).use { outputStream ->
            while (0 < buffer.remaining()) {
                outputStream.channel.write(buffer)
            }
        }
    }

    fun takePicture(filePath: String, result: MethodChannel.Result) {
        val file = File(filePath)
        if (file.exists()) {
            result.error(
                    "fileExists", "File at path '$filePath' already exists. Cannot overwrite.", null)
            return
        }

        pictureImageReader!!.setOnImageAvailableListener(
                { reader: ImageReader ->
                    try {
                        reader.acquireLatestImage().use { image ->
                            val buffer = image.planes[0].buffer
                            writeToFile(buffer, file)
                            result.success(null)
                        }
                    } catch (e: IOException) {
                        result.error("IOError", "Failed saving image", null)
                    }
                },
                null)
        try {
            // Create a new capture session with all this stuff in it.
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(pictureImageReader!!.surface)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, mediaOrientation)
            cameraCaptureSession!!.capture(
                    captureBuilder.build(),
                    object : CaptureCallback() {
                        override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure) {
                            val reason: String
                            reason = when (failure.reason) {
                                CaptureFailure.REASON_ERROR -> "An error happened in the framework"
                                CaptureFailure.REASON_FLUSHED -> "The capture has failed due to an abortCaptures() call"
                                else -> "Unknown reason"
                            }
                            result.error("captureFailure", reason, null)
                        }

                        // Close out the session once we have captured stuff.
                        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                            session.close()
                        }
                    },
                    null)
        } catch (e: CameraAccessException) {
            result.error("cameraAccess", e.message, null);
        }
    }


    @Throws(CameraAccessException::class)
    private fun createCaptureSession(
            templateType: Int, onSuccessCallback: Runnable, surface: Surface
    ) {
        // Close the old session first.
        closeCaptureSession()

        Log.v("Camera", "createCaptureSession " + previewSize.width + "x" + previewSize.height + " mediaOrientation: " + mediaOrientation + " currentOrientation: " + currentOrientation + " sensorOrientation: " + sensorOrientation + " porteait: " + isPortrait)

        // Create a new capture builder.
        val requestBuilder = cameraDevice!!.createCaptureRequest(templateType)

        // Collect all surfaces we want to render to.
        val surfaceList: MutableList<Surface> = ArrayList()

        // Build Flutter surface to render to
        val surfaceTexture = flutterTexture.surfaceTexture()
        if (isPortrait) {
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        } else {
            surfaceTexture.setDefaultBufferSize(previewSize.height, previewSize.width)
        }
        val flutterSurface = Surface(surfaceTexture)

        // The capture request.
        requestBuilder.addTarget(flutterSurface)
        if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
            requestBuilder.addTarget(surface)
        }

        // Create the surface lists for the capture session.
        surfaceList.add(flutterSurface)
        surfaceList.add(surface)

        // Prepare the callback
        val callback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onConfigured(session: CameraCaptureSession) {
                try {
                    if (cameraDevice == null) {
                        dartMessenger.send(
                                DartMessenger.EventType.ERROR, "The camera was closed during configuration.")
                        return
                    }
                    Log.v("Camera", "open successful ")
                    requestBuilder.set(
                            CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    session.setRepeatingRequest(requestBuilder.build(), null, null)
                    cameraCaptureSession = session
                    onSuccessCallback.run()
                } catch (e: CameraAccessException) {
                    Log.v("Camera", "Error CameraAccessException", e)
                    dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                } catch (e: IllegalStateException) {
                    Log.v("Camera", "Error IllegalStateException", e)
                    dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                } catch (e: IllegalArgumentException) {
                    Log.v("Camera", "Error IllegalArgumentException", e)
                    dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                }
            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                dartMessenger.send(
                        DartMessenger.EventType.ERROR, "Failed to configure camera session.")
            }
        }

        // Start the session
        cameraDevice!!.createCaptureSession(surfaceList, callback, null)
    }

    fun startVideoRecording(filePath: String, result: MethodChannel.Result) {
        if (File(filePath).exists()) {
            result.error("fileExists", "File at path '$filePath' already exists.", null)
            return
        }
        try {
            // If we are already setup we just start the recording part of everything instead.
            if (rtmpCamera == null) {
                prepareCameraForRecordAndStream(recordingProfile.videoFrameRate, null)
                createCaptureSession(
                        CameraDevice.TEMPLATE_RECORD,
                        Runnable { rtmpCamera!!.startRecord(filePath) },
                        rtmpCamera!!.inputSurface
                )
            } else {
                rtmpCamera!!.startRecord(filePath)
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoRecordingOrStreaming(result: MethodChannel.Result) {
        Log.i("Camera", "stopVideoRecordingOrStreaming ")

        if (rtmpCamera == null) {
            result.success(null)
            return
        }
        try {
            currentRetries = 0
            publishUrl = null
            if (rtmpCamera != null) {
                rtmpCamera!!.stopRecord()
                rtmpCamera!!.stopStream()
                rtmpCamera = null
            }

            startPreview()
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoRecording(result: MethodChannel.Result) {
        Log.i("Camera", "stopVideoRecording")

        if (rtmpCamera == null) {
            result.success(null)
            return
        }
        try {
            currentRetries = 0
            publishUrl = null
            if (rtmpCamera != null) {
                rtmpCamera!!.stopRecord()
                rtmpCamera = null
            }

            startPreview()
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoStreaming(result: MethodChannel.Result) {
        Log.i("Camera", "stopVideoRecording")

        if (rtmpCamera == null) {
            result.success(null)
            return
        }
        try {
            currentRetries = 0
            publishUrl = null
            if (rtmpCamera != null) {
                rtmpCamera!!.stopStream()
                rtmpCamera = null
            }

            startPreview()
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun pauseVideoRecording(result: MethodChannel.Result) {
        if (rtmpCamera == null || !rtmpCamera!!.isRecording) {
            result.success(null)
            return
        }
        try {
            rtmpCamera!!.pauseRecord()
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
            return
        }
        result.success(null)
    }

    fun resumeVideoRecording(result: MethodChannel.Result) {
        if (rtmpCamera == null || !rtmpCamera!!.isRecording) {
            result.success(null)
            return
        }
        try {
            rtmpCamera!!.resumeRecord()
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
            return
        }
        result.success(null)
    }

    @Throws(CameraAccessException::class)
    fun startPreview() {
        createCaptureSession(
                CameraDevice.TEMPLATE_PREVIEW,
                Runnable { },
        pictureImageReader!!.surface)
    }

    @Throws(CameraAccessException::class)
    fun startPreviewWithImageStream(imageStreamChannel: EventChannel) {
        imageStreamReader = ImageReader.newInstance(
                previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)

        createCaptureSession(
                CameraDevice.TEMPLATE_RECORD,
                Runnable {},
                imageStreamReader!!.surface)
        imageStreamChannel.setStreamHandler(
                object : EventChannel.StreamHandler {
                    override fun onListen(o: Any, imageStreamSink: EventSink) {
                        setImageStreamImageAvailableListener(imageStreamSink)
                    }

                    override fun onCancel(o: Any) {
                        imageStreamReader!!.setOnImageAvailableListener(null, null)
                    }
                })
    }

    private fun setImageStreamImageAvailableListener(imageStreamSink: EventSink) {
        imageStreamReader!!.setOnImageAvailableListener(
                { reader: ImageReader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val planes: MutableList<Map<String, Any>> = ArrayList()
                    for (plane in img.planes) {
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer[bytes, 0, bytes.size]
                        val planeBuffer: MutableMap<String, Any> = HashMap()
                        planeBuffer["bytesPerRow"] = plane.rowStride
                        planeBuffer["bytesPerPixel"] = plane.pixelStride
                        planeBuffer["bytes"] = bytes
                        planes.add(planeBuffer)
                    }
                    val imageBuffer: MutableMap<String, Any> = HashMap()
                    imageBuffer["width"] = img.width
                    imageBuffer["height"] = img.height
                    imageBuffer["format"] = img.format
                    imageBuffer["planes"] = planes
                    imageStreamSink.success(imageBuffer)
                    img.close()
                },
                null)
    }

    private fun closeCaptureSession() {
        if (cameraCaptureSession != null) {
            Log.v("Camera", "Close recoordingCaptureSession")
            try {
                cameraCaptureSession!!.stopRepeating()
                cameraCaptureSession!!.abortCaptures()
                cameraCaptureSession!!.close()
            } catch (e: CameraAccessException) {
                Log.w("RtmpCamera", "Error from camera", e)
            }
            cameraCaptureSession = null
        } else {
            Log.v("Camera", "No recoordingCaptureSession to close")
        }
    }

    fun close() {
        closeCaptureSession()
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        if (pictureImageReader != null) {
            pictureImageReader!!.close()
            pictureImageReader = null
        }
        if (imageStreamReader != null) {
            imageStreamReader!!.close()
            imageStreamReader = null
        }
        if (rtmpCamera != null) {
            rtmpCamera!!.stopStream()
            rtmpCamera = null
            bitrateAdapter = null
            publishUrl = null
        }
    }

    fun dispose() {
        close()
        flutterTexture.release()
        orientationEventListener.disable()
    }

    fun startVideoStreaming(url: String?, bitrate: Int?, result: MethodChannel.Result) {
        if (url == null) {
            result.error("fileExists", "Must specify a url.", null)
            return
        }
        try {
            // Setup the rtmp session
            if (rtmpCamera == null) {
                currentRetries = 0
                prepareCameraForRecordAndStream(streamingProfile.videoFrameRate, bitrate)

                // Start capturing from the camera.
                createCaptureSession(
                        CameraDevice.TEMPLATE_RECORD,
                        Runnable { rtmpCamera!!.startStream(url) },
                        rtmpCamera!!.inputSurface
                )
            } else {
                rtmpCamera!!.startStream(url)
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoStreamingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoStreamingFailed", e.message, null)
        }
    }

    fun startVideoRecordingAndStreaming(filePath: String, url: String?, bitrate: Int?, result: MethodChannel.Result) {
        if (File(filePath).exists()) {
            result.error("fileExists", "File at path '$filePath' already exists.", null)
            return
        }
        if (url == null) {
            result.error("fileExists", "Must specify a url.", null)
            return
        }
        try {
            // Setup the rtmp session
            currentRetries = 0
            prepareCameraForRecordAndStream(streamingProfile.videoFrameRate, bitrate)

            createCaptureSession(
                    CameraDevice.TEMPLATE_RECORD,
                    Runnable {
                        rtmpCamera!!.startStream(url)
                        rtmpCamera!!.startRecord(filePath)
                    },
                    rtmpCamera!!.inputSurface
            )
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }


    fun pauseVideoStreaming(result: MethodChannel.Result) {
        if (rtmpCamera == null || !rtmpCamera!!.isStreaming) {
            result.success(null)
            return
        }
        try {
            currentRetries = 0
            rtmpCamera!!.pauseStream()
        } catch (e: IllegalStateException) {
            result.error("videoStreamingFailed", e.message, null)
            return
        }
        result.success(null)
    }

    fun getStreamStatistics(result: MethodChannel.Result) {
        if (rtmpCamera != null) {
            var ret = hashMapOf<String, Any>();
            ret["cacheSize"] = rtmpCamera!!.cacheSize
            ret["sentAudioFrames"] = rtmpCamera!!.sentAudioFrames
            ret["sentVideoFrames"] = rtmpCamera!!.sentVideoFrames
            if (rtmpCamera!!.droppedAudioFrames == null) {
                ret["droppedAudioFrames"] = 0
            } else {
                ret["droppedAudioFrames"] = rtmpCamera!!.droppedAudioFrames
            }
            ret["droppedVideoFrames"] = rtmpCamera!!.droppedVideoFrames
            ret["isAudioMuted"] = rtmpCamera!!.isAudioMuted
            ret["bitrate"] = rtmpCamera!!.getBitrate()
            ret["width"] = rtmpCamera!!.getStreamWidth()
            ret["height"] = rtmpCamera!!.getStreamHeight()
            ret["fps"] = rtmpCamera!!.getFps()
            result.success(ret)
        } else {
            result.error("noStats", "Not streaming anything", null)
        }
    }

    fun resumeVideoStreaming(result: MethodChannel.Result) {
        if (rtmpCamera == null || !rtmpCamera!!.isStreaming) {
            result.success(null)
            return
        }
        try {
            rtmpCamera!!.resumeStream()
        } catch (e: IllegalStateException) {
            result.error("videoStreamingFailed", e.message, null)
            return
        }
        result.success(null)
    }


    private val mediaOrientation: Int
         get() {
            val sensorOrientationOffset = if (isFrontFacing)
                -currentOrientation
            else
                currentOrientation
            return (sensorOrientationOffset + sensorOrientation + 360) % 360
        }

    private val isPortrait: Boolean
        get() {
            val getOrient = activity!!.getWindowManager().getDefaultDisplay()
            val pt = Point()
            getOrient.getSize(pt)

            if (pt.x == pt.y) {
                return true
            } else {
                if (pt.x < pt.y) {
                    return true
                } else {
                    return false
                }
            }
        }

    init {
        checkNotNull(activity) { "No activity available!" }
        cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        orientationEventListener = object : OrientationEventListener(activity.applicationContext) {
            override fun onOrientationChanged(i: Int) {
                if (i == ORIENTATION_UNKNOWN) {
                    return
                }
                // Convert the raw deg angle to the nearest multiple of 90.
                currentOrientation = Math.round(i / 90.0).toInt() * 90
                // Send a message with the new orientation to the ux.
                dartMessenger.send(DartMessenger.EventType.ROTATION_UPDATE, (currentOrientation / 90).toString())

                Log.i(TAG, "Updated Orientation (sent) " + currentOrientation + " -- " + (currentOrientation / 90).toString())
            }
        }
        orientationEventListener.enable()
        val characteristics = cameraManager.getCameraCharacteristics(cameraName)
        isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        currentOrientation = Math.round(activity.resources.configuration.orientation / 90.0).toInt() * 90
        val preset = ResolutionPreset.valueOf(resolutionPreset!!)
        recordingProfile = CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset)
        captureSize = Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight)
        previewSize = CameraUtils.computeBestPreviewSize(cameraName, preset)

        // Data for streaming, different than the recording data.
        val streamPreset = ResolutionPreset.valueOf(streamingPreset!!)
        streamingProfile = CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, streamPreset)
    }

    override fun onAuthSuccessRtmp() {
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        if (bitrateAdapter != null) {
            bitrateAdapter!!.setMaxBitrate(bitrate.toInt());
        }
    }

    override fun onConnectionSuccessRtmp() {
        bitrateAdapter = BitrateAdapter(object : BitrateAdapter.Listener {
            override fun onBitrateAdapted(bitrate: Int) {
                rtmpCamera!!.setVideoBitrateOnFly(bitrate)
            }
        })
        bitrateAdapter!!.setMaxBitrate(rtmpCamera!!.getBitrate())
    }

    override fun onConnectionFailedRtmp(reason: String) {
        if (rtmpCamera != null) {
            // Retry first.
            for (i in currentRetries..maxRetries) {
                currentRetries = i
                if (rtmpCamera!!.reTry(5000, reason)) {
                    activity!!.runOnUiThread {
                        dartMessenger.send(DartMessenger.EventType.RTMP_RETRY, reason)
                    }
                    // Success!
                    return
                }
            }

            rtmpCamera!!.stopStream()
            rtmpCamera = null
            activity!!.runOnUiThread {
                dartMessenger.send(DartMessenger.EventType.RTMP_STOPPED, "Failed retry")
            }
        }
    }

    override fun onAuthErrorRtmp() {
        activity!!.runOnUiThread {
            dartMessenger.send(DartMessenger.EventType.ERROR, "Auth error")
        }
    }

    override fun onDisconnectRtmp() {
        if (rtmpCamera != null) {
            rtmpCamera!!.stopStream()
            rtmpCamera = null
        }
        activity!!.runOnUiThread {
            dartMessenger.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
        }
    }

    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        TODO("Not yet implemented")
    }

    companion object {
        private val TAG: String? = "FlutterCamera"
    }
}