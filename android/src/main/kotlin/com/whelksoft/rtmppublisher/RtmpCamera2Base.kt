package com.whelksoft.rtmppublisher

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
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
abstract class RtmpCamera2Base(val context: Context) : GetAacData, GetVideoData, GetMicrophoneData {
    protected val videoEncoder: VideoEncoder
    private var microphoneManager: MicrophoneManager
    private var audioEncoder: AudioEncoder

    /**
     * Get stream state.
     *
     * @return true if streaming, false if not streaming.
     */
    var isStreaming = false
        private set

    /**
     * Get video camera state
     *
     * @return true if disabled, false if enabled
     */
    var isVideoEnabled = false
        private set

    private val fpsListener = FpsListener()

    init {
        videoEncoder = VideoEncoder(this)
        microphoneManager = MicrophoneManager(this)
        audioEncoder = AudioEncoder(this)
    }

    /**
     * Set an audio effect modifying microphone's PCM buffer.
     */
    fun setCustomAudioEffect(customAudioEffect: CustomAudioEffect?) {
        microphoneManager.setCustomAudioEffect(customAudioEffect)
    }

    /**
     * @param callback get fps while record or stream
     */
    fun setFpsListener(callback: FpsListener.Callback?) {
        fpsListener.setCallback(callback)
    }

    val inputSurface: Surface get() = videoEncoder.inputSurface

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
        val result = videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation, hardwareRotation,
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
        val isHardwareRotation = true
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
        startEncoders()
        startStreamRtp(url)
    }

    private fun startEncoders() {
        videoEncoder!!.start()
        audioEncoder!!.start()
        microphoneManager!!.start()
    }

    private fun resetVideoEncoder() {
        videoEncoder!!.reset()
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
        microphoneManager!!.stop()
        videoEncoder!!.stop()
        audioEncoder!!.stop()

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

    private fun prepareCameraManager() {
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


    protected abstract fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo)
    override fun getAacData(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
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
        if (isStreaming) getH264DataRtp(h264Buffer, info)
    }

    override fun inputPCMData(frame: Frame) {
        audioEncoder!!.inputPCMData(frame)
    }

    override fun onVideoFormat(mediaFormat: MediaFormat) {
    }

    override fun onAudioFormat(mediaFormat: MediaFormat) {
    }
}