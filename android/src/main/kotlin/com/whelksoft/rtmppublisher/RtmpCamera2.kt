package com.whelksoft.rtmppublisher

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.RequiresApi
import com.pedro.rtplibrary.view.LightOpenGlView
import com.pedro.rtplibrary.view.OpenGlView
import net.ossrs.rtmp.ConnectCheckerRtmp
import net.ossrs.rtmp.SrsFlvMuxer
import java.nio.ByteBuffer

/**
 * More documentation see:
 * [com.pedro.rtplibrary.base.Camera2Base]
 *
 * Created by pedro on 6/07/17.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class RtmpCamera2 : RtmpCamera2Base {
    private var srsFlvMuxer: SrsFlvMuxer

    constructor(surfaceView: SurfaceView, connectChecker: ConnectCheckerRtmp?) : super(surfaceView) {
        srsFlvMuxer = SrsFlvMuxer(connectChecker)
    }

    constructor(context: Context, surface: Surface, connectChecker: ConnectCheckerRtmp?) : super(context, surface) {
        srsFlvMuxer = SrsFlvMuxer(connectChecker)
    }

    constructor(textureView: TextureView, connectChecker: ConnectCheckerRtmp?) : super(textureView) {
        srsFlvMuxer = SrsFlvMuxer(connectChecker)
    }

    constructor(openGlView: OpenGlView, connectChecker: ConnectCheckerRtmp?) : super(openGlView) {
        srsFlvMuxer = SrsFlvMuxer(connectChecker)
    }

    constructor(lightOpenGlView: LightOpenGlView, connectChecker: ConnectCheckerRtmp?) : super(lightOpenGlView) {
        srsFlvMuxer = SrsFlvMuxer(connectChecker)
    }

    constructor(context: Context, useOpengl: Boolean, connectChecker: ConnectCheckerRtmp?) : super(context, useOpengl) {
        srsFlvMuxer = SrsFlvMuxer(connectChecker)
    }

    /**
     * H264 profile.
     *
     * @param profileIop Could be ProfileIop.BASELINE or ProfileIop.CONSTRAINED
     */
    fun setProfileIop(profileIop: Byte) {
        srsFlvMuxer.setProfileIop(profileIop)
    }

    @Throws(RuntimeException::class)
    override fun resizeCache(newSize: Int) {
        srsFlvMuxer.resizeFlvTagCache(newSize)
    }

    override val cacheSize: Int
        get() = srsFlvMuxer.flvTagCacheSize

    override val sentAudioFrames: Long
        get() = srsFlvMuxer.sentAudioFrames


    override val sentVideoFrames: Long
       get() = srsFlvMuxer.sentVideoFrames


    override val droppedAudioFrames: Long get() =
         srsFlvMuxer.droppedAudioFrames


    override val droppedVideoFrames: Long get() =
         srsFlvMuxer.droppedVideoFrames


    override fun resetSentAudioFrames() {
        srsFlvMuxer.resetSentAudioFrames()
    }

    override fun resetSentVideoFrames() {
        srsFlvMuxer.resetSentVideoFrames()
    }

    override fun resetDroppedAudioFrames() {
        srsFlvMuxer.resetDroppedAudioFrames()
    }

    override fun resetDroppedVideoFrames() {
        srsFlvMuxer.resetDroppedVideoFrames()
    }

    override fun setAuthorization(user: String, password: String) {
        srsFlvMuxer.setAuthorization(user, password)
    }

    override fun prepareAudioRtp(isStereo: Boolean, sampleRate: Int) {
        srsFlvMuxer.setIsStereo(isStereo)
        srsFlvMuxer.setSampleRate(sampleRate)
    }

    override fun startStreamRtp(url: String) {
        if (videoEncoder!!.rotation == 90 || videoEncoder!!.rotation == 270) {
            srsFlvMuxer.setVideoResolution(videoEncoder!!.height, videoEncoder!!.width)
        } else {
            srsFlvMuxer.setVideoResolution(videoEncoder!!.width, videoEncoder!!.height)
        }
        srsFlvMuxer.start(url)
    }

    override fun stopStreamRtp() {
        srsFlvMuxer.stop()
    }

    override fun setReTries(reTries: Int) {
        srsFlvMuxer.setReTries(reTries)
    }

    override fun shouldRetry(reason: String): Boolean {
        return srsFlvMuxer.shouldRetry(reason)
    }

    public override fun reConnect(delay: Long) {
        srsFlvMuxer.reConnect(delay)
    }

    override fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        srsFlvMuxer.sendAudio(aacBuffer, info)
    }

    override fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
        srsFlvMuxer.setSpsPPs(sps, pps)
    }

    override fun getH264DataRtp(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        srsFlvMuxer.sendVideo(h264Buffer, info)
    }
}