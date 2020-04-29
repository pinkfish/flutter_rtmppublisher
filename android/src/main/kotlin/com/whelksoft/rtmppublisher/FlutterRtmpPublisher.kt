package com.whelksoft.rtmppublisher

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.CamcorderProfile
import android.media.ImageReader
import android.os.Build
import android.os.Process
import android.view.Surface
import androidx.annotation.RequiresApi
import com.github.faucamp.simplertmp.DefaultRtmpPublisher
import com.github.faucamp.simplertmp.RtmpPublisher
import net.ossrs.rtmp.ConnectCheckerRtmp
import net.ossrs.rtmp.SrsFlvMuxer
import java.io.IOException


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class FlutterRtmpPublisher(var url: String, var messenger: DartMessenger, var mediaProfile: CamcorderProfile) : ConnectCheckerRtmp, SrsEncodeHandler.SrsEncodeListener {
    private var worker: Thread? = null
    private var flvMuxer: SrsFlvMuxer
    private var rtmpImageReader: ImageReader
    private val mPcmBuffer = ByteArray(4096)
    var surface: Surface

    init {
        flvMuxer = SrsFlvMuxer(this)
        rtmpImageReader = ImageReader.newInstance(
                mediaProfile.videoFrameWidth, mediaProfile.videoFrameHeight, ImageFormat.YUV_420_888, 2)
        surface = rtmpImageReader.surface
    }


    fun prepare() {
        flvMuxer.setVideoResolution(mediaProfile.videoFrameWidth, mediaProfile.videoFrameHeight);
        rtmpImageReader.setOnImageAvailableListener({ reader: ImageReader ->
            try {
                reader.acquireLatestImage().use { image ->
                    val buffer = image.planes[0].buffer
                    flvMuxer.sendVideo(buffer, rtmpImageReader.)
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
            worker!!.interrupt()
            try {
                worker!!.join()
            } catch (e: InterruptedException) {
                worker!!.interrupt()
            }
            worker = null
        }
        srsEncoder.stop()
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


    override fun onEncodeIllegalArgumentException(e: IllegalArgumentException?) {
    }

    override fun onNetworkWeak() {
    }

    override fun onNetworkResume() {
    }

    override fun onAuthSuccessRtmp() {
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
    }

    override fun onConnectionSuccessRtmp() {
        messenger.send(DartMessenger.EventType.RTMP_CONNECTED, "Connected to rtmp")
    }

    override fun onConnectionFailedRtmp(reason: String) {
        messenger.send(DartMessenger.EventType.ERROR, reason)
    }

    override fun onAuthErrorRtmp() {
        messenger.send(DartMessenger.EventType.ERROR, "Auth error")
    }

    override fun onDisconnectRtmp() {
        messenger.send(DartMessenger.EventType.RTMP_STOPPED, "RTMP stopped")
    }
}