package com.whelksoft.rtmppublisher

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.RequiresApi
import com.pedro.encoder.Frame
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAacData
import com.pedro.encoder.input.audio.CustomAudioEffect
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.CameraHelper.Facing
import com.pedro.encoder.input.video.CameraOpenException
import com.pedro.encoder.utils.CodecUtil.Force
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.rtplibrary.util.FpsListener
import com.pedro.rtplibrary.util.RecordController
import com.pedro.rtplibrary.view.GlInterface
import com.pedro.rtplibrary.view.LightOpenGlView
import com.pedro.rtplibrary.view.OffScreenGlThread
import com.pedro.rtplibrary.view.OpenGlView
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*


/**
 * Wrapper to stream with camera2 api and microphone. Support stream with SurfaceView, TextureView,
 * OpenGlView(Custom SurfaceView that use OpenGl) and Context(background mode). All views use
 * Surface to buffer encoding mode for H264.
 *
 * API requirements:
 * API 21+.
 *
 * Created by pedro on 7/07/17.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
abstract class RtmpCamera2Base : GetAacData, GetVideoData, GetMicrophoneData {
    protected var context: Context
    private var cameraManager: Camera2ApiManager? = null
    protected var videoEncoder: VideoEncoder? = null
    private var microphoneManager: MicrophoneManager? = null
    private var audioEncoder: AudioEncoder? = null

    /**
     * Get stream state.
     *
     * @return true if streaming, false if not streaming.
     */
    var isStreaming = false
        private set
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var glInterface: GlInterface? = null
    private var surface: Surface? = null

    /**
     * Get video camera state
     *
     * @return true if disabled, false if enabled
     */
    var isVideoEnabled = false
        private set

    /**
     * Get preview state.
     *
     * @return true if enabled, false if disabled.
     */
    var isOnPreview = false
        private set
    private var isBackground = false
    private var recordController: RecordController? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private val fpsListener = FpsListener()

    constructor(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        context = surfaceView.context
        init(context)
    }

    constructor(context: Context, surface: Surface) {
        this.surface = surface
        this.context = context
        init(context)
    }

    constructor(textureView: TextureView) {
        this.textureView = textureView
        context = textureView.context
        init(context)
    }

    constructor(openGlView: OpenGlView) {
        context = openGlView.context
        glInterface = openGlView
        glInterface!!.init()
        init(context)
    }

    constructor(lightOpenGlView: LightOpenGlView) {
        context = lightOpenGlView.context
        glInterface = lightOpenGlView
        glInterface!!.init()
        init(context)
    }

    constructor(context: Context, useOpengl: Boolean) {
        this.context = context
        if (useOpengl) {
            glInterface = OffScreenGlThread(context)
            glInterface!!.init()
        }
        isBackground = true
        init(context)
    }

    private fun init(context: Context) {
        cameraManager = Camera2ApiManager(context)
        videoEncoder = VideoEncoder(this)
        microphoneManager = MicrophoneManager(this)
        audioEncoder = AudioEncoder(this)
        recordController = RecordController()
    }

    /**
     * Set an audio effect modifying microphone's PCM buffer.
     */
    fun setCustomAudioEffect(customAudioEffect: CustomAudioEffect?) {
        microphoneManager!!.setCustomAudioEffect(customAudioEffect)
    }

    /**
     * @param callback get fps while record or stream
     */
    fun setFpsListener(callback: FpsListener.Callback?) {
        fpsListener.setCallback(callback)
    }

    /**
     * Experimental
     */
    fun enableFaceDetection(faceDetectorCallback: Camera2ApiManager.FaceDetectorCallback?) {
        cameraManager!!.enableFaceDetection(faceDetectorCallback)
    }

    /**
     * Experimental
     */
    fun disableFaceDetection() {
        cameraManager!!.disableFaceDetection()
    }

    /**
     * Experimental
     */
    val isFaceDetectionEnabled: Boolean
        get() = cameraManager!!.isFaceDetectionEnabled

    val isFrontCamera: Boolean
        get() = cameraManager!!.isFrontCamera

    @Throws(Exception::class)
    fun enableLantern() {
        cameraManager!!.enableLantern()
    }

    fun disableLantern() {
        cameraManager!!.disableLantern()
    }

    val isLanternEnabled: Boolean
        get() = cameraManager!!.isLanternEnabled

    val isLanternSupported: Boolean
        get() = cameraManager!!.isLanternSupported

    /**
     * Basic auth developed to work with Wowza. No tested with other server
     *
     * @param user auth.
     * @param password auth.
     */
    abstract fun setAuthorization(user: String, password: String)

    /**
     * Call this method before use @startStream. If not you will do a stream without video.
     *
     * @param width resolution in px.
     * @param height resolution in px.
     * @param fps frames per second of the stream.
     * @param bitrate H264 in bps.
     * @param hardwareRotation true if you want rotate using encoder, false if you with OpenGl if you
     * are using OpenGlView.
     * @param rotation could be 90, 180, 270 or 0 (Normally 0 if you are streaming in landscape or 90
     * if you are streaming in Portrait). This only affect to stream result. NOTE: Rotation with
     * encoder is silence ignored in some devices.
     * @return true if success, false if you get a error (Normally because the encoder selected
     * doesn't support any configuration seated or your device hasn't a H264 encoder).
     */
    @JvmOverloads
    fun prepareVideo(width: Int, height: Int, fps: Int, bitrate: Int, hardwareRotation: Boolean,
                     iFrameInterval: Int, rotation: Int, avcProfile: Int = -1, avcProfileLevel: Int =
                             -1): Boolean {
        if (isOnPreview && !(glInterface != null && width == previewWidth && height == previewHeight)) {
            stopPreview()
            isOnPreview = true
        }
        val result = videoEncoder!!.prepareVideoEncoder(width, height, fps, bitrate, rotation, hardwareRotation,
                iFrameInterval, FormatVideoEncoder.SURFACE, avcProfile, avcProfileLevel)
        prepareCameraManager()
        return result
    }

    /**
     * backward compatibility reason
     */
    fun prepareVideo(width: Int, height: Int, fps: Int, bitrate: Int, hardwareRotation: Boolean,
                     rotation: Int): Boolean {
        return prepareVideo(width, height, fps, bitrate, hardwareRotation, 2, rotation)
    }

    protected abstract fun prepareAudioRtp(isStereo: Boolean, sampleRate: Int)
    /**
     * Call this method before use @startStream. If not you will do a stream without audio.
     *
     * @param bitrate AAC in kb.
     * @param sampleRate of audio in hz. Can be 8000, 16000, 22500, 32000, 44100.
     * @param isStereo true if you want Stereo audio (2 audio channels), false if you want Mono audio
     * (1 audio channel).
     * @param echoCanceler true enable echo canceler, false disable.
     * @param noiseSuppressor true enable noise suppressor, false  disable.
     * @return true if success, false if you get a error (Normally because the encoder selected
     * doesn't support any configuration seated or your device hasn't a AAC encoder).
     */
    /**
     * Same to call: prepareAudio(64 * 1024, 32000, true, false, false);
     *
     * @return true if success, false if you get a error (Normally because the encoder selected
     * doesn't support any configuration seated or your device hasn't a AAC encoder).
     */
    @JvmOverloads
    fun prepareAudio(bitrate: Int = 64 * 1024, sampleRate: Int = 32000, isStereo: Boolean = true, echoCanceler: Boolean = false,
                     noiseSuppressor: Boolean = false): Boolean {
        microphoneManager!!.createMicrophone(sampleRate, isStereo, echoCanceler, noiseSuppressor)
        prepareAudioRtp(isStereo, sampleRate)
        return audioEncoder!!.prepareAudioEncoder(bitrate, sampleRate, isStereo,
                microphoneManager!!.maxInputSize)
    }

    /**
     * Same to call: isHardwareRotation = true; if (openGlVIew) isHardwareRotation = false;
     * prepareVideo(640, 480, 30, 1200 * 1024, isHardwareRotation, 90);
     *
     * @return true if success, false if you get a error (Normally because the encoder selected
     * doesn't support any configuration seated or your device hasn't a H264 encoder).
     */
    fun prepareVideo(): Boolean {
        val isHardwareRotation = glInterface == null
        val rotation = CameraHelper.getCameraOrientation(context)
        return prepareVideo(640, 480, 30, 1200 * 1024, isHardwareRotation, rotation)
    }

    /**
     * @param forceVideo force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
     * @param forceAudio force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
     */
    fun setForce(forceVideo: Force, forceAudio: Force) {
        videoEncoder!!.setForce(forceVideo)
        audioEncoder!!.setForce(forceAudio)
    }

    /**
     * Start record a MP4 video. Need be called while stream.
     *
     * @param path where file will be saved.
     * @throws IOException If you init it before start stream.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun startRecord(path: String, listener: RecordController.Listener? = null) {
        recordController!!.startRecord(path, listener)
        if (!isStreaming) {
            startEncoders()
        } else if (videoEncoder!!.isRunning) {
            resetVideoEncoder()
        }
    }

    /**
     * Stop record MP4 video started with @startRecord. If you don't call it file will be unreadable.
     */
    fun stopRecord() {
        recordController!!.stopRecord()
        if (!isStreaming) stopStream()
    }

    fun replaceView(context: Context) {
        isBackground = true
        replaceGlInterface(OffScreenGlThread(context))
    }

    fun replaceView(openGlView: OpenGlView) {
        isBackground = false
        replaceGlInterface(openGlView)
    }

    fun replaceView(lightOpenGlView: LightOpenGlView) {
        isBackground = false
        replaceGlInterface(lightOpenGlView)
    }

    /**
     * Replace glInterface used on fly. Ignored if you use SurfaceView, TextureView or context without
     * OpenGl.
     */
    private fun replaceGlInterface(glInterface: GlInterface) {
        if (this.glInterface != null && Build.VERSION.SDK_INT >= 18) {
            if (isStreaming || isRecording() || isOnPreview) {
                cameraManager!!.closeCamera()
                this.glInterface!!.removeMediaCodecSurface()
                this.glInterface!!.stop()
                this.glInterface = glInterface
                this.glInterface!!.init()
                val isPortrait = CameraHelper.isPortrait(context)
                if (isPortrait) {
                    this.glInterface!!.setEncoderSize(videoEncoder!!.height, videoEncoder!!.width)
                } else {
                    this.glInterface!!.setEncoderSize(videoEncoder!!.width, videoEncoder!!.height)
                }
                this.glInterface!!.setRotation(
                        if (videoEncoder!!.rotation == 0) 270 else videoEncoder!!.rotation - 90)
                this.glInterface!!.start()
                if (isStreaming || isRecording()) {
                    this.glInterface!!.addMediaCodecSurface(videoEncoder!!.inputSurface)
                }
                cameraManager!!.prepareCamera(this.glInterface!!.surfaceTexture, videoEncoder!!.width,
                        videoEncoder!!.height)
                cameraManager!!.openLastCamera()
            } else {
                this.glInterface = glInterface
                this.glInterface!!.init()
            }
        }
    }

    /**
     * Start camera preview. Ignored, if stream or preview is started.
     *
     * @param cameraFacing front or back camera. Like: [com.pedro.encoder.input.video.CameraHelper.Facing.BACK]
     * [com.pedro.encoder.input.video.CameraHelper.Facing.FRONT]
     * @param rotation camera rotation (0, 90, 180, 270). Recommended: [ ][com.pedro.encoder.input.video.CameraHelper.getCameraOrientation]
     */
    @JvmOverloads
    fun startPreview(cameraFacing: Facing = Facing.BACK, width: Int = videoEncoder!!.width, height: Int = videoEncoder!!.height, rotation: Int = CameraHelper.getCameraOrientation(context)) {
        if (!isStreaming && !isOnPreview && !isBackground) {
            previewWidth = width
            previewHeight = height
            if (surface != null) {
                cameraManager!!.prepareCamera(surface)
            } else
            if (surfaceView != null) {
                cameraManager!!.prepareCamera(surfaceView!!.holder.surface)
            } else if (textureView != null) {
                cameraManager!!.prepareCamera(Surface(textureView!!.surfaceTexture))
            } else if (glInterface != null) {
                val isPortrait = CameraHelper.isPortrait(context)
                if (isPortrait) {
                    glInterface!!.setEncoderSize(height, width)
                } else {
                    glInterface!!.setEncoderSize(width, height)
                }
                glInterface!!.setRotation(if (rotation == 0) 270 else rotation - 90)
                glInterface!!.start()
                cameraManager!!.prepareCamera(glInterface!!.surfaceTexture, width, height)
            }
            cameraManager!!.openCameraFacing(cameraFacing)
            isOnPreview = true
        }
    }

    /**
     * Stop camera preview. Ignored if streaming or already stopped. You need call it after
     *
     * @stopStream to release camera properly if you will close activity.
     */
    fun stopPreview() {
        if (!isStreaming && !isRecording() && isOnPreview && !isBackground) {
            if (glInterface != null) {
                glInterface!!.stop()
            }
            cameraManager!!.closeCamera()
            isOnPreview = false
            previewWidth = 0
            previewHeight = 0
        }
    }

    protected abstract fun startStreamRtp(url: String)

    /**
     * Need be called after @prepareVideo or/and @prepareAudio. This method override resolution of
     *
     * @param url of the stream like: protocol://ip:port/application/streamName
     *
     * RTSP: rtsp://192.168.1.1:1935/live/pedroSG94 RTSPS: rtsps://192.168.1.1:1935/live/pedroSG94
     * RTMP: rtmp://192.168.1.1:1935/live/pedroSG94 RTMPS: rtmps://192.168.1.1:1935/live/pedroSG94
     * @startPreview to resolution seated in @prepareVideo. If you never startPreview this method
     * startPreview for you to resolution seated in @prepareVideo.
     */
    fun startStream(url: String) {
        isStreaming = true
        if (!recordController!!.isRunning) {
            startEncoders()
        } else {
            resetVideoEncoder()
        }
        startStreamRtp(url)
        isOnPreview = true
    }

    private fun startEncoders() {
        videoEncoder!!.start()
        audioEncoder!!.start()
        prepareGlView()
        microphoneManager!!.start()
        if (glInterface == null && !cameraManager!!.isRunning && videoEncoder!!.width != previewWidth
                || videoEncoder!!.height != previewHeight) {
            if (isOnPreview) {
                cameraManager!!.openLastCamera()
            } else {
                cameraManager!!.openCameraBack()
            }
        }
        isOnPreview = true
    }

    private fun resetVideoEncoder() {
        if (glInterface != null) {
            glInterface!!.removeMediaCodecSurface()
        }
        videoEncoder!!.reset()
        if (glInterface != null) {
            glInterface!!.addMediaCodecSurface(videoEncoder!!.inputSurface)
        } else {
            cameraManager!!.closeCamera()
            cameraManager!!.prepareCamera(videoEncoder!!.inputSurface)
            cameraManager!!.openLastCamera()
        }
    }

    private fun prepareGlView() {
        if (glInterface != null && isVideoEnabled) {
            if (glInterface is OffScreenGlThread) {
                glInterface = OffScreenGlThread(context)
                glInterface!!.init()
            }
            glInterface!!.setFps(videoEncoder!!.fps)
            if (videoEncoder!!.rotation == 90 || videoEncoder!!.rotation == 270) {
                glInterface!!.setEncoderSize(videoEncoder!!.height, videoEncoder!!.width)
            } else {
                glInterface!!.setEncoderSize(videoEncoder!!.width, videoEncoder!!.height)
            }
            val rotation = videoEncoder!!.rotation
            glInterface!!.setRotation(if (rotation == 0) 270 else rotation - 90)
            if (!cameraManager!!.isRunning && videoEncoder!!.width != previewWidth
                    || videoEncoder!!.height != previewHeight) {
                glInterface!!.start()
            }
            if (videoEncoder!!.inputSurface != null) {
                glInterface!!.addMediaCodecSurface(videoEncoder!!.inputSurface)
            }
            cameraManager!!.prepareCamera(glInterface!!.surfaceTexture, videoEncoder!!.width,
                    videoEncoder!!.height)
        }
    }

    protected abstract fun stopStreamRtp()

    /**
     * Stop stream started with @startStream.
     */
    fun stopStream() {
        if (isStreaming) {
            isStreaming = false
            stopStreamRtp()
        }
        if (!recordController!!.isRecording) {
            isOnPreview = !isBackground
            microphoneManager!!.stop()
            if (glInterface != null) {
                glInterface!!.removeMediaCodecSurface()
                if (glInterface is OffScreenGlThread) {
                    glInterface!!.stop()
                    cameraManager!!.closeCamera()
                }
            } else {
                if (isBackground) {
                    cameraManager!!.closeCamera()
                } else {
                    cameraManager!!.stopRepeatingEncoder()
                }
            }
            videoEncoder!!.stop()
            audioEncoder!!.stop()
            recordController!!.resetFormats()
        }
    }

    fun reTry(delay: Long, reason: String): Boolean {
        val result = shouldRetry(reason)
        if (result) {
            reTry(delay)
        }
        return result
    }

    /**
     * Replace with reTry(long delay, String reason);
     */
    @Deprecated("")
    fun reTry(delay: Long) {
        resetVideoEncoder()
        reConnect(delay)
    }

    /**
     * Replace with reTry(long delay, String reason);
     */
    @Deprecated("")
    abstract fun shouldRetry(reason: String): Boolean
    abstract fun setReTries(reTries: Int)
    protected abstract fun reConnect(delay: Long)

    //cache control
    @Throws(RuntimeException::class)
    abstract fun resizeCache(newSize: Int)
    abstract val cacheSize: Int
    abstract val sentAudioFrames: Long
    abstract val sentVideoFrames: Long
    abstract val droppedAudioFrames: Long
    abstract val droppedVideoFrames: Long

    abstract fun resetSentAudioFrames()
    abstract fun resetSentVideoFrames()
    abstract fun resetDroppedAudioFrames()
    abstract fun resetDroppedVideoFrames()

    /**
     * Get supported preview resolutions of back camera in px.
     *
     * @return list of preview resolutions supported by back camera
     */
    val resolutionsBack: List<Size>
        get() = Arrays.asList(*cameraManager!!.cameraResolutionsBack)

    /**
     * Get supported preview resolutions of front camera in px.
     *
     * @return list of preview resolutions supported by front camera
     */
    val resolutionsFront: List<Size>
        get() = Arrays.asList(*cameraManager!!.cameraResolutionsFront)

    /**
     * Get supported properties of the camera
     *
     * @return CameraCharacteristics object
     */
    val cameraCharacteristics: CameraCharacteristics
        get() = cameraManager!!.cameraCharacteristics

    /**
     * Mute microphone, can be called before, while and after stream.
     */
    fun disableAudio() {
        microphoneManager!!.mute()
    }

    /**
     * Enable a muted microphone, can be called before, while and after stream.
     */
    fun enableAudio() {
        microphoneManager!!.unMute()
    }

    /**
     * Get mute state of microphone.
     *
     * @return true if muted, false if enabled
     */
    val isAudioMuted: Boolean
        get() = microphoneManager!!.isMuted

    val maxZoom: Float
        get() = cameraManager!!.maxZoom

    /**
     * Return current zoom level
     *
     * @return current zoom level
     */
    fun getZoom(): Float {
        return cameraManager!!.zoom
    }

    /**
     * Set zoomIn or zoomOut to camera.
     * Use this method if you use a zoom slider.
     *
     * @param level Expected to be >= 1 and <= max zoom level
     * @see RtmpCamera2Base.getMaxZoom
     */
    fun setZoom(level: Float) {
        cameraManager!!.zoom = level
    }

    /**
     * Set zoomIn or zoomOut to camera.
     *
     * @param event motion event. Expected to get event.getPointerCount() > 1
     */
    fun setZoom(event: MotionEvent?) {
        cameraManager!!.setZoom(event)
    }

    fun getBitrate(): Int {
        return videoEncoder!!.bitRate
    }

    fun getResolutionValue(): Int {
        return videoEncoder!!.width * videoEncoder!!.height
    }

    fun getStreamWidth(): Int {
        return videoEncoder!!.width
    }

    fun getStreamHeight(): Int {
        return videoEncoder!!.height
    }

    /**
     * Switch camera used. Can be called on preview or while stream, ignored with preview off.
     *
     * @throws CameraOpenException If the other camera doesn't support same resolution.
     */
    @Throws(CameraOpenException::class)
    fun switchCamera() {
        if (isStreaming || isOnPreview) {
            cameraManager!!.switchCamera()
        }
    }

    fun getGlInterface(): GlInterface? {
        return if (glInterface != null) {
            glInterface
        } else {
            throw RuntimeException("You can't do it. You are not using Opengl")
        }
    }

    private fun prepareCameraManager() {
        if (surface != null) {
            cameraManager!!.prepareCamera(surface)
        } else
        if (textureView != null) {
            cameraManager!!.prepareCamera(textureView, videoEncoder!!.inputSurface)
        } else if (surfaceView != null) {
            cameraManager!!.prepareCamera(surfaceView, videoEncoder!!.inputSurface)
        } else if (glInterface != null) {
        } else {
            cameraManager!!.prepareCamera(videoEncoder!!.inputSurface)
        }
        isVideoEnabled = true
    }

    /**
     * Set video bitrate of H264 in bits per second while stream.
     *
     * @param bitrate H264 in bits per second.
     */
    fun setVideoBitrateOnFly(bitrate: Int) {
        videoEncoder!!.setVideoBitrateOnFly(bitrate)
    }

    /**
     * Set limit FPS while stream. This will be override when you call to prepareVideo method. This
     * could produce a change in iFrameInterval.
     *
     * @param fps frames per second
     */
    fun setLimitFPSOnFly(fps: Int) {
        videoEncoder!!.fps = fps
    }

    /**
     * Get record state.
     *
     * @return true if recording, false if not recoding.
     */
    fun isRecording(): Boolean {
        return recordController!!.isRunning
    }

    fun pauseRecord() {
        recordController!!.pauseRecord()
    }

    fun resumeRecord() {
        recordController!!.resumeRecord()
    }

    fun getRecordStatus(): RecordController.Status {
        return recordController!!.status
    }

    protected abstract fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo)
    override fun getAacData(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        recordController!!.recordAudio(aacBuffer, info)
        if (isStreaming) getAacDataRtp(aacBuffer, info)
    }

    protected abstract fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?)
    override fun onSpsPps(sps: ByteBuffer, pps: ByteBuffer) {
        if (isStreaming) onSpsPpsVpsRtp(sps, pps, null)
    }

    override fun onSpsPpsVps(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer) {
        if (isStreaming) onSpsPpsVpsRtp(sps, pps, vps)
    }

    protected abstract fun getH264DataRtp(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    override fun getVideoData(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        fpsListener.calculateFps()
        recordController!!.recordVideo(h264Buffer, info)
        if (isStreaming) getH264DataRtp(h264Buffer, info)
    }

    override fun inputPCMData(frame: Frame) {
        audioEncoder!!.inputPCMData(frame)
    }

    override fun onVideoFormat(mediaFormat: MediaFormat) {
        recordController!!.setVideoFormat(mediaFormat)
    }

    override fun onAudioFormat(mediaFormat: MediaFormat) {
        recordController!!.setAudioFormat(mediaFormat)
    }
}