package com.whelksoft.rtmppublisher

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.CamcorderProfile
import android.media.ImageReader
import android.os.Build
import android.os.Process
import android.view.Surface
import androidx.annotation.RequiresApi
import com.github.faucamp.simplertmp.RtmpHandler
import net.ossrs.yasea.SrsEncodeHandler
import net.ossrs.yasea.SrsEncoder
import net.ossrs.yasea.SrsFlvMuxer
import java.io.IOException
import java.net.SocketException


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class RtmpPublisher(var url: String, var messenger: DartMessenger, var mediaProfile: CamcorderProfile) : RtmpHandler.RtmpListener, SrsEncodeHandler.SrsEncodeListener {
    private var worker: Thread? = null
    private var srsEncoder: SrsEncoder
    private var flvMuxer: SrsFlvMuxer
    private var rtmpHandler: RtmpHandler
    private var rtmpImageReader: ImageReader
    private val mPcmBuffer = ByteArray(4096)
    var surface: Surface

    init {
        rtmpHandler = RtmpHandler(this)
        flvMuxer = SrsFlvMuxer(rtmpHandler)
        srsEncoder = SrsEncoder(SrsEncodeHandler(this))
        srsEncoder.setFlvMuxer(flvMuxer)
        rtmpImageReader = ImageReader.newInstance(
                mediaProfile.videoFrameWidth, mediaProfile.videoFrameHeight, ImageFormat.YUV_420_888, 2)
        surface = rtmpImageReader.surface
    }


    fun prepare() {
        rtmpImageReader.setOnImageAvailableListener({ reader: ImageReader ->
            try {
                reader.acquireLatestImage().use { image ->
                    val buffer = image.planes[0].buffer
                    srsEncoder.onGetYuvNV21Frame(buffer.array(), image.width, image.height, Rect(0, 0, image.width, image.height))
                }
            } catch (e: IOException) {
                messenger.send(DartMessenger.EventType.ERROR, e!!.message)
            }
        }, null)

        worker = Thread(Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            //mic.startRecording()
            while (!Thread.interrupted()) {
                //if (sendVideoOnly) {
                srsEncoder.onGetPcmFrame(mPcmBuffer, mPcmBuffer.size)
                try {
                    // This is trivial...
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                }
                // } else {
                //      val size: Int = mic.read(mPcmBuffer, 0, mPcmBuffer.length)
                //     if (size > 0) {
                //         mEncoder.onGetPcmFrame(mPcmBuffer, size)
                //     }
                // }
            }
        })
        worker!!.start()
    }

    fun release() {
        if (worker != null) {
            worker!!.interrupt();
            try {
                worker!!.join();
            } catch (e: InterruptedException) {
                worker!!.interrupt();
            }
            worker = null;
        }
        srsEncoder.stop();
        flvMuxer.stop()
    }

    fun start() {
        srsEncoder.start()
        flvMuxer.start(url)
    }

    fun stop() {
        srsEncoder.stop()
        flvMuxer.stop()
    }

    fun reset() {

    }

    fun pause() {
        srsEncoder.pause()
    }

    fun resume() {
        srsEncoder.resume()
    }

    override fun onRtmpConnected(msg: String?) {
        messenger.send(DartMessenger.EventType.RTMP_CONNECTED, "Connected to rtmp")
    }

    override fun onRtmpIllegalStateException(e: IllegalStateException?) {
    }

    override fun onRtmpStopped() {
        messenger.send(DartMessenger.EventType.RTMP_STOPPED, "RTMP stopped")
    }

    override fun onRtmpIOException(e: IOException?) {
        messenger.send(DartMessenger.EventType.ERROR, e!!.message)
    }

    override fun onRtmpAudioStreaming() {
    }

    override fun onRtmpSocketException(e: SocketException?) {
        messenger.send(DartMessenger.EventType.ERROR, e!!.message)
    }

    override fun onRtmpDisconnected() {
    }

    override fun onRtmpVideoFpsChanged(fps: Double) {
    }

    override fun onRtmpConnecting(msg: String?) {
    }

    override fun onRtmpVideoStreaming() {
    }

    override fun onRtmpAudioBitrateChanged(bitrate: Double) {
    }

    override fun onRtmpVideoBitrateChanged(bitrate: Double) {
    }

    override fun onRtmpIllegalArgumentException(e: IllegalArgumentException?) {
    }

    override fun onEncodeIllegalArgumentException(e: IllegalArgumentException?) {
    }

    override fun onNetworkWeak() {
    }

    override fun onNetworkResume() {
    }
}