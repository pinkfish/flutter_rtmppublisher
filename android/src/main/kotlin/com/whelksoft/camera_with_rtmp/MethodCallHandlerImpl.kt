package com.whelksoft.camera_with_rtmp

import android.app.Activity
import android.hardware.camera2.CameraAccessException
import android.os.Build
import io.flutter.Log
import androidx.annotation.RequiresApi
import com.whelksoft.camera_with_rtmp.CameraPermissions.ResultCallback
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.view.TextureRegistry


internal class MethodCallHandlerImpl(
        private val activity: Activity,
        private val messenger: BinaryMessenger,
        private val cameraPermissions: CameraPermissions,
        private val permissionsRegistry: PermissionStuff,
        private val textureRegistry: TextureRegistry) : MethodCallHandler {
    private val methodChannel: MethodChannel
    private val imageStreamChannel: EventChannel
    private var camera: Camera? = null

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "availableCameras" -> try {
                result.success(CameraUtils.getAvailableCameras(activity))
            } catch (e: Exception) {
                handleException(e, result)
            }
            "initialize" -> {
                if (camera != null) {
                    camera!!.close()
                }
                cameraPermissions.requestPermissions(
                        activity,
                        permissionsRegistry,
                        call.argument("enableAudio")!!,
                        object : ResultCallback {
                            override fun onResult(errorCode: String?, errorDescription: String?) {
                                if (errorCode == null) {
                                    try {
                                        instantiateCamera(call, result)
                                    } catch (e: Exception) {
                                        handleException(e, result)
                                    }
                                } else {
                                    result.error(errorCode, errorDescription, null)
                                }
                            }
                        })
            }
            "takePicture" -> {
                camera!!.takePicture(call.argument("path")!!, result)
            }
            "prepareForVideoRecording" -> {

                // This optimization is not required for Android.
                result.success(null)
            }
            "startVideoRecording" -> {
                camera!!.startVideoRecording(call.argument("filePath")!!, result)
            }
            "startVideoStreaming" -> {
                Log.i("Stuff", call.arguments.toString())
                var bitrate: Int? = null
                if (call.hasArgument("bitrate")) {
                    bitrate = call.argument("bitrate")
                }

                camera!!.startVideoStreaming(
                        call.argument("url"),
                        bitrate,
                        result)
            }
            "startVideoRecordingAndStreaming" -> {
                Log.i("Stuff", call.arguments.toString())
                var bitrate: Int? = null
                if (call.hasArgument("bitrate")) {
                    bitrate = call.argument("bitrate")
                }
                camera!!.startVideoRecordingAndStreaming(
                        call.argument("filePath")!!,
                        call.argument("url"),
                        bitrate,
                        result)
            }
            "pauseVideoStreaming" -> {
                camera!!.pauseVideoStreaming(result)
            }
            "resumeVideoStreaming" -> {
                camera!!.resumeVideoStreaming(result)
            }
            "stopRecordingOrStreaming" -> {
                camera!!.stopVideoRecordingOrStreaming(result)
            }
            "stopRecording" -> {
                camera!!.stopVideoRecording(result)
            }
            "stopStreaming" -> {
                camera!!.stopVideoStreaming(result)
            }
            "pauseVideoRecording" -> {
                camera!!.pauseVideoRecording(result)
            }
            "resumeVideoRecording" -> {
                camera!!.resumeVideoRecording(result)
            }
            "startImageStream" -> {
                try {
                    camera!!.startPreviewWithImageStream(imageStreamChannel)
                    result.success(null)
                } catch (e: Exception) {
                    handleException(e, result)
                }
            }
            "stopImageStream" -> {
                try {
                    camera!!.startPreview()
                    result.success(null)
                } catch (e: Exception) {
                    handleException(e, result)
                }
            }
            "getStreamStatistics" -> {
                try {
                    camera!!.getStreamStatistics(result)
                } catch (e: Exception) {
                    handleException(e, result)
                }
            }
            "dispose" -> {
                if (camera != null) {
                    camera!!.dispose()
                }
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    fun stopListening() {
        methodChannel.setMethodCallHandler(null)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    @Throws(CameraAccessException::class)
    private fun instantiateCamera(call: MethodCall, result: MethodChannel.Result) {
        val cameraName = call.argument<String>("cameraName")
        val resolutionPreset = call.argument<String>("resolutionPreset")
        val streamingPreset = call.argument<String>("streamingPreset")
        val enableAudio = call.argument<Boolean>("enableAudio")!!
        var enableOpenGL = false
        if (call.hasArgument("enableAndroidOpenGL")) {
            enableOpenGL = call.argument<Boolean>("enableAndroidOpenGL")!!
        }
        val flutterSurfaceTexture = textureRegistry.createSurfaceTexture()
        val dartMessenger = DartMessenger(messenger, flutterSurfaceTexture.id())
        camera = Camera(
                activity,
                flutterSurfaceTexture,
                dartMessenger,
                cameraName!!,
                resolutionPreset,
                streamingPreset,
                enableAudio,
                enableOpenGL)
        camera!!.open(result)
    }

    // We move catching CameraAccessException out of onMethodCall because it causes a crash
    // on plugin registration for sdks incompatible with Camera2 (< 21). We want this plugin to
    // to be able to compile with <21 sdks for apps that want the camera and support earlier version.
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun handleException(exception: Exception, result: MethodChannel.Result) {
        if (exception is CameraAccessException) {
            result.error("CameraAccess", exception.message, null)
        }
        throw (exception as RuntimeException)
    }

    init {
        methodChannel = MethodChannel(messenger, "plugins.flutter.io/camera_with_rtmp")
        imageStreamChannel = EventChannel(messenger, "plugins.flutter.io/camera_with_rtmp/imageStream")
        methodChannel.setMethodCallHandler(this)
    }
}