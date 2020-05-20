package com.whelksoft.camera_with_rtmp


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.rtplibrary.util.BitrateAdapter
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry.SurfaceTextureEntry
import net.ossrs.rtmp.ConnectCheckerRtmp
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
        val enableAudio: Boolean) : ConnectCheckerRtmp {
    private val cameraManager: CameraManager
    private val orientationEventListener: OrientationEventListener
    private val isFrontFacing: Boolean
    private val sensorOrientation: Int
    private val captureSize: Size
    private val previewSize: Size
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var streamingCaptureSession: CameraCaptureSession? = null
    private var pictureImageReader: ImageReader? = null
    private var imageStreamReader: ImageReader? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var streamingRequestBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingVideo = false
    private var recordingRtmp = false
    private val recordingProfile: CamcorderProfile
    private val streamingProfile: CamcorderProfile
    private var currentOrientation = OrientationEventListener.ORIENTATION_UNKNOWN
    private var rtmpCamera: RtmpCamera2? = null
    private var bitrateAdapter: BitrateAdapter? = null
    private val maxRetries = 3
    private var currentRetries = 0
    private var publishUrl: String? = null

    // Mirrors camera.dart
    enum class ResolutionPreset {
        low, medium, high, veryHigh, ultraHigh, max
    }

    @Throws(IOException::class)
    private fun prepareMediaRecorder(outputFilePath: String) {
        if (mediaRecorder != null) {
            mediaRecorder!!.release()
        }
        mediaRecorder = MediaRecorder()

        // There's a specific order that mediaRecorder expects. Do not change the order
        // of these function calls.
        if (enableAudio) mediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder!!.setOutputFormat(recordingProfile.fileFormat)
        if (enableAudio) mediaRecorder!!.setAudioEncoder(recordingProfile.audioCodec)
        mediaRecorder!!.setVideoEncoder(recordingProfile.videoCodec)
        mediaRecorder!!.setVideoEncodingBitRate(recordingProfile.videoBitRate)
        if (enableAudio) mediaRecorder!!.setAudioSamplingRate(recordingProfile.audioSampleRate)
        mediaRecorder!!.setVideoFrameRate(recordingProfile.videoFrameRate)
        mediaRecorder!!.setVideoSize(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight)
        mediaRecorder!!.setOutputFile(outputFilePath)
        mediaRecorder!!.setOrientationHint(mediaOrientation)
        mediaRecorder!!.prepare()
        print("Video recorder " + recordingProfile.videoFrameWidth + "x" + recordingProfile.videoFrameHeight + "  " + recordingProfile.videoFrameRate + " fps "
        )
    }

    @Throws(IOException::class)
    private fun prepareRtmpPublished(url: String, fps: Int, bitrate: Int?) {
        if (rtmpCamera != null) {
            rtmpCamera!!.stopStream()
            rtmpCamera = null
        }
        rtmpCamera = RtmpCamera2(
                context = activity!!.applicationContext!!,
                connectChecker = this)

        rtmpCamera!!.prepareAudio()
        var bitrateToUse = bitrate
        if (bitrateToUse == null) {
            bitrateToUse = 1200 * 1024
        }
        rtmpCamera!!.prepareVideo(
                streamingProfile.videoFrameWidth,
                streamingProfile.videoFrameHeight,
                fps,
                bitrateToUse,
                true,
                mediaOrientation)
        publishUrl = url
    }


    @SuppressLint("MissingPermission")
    @Throws(CameraAccessException::class)
    fun open(result: MethodChannel.Result) {
        pictureImageReader = ImageReader.newInstance(
                captureSize.width, captureSize.height, ImageFormat.JPEG, 2)

        // Used to steam image byte data to dart side.
        imageStreamReader = ImageReader.newInstance(
                previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
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
                        reply["previewWidth"] = previewSize.width
                        reply["previewHeight"] = previewSize.height
                        result.success(reply)
                    }

                    override fun onClosed(camera: CameraDevice) {
                        dartMessenger.sendCameraClosingEvent()
                        super.onClosed(camera)
                    }

                    override fun onDisconnected(cameraDevice: CameraDevice) {
                        close()
                        dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.")
                    }

                    override fun onError(cameraDevice: CameraDevice, errorCode: Int) {
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
            val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val surfaceList: MutableList<Surface> = ArrayList()
            surfaceList.add(pictureImageReader!!.surface)
            requestBuilder.addTarget(pictureImageReader!!.surface)
            // Start the session
            cameraDevice!!.createCaptureSession(surfaceList, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        if (cameraDevice == null) {
                            dartMessenger.send(
                                    DartMessenger.EventType.ERROR, "The camera was closed during configuration.")
                            return
                        }
                        requestBuilder.set(
                                CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        session.capture(
                                requestBuilder.build(),
                                object : CaptureCallback() {
                                    override fun onCaptureFailed(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            failure: CaptureFailure) {
                                        val reason: String
                                        session.close()
                                        reason = when (failure.reason) {
                                            CaptureFailure.REASON_ERROR -> "An error happened in the framework"
                                            CaptureFailure.REASON_FLUSHED -> "The capture has failed due to an abortCaptures() call"
                                            else -> "Unknown reason"
                                        }
                                        result.error("captureFailure", reason, null)
                                    }

                                    // Close out the session once we have captured stuff.
                                    override fun onCaptureSequenceCompleted( session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                                        session.close()
                                    }
                                },
                                null)
                    } catch (e: CameraAccessException) {
                        dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                        session.close()
                    } catch (e: IllegalStateException) {
                        dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                        session.close()
                    } catch (e: IllegalArgumentException) {
                        dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                        session.close()
                    }
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    dartMessenger.send(
                            DartMessenger.EventType.ERROR, "Failed to configure camera session.")
                }

            }, null)
        } catch (e: CameraAccessException) {
            result.error("cameraAccess", e.message, null)
        }
    }

    @Throws(CameraAccessException::class)
    private fun createCaptureSession(
            templateType: Int, onSuccessCallback: Runnable, useFlutterSurface: Boolean, successCallback: (CameraCaptureSession) -> Unit, vararg surfaces: Surface?) : CaptureRequest.Builder {
        // Close any existing capture session.
        //closeCaptureSession(streaming)

        // Create a new capture builder.
        val requestBuilder = cameraDevice!!.createCaptureRequest(templateType)
        /*
        if (streaming) {
            streamingRequestBuilder = requestBuilder
        } else {
            captureRequestBuilder = requestBuilder
        }

         */

        // Build Flutter surface to render to
        var flutterSurface: Surface? = null
        if (!useFlutterSurface) {
            val surfaceTexture = flutterTexture.surfaceTexture()
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            flutterSurface = Surface(surfaceTexture)
            requestBuilder.addTarget(flutterSurface)
        }
        val remainingSurfacesNull = Arrays.asList(*surfaces)
        val remainingSurfaces = remainingSurfacesNull.filterNotNull()
        if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
            // If it is not preview mode, add all surfaces as targets.
            for (surface in remainingSurfaces) {
                requestBuilder.addTarget(surface)
            }
        }

        // Prepare the callback
        val callback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                try {
                    if (cameraDevice == null) {
                        dartMessenger.send(
                                DartMessenger.EventType.ERROR, "The camera was closed during configuration.")
                        return
                    }
                    successCallback(session)
                    requestBuilder.set(
                            CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    session.setRepeatingRequest(requestBuilder.build(), null, null)
                    onSuccessCallback?.run()
                } catch (e: CameraAccessException) {
                    dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                } catch (e: IllegalStateException) {
                    dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                } catch (e: IllegalArgumentException) {
                    dartMessenger.send(DartMessenger.EventType.ERROR, e.message)
                }
            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                dartMessenger.send(
                        DartMessenger.EventType.ERROR, "Failed to configure camera session.")
            }
        }

        // Collect all surfaces we want to render to.
        val surfaceList: MutableList<Surface> = ArrayList()
        if (flutterSurface != null && !useFlutterSurface) {
            surfaceList.add(flutterSurface!!)
        }
        surfaceList.addAll(remainingSurfaces)
        // Start the session
        cameraDevice!!.createCaptureSession(surfaceList, callback, null)
        return requestBuilder
    }

    fun startVideoRecording(filePath: String, result: MethodChannel.Result) {
        if (File(filePath).exists()) {
            result.error("fileExists", "File at path '$filePath' already exists.", null)
            return
        }
        try {
            prepareMediaRecorder(filePath)
            recordingVideo = true
            captureRequestBuilder = createCaptureSession(
                    CameraDevice.TEMPLATE_RECORD,
                    Runnable { mediaRecorder!!.start() },
                    false,
                    {
                        cameraCaptureSession = it;
                    },
                    mediaRecorder!!.surface
)
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoRecording(result: MethodChannel.Result) {
        if (!recordingVideo) {
            result.success(null)
            return
        }
        try {
            recordingVideo = false
            mediaRecorder!!.stop()
            mediaRecorder!!.reset()
            startPreview()
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun pauseVideoRecording(result: MethodChannel.Result) {
        if (!recordingVideo) {
            result.success(null)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder!!.pause()
            } else {
                result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null)
                return
            }
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
            return
        }
        result.success(null)
    }

    fun resumeVideoRecording(result: MethodChannel.Result) {
        if (!recordingVideo) {
            result.success(null)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder!!.resume()
            } else {
                result.error(
                        "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null)
                return
            }
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
            return
        }
        result.success(null)
    }

    @Throws(CameraAccessException::class)
    fun startPreview() {
        captureRequestBuilder= createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, Runnable {}, false,
                { cameraCaptureSession =it   },null)
    }

    @Throws(CameraAccessException::class)
    fun startPreviewWithImageStream(imageStreamChannel: EventChannel) {
        captureRequestBuilder = createCaptureSession(
                CameraDevice.TEMPLATE_RECORD,
                Runnable{},
                false,
                {
                    cameraCaptureSession = it;
                },
                imageStreamReader!!.surface,
                mediaRecorder?.surface)
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

    private fun closeCaptureSession(streaming: Boolean) {
        if (cameraCaptureSession != null && !streaming) {
            print("Close cameraCaptureSession")
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
        }
        if (streamingCaptureSession != null && streaming) {
            print("Close streamingCaptureSession")
            streamingCaptureSession!!.close()
            streamingCaptureSession = null
        }
    }

    fun close() {
        closeCaptureSession(true)
        closeCaptureSession(false)
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
        if (mediaRecorder != null) {
            mediaRecorder!!.reset()
            mediaRecorder!!.release()
            mediaRecorder = null
        }
        if (rtmpCamera != null) {
            rtmpCamera!!.stopStream();
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
            currentRetries = 0
            prepareRtmpPublished(url, streamingProfile.videoFrameRate, bitrate)
            streamingRequestBuilder = createCaptureSession(
                    CameraDevice.TEMPLATE_RECORD,
                    Runnable { rtmpCamera!!.startStream(url) },
                    true,
                    {
                        streamingCaptureSession = it;
                    },
                    rtmpCamera!!.inputSurface
            )
            recordingRtmp = true
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoStreamingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoStreamingFailed", e.message, null)
        }
    }

    fun stopVideoStreaming(result: MethodChannel.Result) {
        if (!recordingRtmp) {
            result.success(null)
            return
        }
        try {
            currentRetries = 0
            recordingRtmp = false
            publishUrl = null
            rtmpCamera!!.stopStream()
            startPreview()
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoStreamingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("videoStreamingFailed", e.message, null)
        }
    }

    fun pauseVideoStreaming(result: MethodChannel.Result) {
        if (!recordingRtmp) {
            result.success(null)
            return
        }
        try {
            currentRetries = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                rtmpCamera!!.stopStream()
            } else {
                result.error("videoStreamingFailed", "pauseVideoStreaming requires Android API +24.", null)
                return
            }
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
            ret["droppedAudioFrames"] = rtmpCamera!!.droppedAudioFrames
            ret["droppedVideoFrames"] = rtmpCamera!!.droppedVideoFrames
            ret["isAudioMuted"] = rtmpCamera!!.isAudioMuted
            ret["bitRate"] = rtmpCamera!!.getBitrate()
            ret["width"] = rtmpCamera!!.getStreamWidth()
            ret["height"] = rtmpCamera!!.getStreamHeight()
            result.success(ret)
        } else {
            result.error("noStats", "Not streaming anything", null)
        }
    }

    fun resumeVideoStreaming(result: MethodChannel.Result) {
        if (!recordingRtmp) {
            result.success(null)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                rtmpCamera!!.startStream(publishUrl!!)
            } else {
                result.error(
                        "videoStreamingFailed", "resumeVideoStreaming requires Android API +24.", null)
                return
            }
        } catch (e: IllegalStateException) {
            result.error("videoStreamingFailed", e.message, null)
            return
        }
        result.success(null)
    }


    private val mediaOrientation: Int
        private get() {
            val sensorOrientationOffset = if (currentOrientation == OrientationEventListener.ORIENTATION_UNKNOWN) 0 else if (isFrontFacing) -currentOrientation else currentOrientation
            return (sensorOrientationOffset + sensorOrientation + 360) % 360
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
            }
        }
        orientationEventListener.enable()
        val characteristics = cameraManager.getCameraCharacteristics(cameraName)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
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
        rtmpCamera!!.stopStream()
        activity!!.runOnUiThread {
            dartMessenger.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
        }
    }
}