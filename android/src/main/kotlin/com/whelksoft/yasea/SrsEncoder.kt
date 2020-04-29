package com.whelksoft.yasea

import android.content.res.Configuration
import android.graphics.Rect
import android.media.*
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Created by Leo Ma on 4/1/2016.
 */
class SrsEncoder(private val mHandler: SrsEncodeHandler) {
    private var flvMuxer: SrsFlvMuxer? = null
    private var mp4Muxer: SrsMp4Muxer? = null
    private var vmci: MediaCodecInfo? = null
    private var vencoder: MediaCodec? = null
    private var aencoder: MediaCodec? = null
    private var networkWeakTriggered = false
    private var mCameraFaceFront = true
    var isSoftEncoder = false
        private set
    private var canSoftEncode = false
    private var mPresentTimeUs: Long = 0
    private var mPausetime: Long = 0
    private val mVideoColorFormat: Int
    private var videoFlvTrack = 0
    private var videoMp4Track = 0
    private var audioFlvTrack = 0
    private var audioMp4Track = 0
    fun setFlvMuxer(flvMuxer: SrsFlvMuxer?) {
        this.flvMuxer = flvMuxer
    }

    fun setMp4Muxer(mp4Muxer: SrsMp4Muxer?) {
        this.mp4Muxer = mp4Muxer
    }

    fun start(): Boolean {
        if (flvMuxer == null || mp4Muxer == null) {
            return false
        }

        // the referent PTS for video and audio encoder.
        mPresentTimeUs = System.nanoTime() / 1000

        // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
        // Since Y component is quadruple size as U and V component, the stride must be set as 32x
        if (!isSoftEncoder && (outputWidth % 32 != 0 || outputHeight % 32 != 0)) {
            if (vmci!!.name.contains("MTK")) {
                //throw new AssertionError("MTK encoding revolution stride must be 32x");
            }
        }
        setEncoderResolution(outputWidth, outputHeight)
        setEncoderFps(VFPS)
        setEncoderGop(VGOP)
        // Unfortunately for some android phone, the output fps is less than 10 limited by the
        // capacity of poor cheap chips even with x264. So for the sake of quick appearance of
        // the first picture on the player, a spare lower GOP value is suggested. But note that
        // lower GOP will produce more I frames and therefore more streaming data flow.
        // setEncoderGop(15);
        setEncoderBitrate(vBitrate)
        setEncoderPreset(x264Preset)
        if (isSoftEncoder) {
            canSoftEncode = openSoftEncoder()
            if (!canSoftEncode) {
                return false
            }
        }

        // aencoder pcm to aac raw stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        aencoder = try {
            MediaCodec.createEncoderByType(ACODEC)
        } catch (e: IOException) {
            Log.e(TAG, "create aencoder failed.")
            e.printStackTrace()
            return false
        }

        // setup the aencoder.
        // @see https://developer.android.com/reference/android/media/MediaCodec.html
        val ach = if (aChannelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val audioFormat = MediaFormat.createAudioFormat(ACODEC, ASAMPLERATE, ach)
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE)
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
        aencoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // add the audio tracker to muxer.
        audioFlvTrack = flvMuxer!!.addTrack(audioFormat)
        audioMp4Track = mp4Muxer!!.addTrack(audioFormat)

        // vencoder yuv to 264 es stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        vencoder = try {
            MediaCodec.createByCodecName(vmci!!.name)
        } catch (e: IOException) {
            Log.e(TAG, "create vencoder failed.")
            e.printStackTrace()
            return false
        }

        // setup the vencoder.
        // Note: landscape to portrait, 90 degree rotation, so we need to switch width and height in configuration
        val videoFormat = MediaFormat.createVideoFormat(VCODEC, outputWidth, outputHeight)
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mVideoColorFormat)
        videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, vBitrate)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS)
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP / VFPS)
        vencoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // add the video tracker to muxer.
        videoFlvTrack = flvMuxer!!.addTrack(videoFormat)
        videoMp4Track = mp4Muxer!!.addTrack(videoFormat)

        // start device and encoder.
        vencoder.start()
        aencoder.start()
        return true
    }

    fun pause() {
        mPausetime = System.nanoTime() / 1000
    }

    fun resume() {
        val resumeTime = System.nanoTime() / 1000 - mPausetime
        mPresentTimeUs = mPresentTimeUs + resumeTime
        mPausetime = 0
    }

    fun stop() {
        if (isSoftEncoder) {
            closeSoftEncoder()
            canSoftEncode = false
        }
        if (aencoder != null) {
            Log.i(TAG, "stop aencoder")
            try {
                aencoder!!.stop()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            aencoder!!.release()
            aencoder = null
        }
        if (vencoder != null) {
            Log.i(TAG, "stop vencoder")
            try {
                vencoder!!.stop()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            vencoder!!.release()
            vencoder = null
        }
    }

    fun setCameraFrontFace() {
        mCameraFaceFront = true
    }

    fun setCameraBackFace() {
        mCameraFaceFront = false
    }

    fun switchToSoftEncoder() {
        isSoftEncoder = true
    }

    fun switchToHardEncoder() {
        isSoftEncoder = false
    }

    fun canHardEncode(): Boolean {
        return vencoder != null
    }

    fun canSoftEncode(): Boolean {
        return canSoftEncode
    }

    val isEnabled: Boolean
        get() = canHardEncode() || canSoftEncode()

    fun setPreviewResolution(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
    }

    fun setPortraitResolution(width: Int, height: Int) {
        outputWidth = width
        outputHeight = height
        vPortraitWidth = width
        vPortraitHeight = height
        vLandscapeWidth = height
        vLandscapeHeight = width
    }

    fun setLandscapeResolution(width: Int, height: Int) {
        outputWidth = width
        outputHeight = height
        vLandscapeWidth = width
        vLandscapeHeight = height
        vPortraitWidth = height
        vPortraitHeight = width
    }

    fun setVideoHDMode() {
        vBitrate = 1200 * 1024 // 1200 kbps
        x264Preset = "veryfast"
    }

    fun setVideoSmoothMode() {
        vBitrate = 500 * 1024 // 500 kbps
        x264Preset = "superfast"
    }

    fun setScreenOrientation(orientation: Int) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            outputWidth = vPortraitWidth
            outputHeight = vPortraitHeight
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            outputWidth = vLandscapeWidth
            outputHeight = vLandscapeHeight
        }

        // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
        // Since Y component is quadruple size as U and V component, the stride must be set as 32x
        if (!isSoftEncoder && (outputWidth % 32 != 0 || outputHeight % 32 != 0)) {
            if (vmci!!.name.contains("MTK")) {
                //throw new AssertionError("MTK encoding revolution stride must be 32x");
            }
        }
        setEncoderResolution(outputWidth, outputHeight)
    }

    private fun onProcessedYuvFrame(yuvFrame: ByteArray, pts: Long) {
        val inBuffers = vencoder!!.inputBuffers
        val outBuffers = vencoder!!.outputBuffers
        val inBufferIndex = vencoder!!.dequeueInputBuffer(-1)
        if (inBufferIndex >= 0) {
            val bb = inBuffers[inBufferIndex]
            bb.clear()
            bb.put(yuvFrame, 0, yuvFrame.size)
            vencoder!!.queueInputBuffer(inBufferIndex, 0, yuvFrame.size, pts, 0)
        }
        while (true) {
            val vebi = MediaCodec.BufferInfo()
            val outBufferIndex = vencoder!!.dequeueOutputBuffer(vebi, 0)
            if (outBufferIndex >= 0) {
                val bb = outBuffers[outBufferIndex]
                onEncodedAnnexbFrame(bb, vebi)
                vencoder!!.releaseOutputBuffer(outBufferIndex, false)
            } else {
                break
            }
        }
    }

    private fun onSoftEncodedData(es: ByteArray, pts: Long, isKeyFrame: Boolean) {
        val bb = ByteBuffer.wrap(es)
        val vebi = MediaCodec.BufferInfo()
        vebi.offset = 0
        vebi.size = es.size
        vebi.presentationTimeUs = pts
        vebi.flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        onEncodedAnnexbFrame(bb, vebi)
    }

    // when got encoded h264 es stream.
    private fun onEncodedAnnexbFrame(es: ByteBuffer, bi: MediaCodec.BufferInfo) {
        mp4Muxer!!.writeSampleData(videoMp4Track, es.duplicate(), bi)
        flvMuxer!!.writeSampleData(videoFlvTrack, es, bi)
    }

    // when got encoded aac raw stream.
    private fun onEncodedAacFrame(es: ByteBuffer, bi: MediaCodec.BufferInfo) {
        mp4Muxer!!.writeSampleData(audioMp4Track, es.duplicate(), bi)
        flvMuxer!!.writeSampleData(audioFlvTrack, es, bi)
    }

    fun onGetPcmFrame(data: ByteArray?, size: Int) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        val videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber()
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            val inBuffers = aencoder!!.inputBuffers
            val outBuffers = aencoder!!.outputBuffers
            val inBufferIndex = aencoder!!.dequeueInputBuffer(-1)
            if (inBufferIndex >= 0) {
                val bb = inBuffers[inBufferIndex]
                bb.clear()
                bb.put(data, 0, size)
                val pts = System.nanoTime() / 1000 - mPresentTimeUs
                aencoder!!.queueInputBuffer(inBufferIndex, 0, size, pts, 0)
            }
            while (true) {
                val aebi = MediaCodec.BufferInfo()
                val outBufferIndex = aencoder!!.dequeueOutputBuffer(aebi, 0)
                if (outBufferIndex >= 0) {
                    val bb = outBuffers[outBufferIndex]
                    onEncodedAacFrame(bb, aebi)
                    aencoder!!.releaseOutputBuffer(outBufferIndex, false)
                } else {
                    break
                }
            }
        }
    }

    fun onGetRgbaFrame(data: ByteArray, width: Int, height: Int) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        val videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber()
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            val pts = System.nanoTime() / 1000 - mPresentTimeUs
            if (isSoftEncoder) {
                swRgbaFrame(data, width, height, pts)
            } else {
                val processedData = hwRgbaFrame(data, width, height)
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts)
                } else {
                    mHandler.notifyEncodeIllegalArgumentException(IllegalArgumentException("libyuv failure"))
                }
            }
            if (networkWeakTriggered) {
                networkWeakTriggered = false
                mHandler.notifyNetworkResume()
            }
        } else {
            mHandler.notifyNetworkWeak()
            networkWeakTriggered = true
        }
    }

    fun onGetYuvNV21Frame(data: ByteArray, width: Int, height: Int, boundingBox: Rect) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        val videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber()
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            val pts = System.nanoTime() / 1000 - mPresentTimeUs
            if (isSoftEncoder) {
                throw UnsupportedOperationException("Not implemented")
                //swRgbaFrame(data, width, height, pts);
            } else {
                val processedData = hwYUVNV21FrameScaled(data, width, height, boundingBox)
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts)
                } else {
                    mHandler.notifyEncodeIllegalArgumentException(IllegalArgumentException("libyuv failure"))
                }
            }
            if (networkWeakTriggered) {
                networkWeakTriggered = false
                mHandler.notifyNetworkResume()
            }
        } else {
            mHandler.notifyNetworkWeak()
            networkWeakTriggered = true
        }
    }

    fun onGetArgbFrame(data: IntArray, width: Int, height: Int, boundingBox: Rect) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        val videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber()
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            val pts = System.nanoTime() / 1000 - mPresentTimeUs
            if (isSoftEncoder) {
                throw UnsupportedOperationException("Not implemented")
                //swArgbFrame(data, width, height, pts);
            } else {
                val processedData = hwArgbFrameScaled(data, width, height, boundingBox)
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts)
                } else {
                    mHandler.notifyEncodeIllegalArgumentException(IllegalArgumentException("libyuv failure"))
                }
            }
            if (networkWeakTriggered) {
                networkWeakTriggered = false
                mHandler.notifyNetworkResume()
            }
        } else {
            mHandler.notifyNetworkWeak()
            networkWeakTriggered = true
        }
    }

    fun onGetArgbFrame(data: IntArray, width: Int, height: Int) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        val videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber()
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            val pts = System.nanoTime() / 1000 - mPresentTimeUs
            if (isSoftEncoder) {
                throw UnsupportedOperationException("Not implemented")
                //swArgbFrame(data, width, height, pts);
            } else {
                val processedData = hwArgbFrame(data, width, height)
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts)
                } else {
                    mHandler.notifyEncodeIllegalArgumentException(IllegalArgumentException("libyuv failure"))
                }
            }
            if (networkWeakTriggered) {
                networkWeakTriggered = false
                mHandler.notifyNetworkResume()
            }
        } else {
            mHandler.notifyNetworkWeak()
            networkWeakTriggered = true
        }
    }

    private fun hwRgbaFrame(data: ByteArray, width: Int, height: Int): ByteArray {
        return when (mVideoColorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> RGBAToI420(data, width, height, true, 180)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> RGBAToNV12(data, width, height, true, 180)
            else -> throw IllegalStateException("Unsupported color format!")
        }
    }

    private fun hwYUVNV21FrameScaled(data: ByteArray, width: Int, height: Int, boundingBox: Rect): ByteArray {
        return when (mVideoColorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> NV21ToI420Scaled(data, width, height, true, 180, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height())
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> NV21ToNV12Scaled(data, width, height, true, 180, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height())
            else -> throw IllegalStateException("Unsupported color format!")
        }
    }

    private fun hwArgbFrameScaled(data: IntArray, width: Int, height: Int, boundingBox: Rect): ByteArray {
        return when (mVideoColorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> ARGBToI420Scaled(data, width, height, false, 0, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height())
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> ARGBToNV12Scaled(data, width, height, false, 0, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height())
            else -> throw IllegalStateException("Unsupported color format!")
        }
    }

    private fun hwArgbFrame(data: IntArray, inputWidth: Int, inputHeight: Int): ByteArray {
        return when (mVideoColorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> ARGBToI420(data, inputWidth, inputHeight, false, 0)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> ARGBToNV12(data, inputWidth, inputHeight, false, 0)
            else -> throw IllegalStateException("Unsupported color format!")
        }
    }

    private fun swRgbaFrame(data: ByteArray, width: Int, height: Int, pts: Long) {
        RGBASoftEncode(data, width, height, true, 180, pts)
    }

    fun chooseAudioRecord(): AudioRecord? {
        var mic: AudioRecord? = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, ASAMPLERATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, pcmBufferSize * 4)
        if (mic!!.state != AudioRecord.STATE_INITIALIZED) {
            mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, ASAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, pcmBufferSize * 4)
            if (mic.state != AudioRecord.STATE_INITIALIZED) {
                mic = null
            } else {
                aChannelConfig = AudioFormat.CHANNEL_IN_MONO
            }
        } else {
            aChannelConfig = AudioFormat.CHANNEL_IN_STEREO
        }
        return mic
    }

    private val pcmBufferSize: Int
        private get() {
            val pcmBufSize = AudioRecord.getMinBufferSize(ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT) + 8191
            return pcmBufSize - pcmBufSize % 8192
        }

    // choose the video encoder by name.
    private fun chooseVideoEncoder(name: String?): MediaCodecInfo? {
        val nbCodecs = MediaCodecList.getCodecCount()
        for (i in 0 until nbCodecs) {
            val mci = MediaCodecList.getCodecInfoAt(i)
            if (!mci.isEncoder) {
                continue
            }
            val types = mci.supportedTypes
            for (j in types.indices) {
                if (types[j].equals(VCODEC, ignoreCase = true)) {
                    Log.i(TAG, String.format("vencoder %s types: %s", mci.name, types[j]))
                    if (name == null) {
                        return mci
                    }
                    if (mci.name.contains(name)) {
                        return mci
                    }
                }
            }
        }
        return null
    }

    // choose the right supported color format. @see below:
    private fun chooseVideoEncoder(): Int {
        // choose the encoder "video/avc":
        //      1. select default one when type matched.
        //      2. google avc is unusable.
        //      3. choose qcom avc.
        vmci = chooseVideoEncoder(null)
        //vmci = chooseVideoEncoder("google");
        //vmci = chooseVideoEncoder("qcom");
        var matchedColorFormat = 0
        val cc = vmci!!.getCapabilitiesForType(VCODEC)
        for (i in cc.colorFormats.indices) {
            val cf = cc.colorFormats[i]
            Log.i(TAG, String.format("vencoder %s supports color fomart 0x%x(%d)", vmci!!.name, cf, cf))

            // choose YUV for h.264, prefer the bigger one.
            // corresponding to the color space transform in onPreviewFrame
            if (cf >= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar && cf <= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                if (cf > matchedColorFormat) {
                    matchedColorFormat = cf
                }
            }
        }
        for (i in cc.profileLevels.indices) {
            val pl = cc.profileLevels[i]
            Log.i(TAG, String.format("vencoder %s support profile %d, level %d", vmci!!.name, pl.profile, pl.level))
        }
        Log.i(TAG, String.format("vencoder %s choose color format 0x%x(%d)", vmci!!.name, matchedColorFormat, matchedColorFormat))
        return matchedColorFormat
    }

    private external fun setEncoderResolution(outWidth: Int, outHeight: Int)
    private external fun setEncoderFps(fps: Int)
    private external fun setEncoderGop(gop: Int)
    private external fun setEncoderBitrate(bitrate: Int)
    private external fun setEncoderPreset(preset: String)
    private external fun RGBAToI420(frame: ByteArray, width: Int, height: Int, flip: Boolean, rotate: Int): ByteArray
    private external fun RGBAToNV12(frame: ByteArray, width: Int, height: Int, flip: Boolean, rotate: Int): ByteArray
    private external fun ARGBToI420Scaled(frame: IntArray, width: Int, height: Int, flip: Boolean, rotate: Int, crop_x: Int, crop_y: Int, crop_width: Int, crop_height: Int): ByteArray
    private external fun ARGBToNV12Scaled(frame: IntArray, width: Int, height: Int, flip: Boolean, rotate: Int, crop_x: Int, crop_y: Int, crop_width: Int, crop_height: Int): ByteArray
    private external fun ARGBToI420(frame: IntArray, width: Int, height: Int, flip: Boolean, rotate: Int): ByteArray
    private external fun ARGBToNV12(frame: IntArray, width: Int, height: Int, flip: Boolean, rotate: Int): ByteArray
    private external fun NV21ToNV12Scaled(frame: ByteArray, width: Int, height: Int, flip: Boolean, rotate: Int, crop_x: Int, crop_y: Int, crop_width: Int, crop_height: Int): ByteArray
    private external fun NV21ToI420Scaled(frame: ByteArray, width: Int, height: Int, flip: Boolean, rotate: Int, crop_x: Int, crop_y: Int, crop_width: Int, crop_height: Int): ByteArray
    private external fun RGBASoftEncode(frame: ByteArray, width: Int, height: Int, flip: Boolean, rotate: Int, pts: Long): Int
    private external fun openSoftEncoder(): Boolean
    private external fun closeSoftEncoder()

    companion object {
        private const val TAG = "SrsEncoder"
        const val VCODEC = "video/avc"
        const val ACODEC = "audio/mp4a-latm"
        var x264Preset = "veryfast"
        var previewWidth = 640
        var previewHeight = 360
        var vPortraitWidth = 360
        var vPortraitHeight = 640
        var vLandscapeWidth = 640
        var vLandscapeHeight = 360
        var outputWidth = 360 // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
        var outputHeight = 640 // Since Y component is quadruple size as U and V component, the stride must be set as 32x
        var vBitrate = 1200 * 1024 // 1200 kbps
        const val VFPS = 24
        const val VGOP = 48
        const val ASAMPLERATE = 44100
        var aChannelConfig = AudioFormat.CHANNEL_IN_STEREO
        const val ABITRATE = 64 * 1024 // 64 kbps

        init {
            System.loadLibrary("yuv")
            System.loadLibrary("enc")
        }
    }

    // Y, U (Cb) and V (Cr)
    // yuv420                     yuv yuv yuv yuv
    // yuv420p (planar)   yyyy*2 uu vv
    // yuv420sp(semi-planner)   yyyy*2 uv uv
    // I420 -> YUV420P   yyyy*2 uu vv
    // YV12 -> YUV420P   yyyy*2 vv uu
    // NV12 -> YUV420SP  yyyy*2 uv uv
    // NV21 -> YUV420SP  yyyy*2 vu vu
    // NV16 -> YUV422SP  yyyy uv uv
    // YUY2 -> YUV422SP  yuyv yuyv
    init {
        mVideoColorFormat = chooseVideoEncoder()
    }
}