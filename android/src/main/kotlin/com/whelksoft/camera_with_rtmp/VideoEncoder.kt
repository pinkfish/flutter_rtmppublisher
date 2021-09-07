package com.whelksoft.camera_with_rtmp

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Pair
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.video.FpsLimiter
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.utils.yuv.YUVUtil
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import java.io.IOException
import java.nio.ByteBuffer
import java.util.List

/*
 * Encodes the data going over the wire to the backend system, this handles talking
 * with the media encoder framework and shuttling this over to the rtmp system itself.
 */
class VideoEncoder(
        val getVideoData: GetVideoData,
        val width: Int,
        val height: Int,
        var fps: Int,
        var bitrate: Int,
        val rotation: Int,
        val doRotation: Boolean,
        val iFrameInterval: Int,
        val formatVideoEncoder: FormatVideoEncoder,
        val avcProfile: Int = -1,
        val avcProfileLevel: Int = -1) {
    private var spsPpsSetted = false

    // surface to buffer encoder
    var surface: Surface? = null

    // for disable video
    private val fpsLimiter: FpsLimiter = FpsLimiter()
    var type: String = CodecUtil.H264_MIME
    private var handlerThread: HandlerThread = HandlerThread(TAG)
    protected var codec: MediaCodec? = null
    private var callback: MediaCodec.Callback? = null
    private var isBufferMode: Boolean = false
    protected var presentTimeUs: Long = 0
    var force: CodecUtil.Force = CodecUtil.Force.FIRST_COMPATIBLE_FOUND
    private val bufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()

    @kotlin.jvm.Volatile
    public var running = false

    // The fps to limit at
    var limitFps = fps

    /**
     * Prepare encoder.
     */
    fun prepare(): Boolean {
        val encoder: MediaCodecInfo? = chooseEncoder(type)
        var videoEncoder: FormatVideoEncoder? = this.formatVideoEncoder
        return try {
            if (encoder != null) {
                codec = MediaCodec.createByCodecName(encoder.getName())
                if (videoEncoder == FormatVideoEncoder.YUV420Dynamical) {
                    videoEncoder = chooseColorDynamically(encoder)
                    if (videoEncoder == null) {
                        Log.e(TAG, "YUV420 dynamical choose failed")
                        return false
                    }
                }
            } else {
                Log.e(TAG, "Valid encoder not found")
                return false
            }
            val videoFormat: MediaFormat
            //if you dont use mediacodec rotation you need swap width and height in rotation 90 or 270
            // for correct encoding resolution
            val resolution: String = "" + width + "x" + height
            videoFormat = MediaFormat.createVideoFormat(type, width, height)
            Log.i(TAG, "Prepare video info: " + videoEncoder!!.name.toString() + ", " + resolution)
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, videoEncoder!!.getFormatCodec())
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            videoFormat.setInteger(MediaFormat.KEY_ROTATION, rotation)
            if (this.avcProfile > 0 && this.avcProfileLevel > 0) {
                // MediaFormat.KEY_PROFILE, API > 21
                videoFormat.setInteger(MediaFormat.KEY_PROFILE, this.avcProfile)
                // MediaFormat.KEY_LEVEL, API > 23
                videoFormat.setInteger(MediaFormat.KEY_LEVEL, this.avcProfileLevel)
            }
            codec!!.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            running = false
            isBufferMode = false
            surface = codec!!.createInputSurface()
            Log.i(TAG, "prepared")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Create VideoEncoder failed.", e)
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Create VideoEncoder failed.", e)
            false
        }
    }

    fun start() {
        spsPpsSetted = false
        presentTimeUs = System.nanoTime() / 1000
        fpsLimiter.setFPS(limitFps)
        if (formatVideoEncoder !== FormatVideoEncoder.SURFACE) {
            YUVUtil.preAllocateBuffers(width * height * 3 / 2)
        }
        handlerThread.start()
        val handler = Handler(handlerThread.getLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            createAsyncCallback()
            codec!!.setCallback(callback, handler)
            codec!!.start()
        } else {
            codec!!.start()
            handler.post(Runnable {
                while (running) {
                    try {
                        getDataFromEncoder()
                    } catch (e: IllegalStateException) {
                        Log.i(TAG, "Encoding error", e)
                    }
                }
            })
        }
        running = true
        Log.i(TAG, "started")
    }

    protected fun stopImp() {
        if (handlerThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                handlerThread.quitSafely()
            } else {
                handlerThread.quit()
            }
        }
        spsPpsSetted = false
        surface = null
        Log.i(TAG, "stopped")
    }

    fun stop() {
        running = false
        codec = try {
            codec!!.stop()
            codec!!.release()
            stopImp()
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: NullPointerException) {
            null
        }
    }

    fun reset() {
        stop()
        prepare()
        start()
    }

    private fun chooseColorDynamically(mediaCodecInfo: MediaCodecInfo): FormatVideoEncoder? {
        for (color in mediaCodecInfo.getCapabilitiesForType(type).colorFormats) {
            if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()) {
                return FormatVideoEncoder.YUV420PLANAR
            } else if (color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
                return FormatVideoEncoder.YUV420SEMIPLANAR
            }
        }
        return null
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    fun setVideoBitrateOnFly(bitrate: Int) {
        if (running) {
            this.bitrate = bitrate
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
            try {
                codec!!.setParameters(bundle)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "encoder need be running", e)
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    fun forceSyncFrame() {
        if (running) {
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            try {
                codec!!.setParameters(bundle)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "encoder need be running", e)
            }
        }
    }

    private fun sendSPSandPPS(mediaFormat: MediaFormat) {
        //H265
        if (type!!.equals(CodecUtil.H265_MIME)) {
            val byteBufferList = extractVpsSpsPpsFromH265(mediaFormat.getByteBuffer("csd-0")!!)
            getVideoData.onSpsPpsVps(byteBufferList!![1], byteBufferList[2], byteBufferList[0])
            //H264
        } else {
            getVideoData.onSpsPpsVps(mediaFormat.getByteBuffer("csd-0"), mediaFormat.getByteBuffer("csd-1"),null)
        }
    }

    /**
     * choose the video encoder by mime.
     */
    protected fun chooseEncoder(mime: String): MediaCodecInfo? {
        val mediaCodecInfoList: List<MediaCodecInfo>? = if (force === CodecUtil.Force.HARDWARE) {
            CodecUtil.getAllHardwareEncoders(mime) as List<MediaCodecInfo>
        } else if (force === CodecUtil.Force.SOFTWARE) {
            CodecUtil.getAllSoftwareEncoders(mime) as List<MediaCodecInfo>
        } else {
            CodecUtil.getAllEncoders(mime) as List<MediaCodecInfo>
        }
        for (mci in mediaCodecInfoList!!) {
            Log.i(TAG, String.format("VideoEncoder %s", mci.getName()))
            val codecCapabilities: MediaCodecInfo.CodecCapabilities = mci.getCapabilitiesForType(mime)
            for (color in codecCapabilities.colorFormats) {
                Log.i(TAG, "Color supported: $color")
                if (formatVideoEncoder === FormatVideoEncoder.SURFACE) {
                    if (color == FormatVideoEncoder.SURFACE.getFormatCodec()) return mci
                } else {
                    //check if encoder support any yuv420 color
                    if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()
                            || color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
                        return mci
                    }
                }
            }
        }
        return null
    }

    /**
     * decode sps and pps if the encoder never call to MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
     */
    private fun decodeSpsPpsFromBuffer(outputBuffer: ByteBuffer, length: Int): Pair<ByteBuffer, ByteBuffer>? {
        var mSPS: ByteArray? = null
        var mPPS: ByteArray? = null
        val csd = ByteArray(length)
        outputBuffer.get(csd, 0, length)
        var i = 0
        var spsIndex = -1
        var ppsIndex = -1
        while (i < length - 4) {
            if (csd[i].toInt() == 0 && csd[i + 1].toInt() == 0 && csd[i + 2].toInt() == 0 && csd[i + 3].toInt() == 1) {
                if (spsIndex.toInt() == -1) {
                    spsIndex = i
                } else {
                    ppsIndex = i
                    break
                }
            }
            i++
        }
        if (spsIndex != -1 && ppsIndex != -1) {
            mSPS = ByteArray(ppsIndex)
            System.arraycopy(csd, spsIndex, mSPS, 0, ppsIndex)
            mPPS = ByteArray(length - ppsIndex)
            System.arraycopy(csd, ppsIndex, mPPS, 0, length - ppsIndex)
        }
        return if (mSPS != null && mPPS != null) {
            Pair(ByteBuffer.wrap(mSPS), ByteBuffer.wrap(mPPS))
        } else null
    }

    /**
     * You need find 0 0 0 1 byte sequence that is the initiation of vps, sps and pps
     * buffers.
     *
     * @param csd0byteBuffer get in mediacodec case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
     * @return list with vps, sps and pps
     */
    private fun extractVpsSpsPpsFromH265(csd0byteBuffer: ByteBuffer): List<ByteBuffer> {
        val byteBufferList: MutableList<ByteBuffer> = mutableListOf<ByteBuffer>()
        var vpsPosition = -1
        var spsPosition = -1
        var ppsPosition = -1
        var contBufferInitiation = 0
        val csdArray: ByteArray = csd0byteBuffer.array()
        for (i in csdArray.indices) {
            if (contBufferInitiation.toInt() == 3 && csdArray[i].toInt() == 1) {
                if (vpsPosition.toInt() == -1) {
                    vpsPosition = i - 3
                } else if (spsPosition.toInt() == -1) {
                    spsPosition = i - 3
                } else {
                    ppsPosition = i - 3
                }
            }
            if (csdArray[i].toInt() == 0) {
                contBufferInitiation++
            } else {
                contBufferInitiation = 0
            }
        }
        val vps = ByteArray(spsPosition)
        val sps = ByteArray(ppsPosition - spsPosition)
        val pps = ByteArray(csdArray.size - ppsPosition)
        for (i in csdArray.indices) {
            if (i < spsPosition) {
                vps[i] = csdArray[i]
            } else if (i < ppsPosition) {
                sps[i - spsPosition] = csdArray[i]
            } else {
                pps[i - ppsPosition] = csdArray[i]
            }
        }
        byteBufferList.add(ByteBuffer.wrap(vps))
        byteBufferList.add(ByteBuffer.wrap(sps))
        byteBufferList.add(ByteBuffer.wrap(pps))
        return byteBufferList as List<ByteBuffer>
    }

    @kotlin.jvm.Throws(IllegalStateException::class)
    protected fun getDataFromEncoder() {
        Log.i(TAG, "getDataFromEncoder")

        while (running) {
            val outBufferIndex: Int = codec!!.dequeueOutputBuffer(bufferInfo, 1)
            if (outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val mediaFormat: MediaFormat = codec!!.getOutputFormat()
                formatChanged(codec!!, mediaFormat)
            } else if (outBufferIndex >= 0) {
                outputAvailable(codec!!, outBufferIndex, bufferInfo)
            } else {
                break
            }
        }
    }

    fun formatChanged(mediaCodec: MediaCodec, mediaFormat: MediaFormat) {
        getVideoData.onVideoFormat(mediaFormat)
        sendSPSandPPS(mediaFormat)
        spsPpsSetted = true
    }

    protected fun checkBuffer(byteBuffer: ByteBuffer,
                              bufferInfo: MediaCodec.BufferInfo) {
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG !== 0) {
            if (!spsPpsSetted) {
                val buffers: Pair<ByteBuffer, ByteBuffer>? = decodeSpsPpsFromBuffer(byteBuffer.duplicate(), bufferInfo.size)
                if (buffers != null) {
                    getVideoData.onSpsPpsVps(buffers.first, buffers.second, null)
                    spsPpsSetted = true
                }
            }
        }
    }

    protected fun sendBuffer(byteBuffer: ByteBuffer,
                             bufferInfo: MediaCodec.BufferInfo) {
        bufferInfo.presentationTimeUs = System.nanoTime() / 1000 - presentTimeUs
        getVideoData.getVideoData(byteBuffer, bufferInfo)
    }

    @kotlin.jvm.Throws(IllegalStateException::class)
    private fun processOutput(byteBuffer: ByteBuffer, mediaCodec: MediaCodec,
                              outBufferIndex: Int, bufferInfo: MediaCodec.BufferInfo) {
        if (running) {
            checkBuffer(byteBuffer, bufferInfo)
            sendBuffer(byteBuffer, bufferInfo)
        }
        Log.e(TAG, "releaseOutputBuffer " + outBufferIndex)
        mediaCodec.releaseOutputBuffer(outBufferIndex, false)
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun createAsyncCallback() {
        Log.i(TAG, "createAsyncCallback")
        callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mediaCodec: MediaCodec, inBufferIndex: Int) {
               Log.i(TAG, "onInputBufferAvailable ignored")
            }

            override fun onOutputBufferAvailable(mediaCodec: MediaCodec, outBufferIndex: Int,
                                                 bufferInfo: MediaCodec.BufferInfo) {
                try {
                    outputAvailable(mediaCodec, outBufferIndex, bufferInfo)
                } catch (e: IllegalStateException) {
                    Log.i(TAG, "Encoding error", e)
                }
            }

            override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Error", e)
            }

            override fun onOutputFormatChanged(mediaCodec: MediaCodec,
                                               mediaFormat: MediaFormat) {
                formatChanged(mediaCodec, mediaFormat)
            }
        }
    }

    fun outputAvailable(mediaCodec: MediaCodec, outBufferIndex: Int,
                        bufferInfo: MediaCodec.BufferInfo) {
        Log.e(TAG, "outputAvailable " + outBufferIndex)
        val byteBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaCodec.getOutputBuffer(outBufferIndex)
        } else {
            mediaCodec.getOutputBuffers().get(outBufferIndex)
        }
        processOutput(byteBuffer!!, mediaCodec, outBufferIndex, bufferInfo)
    }

    companion object {
        private val TAG: String? = "VideoEncoder"
    }
}
