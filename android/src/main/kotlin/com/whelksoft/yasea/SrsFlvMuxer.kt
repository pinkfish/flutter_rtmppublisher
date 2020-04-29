package com.whelksoft.yasea

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.github.faucamp.simplertmp.DefaultRtmpPublisher
import com.github.faucamp.simplertmp.RtmpHandler
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by winlin on 5/2/15.
 * Updated by leoma on 4/1/16.
 * to POST the h.264/avc annexb frame over RTMP.
 * @see android.media.MediaMuxer https://developer.android.com/reference/android/media/MediaMuxer.html
 */
class SrsFlvMuxer(handler: RtmpHandler?) {
    @Volatile
    private var started = false
    private val publisher: DefaultRtmpPublisher?
    private var worker: Thread? = null
    private val txFrameLock = Any()
    private val flv = SrsFlv()
    private var needToFindKeyFrame = true
    private var mVideoSequenceHeader: SrsFlvFrame? = null
    private var mAudioSequenceHeader: SrsFlvFrame? = null
    private val mVideoAllocator = SrsAllocator(VIDEO_ALLOC_SIZE)
    private val mAudioAllocator = SrsAllocator(AUDIO_ALLOC_SIZE)
    private val mFlvTagCache = ConcurrentLinkedQueue<SrsFlvFrame>()

    /**
     * get cached video frame number in publisher
     */
    val videoFrameCacheNumber: AtomicInteger?
        get() = if (publisher == null) null else publisher.videoFrameCacheNumber

    /**
     * set video resolution for publisher
     * @param width width
     * @param height height
     */
    fun setVideoResolution(width: Int, height: Int) {
        publisher?.setVideoResolution(width, height)
    }

    /**
     * Adds a track with the specified format.
     * @param format The media format for the track.
     * @return The track index for this newly added track.
     */
    fun addTrack(format: MediaFormat): Int {
        if (format.getString(MediaFormat.KEY_MIME).contentEquals(SrsEncoder.Companion.VCODEC)) {
            flv.setVideoTrack(format)
            return VIDEO_TRACK
        } else {
            flv.setAudioTrack(format)
            return AUDIO_TRACK
        }
    }

    private fun disconnect() {
        try {
            publisher!!.close()
        } catch (e: IllegalStateException) {
            // Ignore illegal state.
        }
        mVideoSequenceHeader = null
        mAudioSequenceHeader = null
        Log.i(TAG, "worker: disconnect ok.")
    }

    private fun connect(url: String): Boolean {
        var connected = false
        Log.i(TAG, String.format("worker: connecting to RTMP server by url=%s\n", url))
        if (publisher!!.connect(url)) {
            connected = publisher.publish("live")
        }
        mVideoSequenceHeader = null
        mAudioSequenceHeader = null
        return connected
    }

    private fun sendFlvTag(frame: SrsFlvFrame?) {
        if (frame == null) {
            return
        }
        if (frame.isVideo) {
            if (frame.isKeyFrame) {
                Log.i(TAG, String.format("worker: send frame type=%d, dts=%d, size=%dB",
                        frame.type, frame.dts, frame.flvTag!!.array().size))
            }
            publisher!!.publishVideoData(frame.flvTag!!.array(), frame.flvTag!!.size(), frame.dts)
            mVideoAllocator.release(frame.flvTag)
        } else if (frame.isAudio) {
            publisher!!.publishAudioData(frame.flvTag!!.array(), frame.flvTag!!.size(), frame.dts)
            mAudioAllocator.release(frame.flvTag)
        }
    }

    /**
     * start to the remote server for remux.
     */
    fun start(rtmpUrl: String) {
        started = true
        worker = Thread(Runnable {
            if (!connect(rtmpUrl)) {
                return@Runnable
            }
            while (!Thread.interrupted()) {
                while (!mFlvTagCache.isEmpty()) {
                    val frame = mFlvTagCache.poll()
                    if (frame.isSequenceHeader) {
                        if (frame.isVideo) {
                            mVideoSequenceHeader = frame
                            sendFlvTag(mVideoSequenceHeader)
                        } else if (frame.isAudio) {
                            mAudioSequenceHeader = frame
                            sendFlvTag(mAudioSequenceHeader)
                        }
                    } else {
                        if (frame.isVideo && mVideoSequenceHeader != null) {
                            sendFlvTag(frame)
                        } else if (frame.isAudio && mAudioSequenceHeader != null) {
                            sendFlvTag(frame)
                        }
                    }
                }
                // Waiting for next frame
                synchronized(txFrameLock) {
                    try {
                        // isEmpty() may take some time, so we set timeout to detect next frame
                        txFrameLock.wait(500)
                    } catch (ie: InterruptedException) {
                        worker!!.interrupt()
                    }
                }
            }
        })
        worker!!.start()
    }

    /**
     * stop the muxer, disconnect RTMP connection.
     */
    fun stop() {
        started = false
        mFlvTagCache.clear()
        if (worker != null) {
            worker!!.interrupt()
            try {
                worker!!.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                worker!!.interrupt()
            }
            worker = null
        }
        flv.reset()
        needToFindKeyFrame = true
        Log.i(TAG, "SrsFlvMuxer closed")
        // We should not block the main thread
        Thread(object : Runnable {
            override fun run() {
                disconnect()
            }
        }).start()
    }

    /**
     * send the annexb frame over RTMP.
     * @param trackIndex The track index for this sample.
     * @param byteBuf The encoded sample.
     * @param bufferInfo The buffer information related to this sample.
     */
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (bufferInfo.offset > 0) {
            Log.w(TAG, String.format("encoded frame %dB, offset=%d pts=%dms",
                    bufferInfo.size, bufferInfo.offset, bufferInfo.presentationTimeUs / 1000
            ))
        }
        if (VIDEO_TRACK == trackIndex) {
            flv.writeVideoSample(byteBuf, bufferInfo)
        } else {
            flv.writeAudioSample(byteBuf, bufferInfo)
        }
    }

    // E.4.3.1 VIDEODATA
    // Frame Type UB [4]
    // Type of video frame. The following values are defined:
    //     1 = key frame (for AVC, a seekable frame)
    //     2 = inter frame (for AVC, a non-seekable frame)
    //     3 = disposable inter frame (H.263 only)
    //     4 = generated key frame (reserved for server use only)
    //     5 = video info/command frame
    private object SrsCodecVideoAVCFrame {
        // set to the zero to reserved, for array map.
        val Reserved = 0
        val Reserved1 = 6
        val KeyFrame = 1
        val InterFrame = 2
        val DisposableInterFrame = 3
        val GeneratedKeyFrame = 4
        val VideoInfoFrame = 5
    }

    // AVCPacketType IF CodecID == 7 UI8
    // The following values are defined:
    //     0 = AVC sequence header
    //     1 = AVC NALU
    //     2 = AVC end of sequence (lower level NALU sequence ender is
    //         not required or supported)
    private object SrsCodecVideoAVCType {
        // set to the max value to reserved, for array map.
        val Reserved = 3
        val SequenceHeader = 0
        val NALU = 1
        val SequenceHeaderEOF = 2
    }

    /**
     * E.4.1 FLV Tag, page 75
     */
    private object SrsCodecFlvTag {
        // set to the zero to reserved, for array map.
        val Reserved = 0

        // 8 = audio
        val Audio = 8

        // 9 = video
        val Video = 9

        // 18 = script data
        val Script = 18
    }

    // E.4.3.1 VIDEODATA
    // CodecID UB [4]
    // Codec Identifier. The following values are defined:
    //     2 = Sorenson H.263
    //     3 = Screen video
    //     4 = On2 VP6
    //     5 = On2 VP6 with alpha channel
    //     6 = Screen video version 2
    //     7 = AVC
    private object SrsCodecVideo {
        // set to the zero to reserved, for array map.
        val Reserved = 0
        val Reserved1 = 1
        val Reserved2 = 9

        // for user to disable video, for example, use pure audio hls.
        val Disabled = 8
        val SorensonH263 = 2
        val ScreenVideo = 3
        val On2VP6 = 4
        val On2VP6WithAlphaChannel = 5
        val ScreenVideoVersion2 = 6
        val AVC = 7
    }

    /**
     * the aac object type, for RTMP sequence header
     * for AudioSpecificConfig, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 33
     * for audioObjectType, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 23
     */
    private object SrsAacObjectType {
        val Reserved = 0

        // Table 1.1 – Audio Object Type definition
        // @see @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 23
        val AacMain = 1
        val AacLC = 2
        val AacSSR = 3

        // AAC HE = LC+SBR
        val AacHE = 5

        // AAC HEv2 = LC+SBR+PS
        val AacHEV2 = 29
    }

    /**
     * the aac profile, for ADTS(HLS/TS)
     * @see https://github.com/simple-rtmp-server/srs/issues/310
     */
    private object SrsAacProfile {
        val Reserved = 3

        // @see 7.1 Profiles, aac-iso-13818-7.pdf, page 40
        val Main = 0
        val LC = 1
        val SSR = 2
    }

    /**
     * the FLV/RTMP supported audio sample rate.
     * Sampling rate. The following values are defined:
     * 0 = 5.5 kHz = 5512 Hz
     * 1 = 11 kHz = 11025 Hz
     * 2 = 22 kHz = 22050 Hz
     * 3 = 44 kHz = 44100 Hz
     */
    private object SrsCodecAudioSampleRate {
        val R5512 = 5512
        val R11025 = 11025
        val R22050 = 22050
        val R44100 = 44100
        val R32000 = 32000
        val R16000 = 16000
    }

    /**
     * Table 7-1 – NAL unit type codes, syntax element categories, and NAL unit type classes
     * H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 83.
     */
    private object SrsAvcNaluType {
        // Unspecified
        val Reserved = 0

        // Coded slice of a non-IDR picture slice_layer_without_partitioning_rbsp( )
        val NonIDR = 1

        // Coded slice data partition A slice_data_partition_a_layer_rbsp( )
        val DataPartitionA = 2

        // Coded slice data partition B slice_data_partition_b_layer_rbsp( )
        val DataPartitionB = 3

        // Coded slice data partition C slice_data_partition_c_layer_rbsp( )
        val DataPartitionC = 4

        // Coded slice of an IDR picture slice_layer_without_partitioning_rbsp( )
        val IDR = 5

        // Supplemental enhancement information (SEI) sei_rbsp( )
        val SEI = 6

        // Sequence parameter set seq_parameter_set_rbsp( )
        val SPS = 7

        // Picture parameter set pic_parameter_set_rbsp( )
        val PPS = 8

        // Access unit delimiter access_unit_delimiter_rbsp( )
        val AccessUnitDelimiter = 9

        // End of sequence end_of_seq_rbsp( )
        val EOSequence = 10

        // End of stream end_of_stream_rbsp( )
        val EOStream = 11

        // Filler data filler_data_rbsp( )
        val FilterData = 12

        // Sequence parameter set extension seq_parameter_set_extension_rbsp( )
        val SPSExt = 13

        // Prefix NAL unit prefix_nal_unit_rbsp( )
        val PrefixNALU = 14

        // Subset sequence parameter set subset_seq_parameter_set_rbsp( )
        val SubsetSPS = 15

        // Coded slice of an auxiliary coded picture without partitioning slice_layer_without_partitioning_rbsp( )
        val LayerWithoutPartition = 19

        // Coded slice extension slice_layer_extension_rbsp( )
        val CodedSliceExt = 20
    }

    /**
     * the search result for annexb.
     */
    private inner class SrsAnnexbSearch() {
        var nb_start_code = 0
        var match = false
    }

    /**
     * the demuxed tag frame.
     */
    private inner class SrsFlvFrameBytes() {
        var data: ByteBuffer? = null
        var size = 0
    }

    /**
     * the muxed flv frame.
     */
    private inner class SrsFlvFrame() {
        // the tag bytes.
        var flvTag: SrsAllocator.Allocation? = null

        // the codec type for audio/aac and video/avc for instance.
        var avc_aac_type = 0

        // the frame type, keyframe or not.
        var frame_type = 0

        // the tag type, audio, video or data.
        var type = 0

        // the dts in ms, tbn is 1000.
        var dts = 0
        val isKeyFrame: Boolean
            get() = isVideo && frame_type == SrsCodecVideoAVCFrame.KeyFrame

        val isSequenceHeader: Boolean
            get() = avc_aac_type == 0

        val isVideo: Boolean
            get() = type == SrsCodecFlvTag.Video

        val isAudio: Boolean
            get() = type == SrsCodecFlvTag.Audio
    }

    /**
     * the raw h.264 stream, in annexb.
     */
    private inner class SrsRawH264Stream() {
        private val annexb = SrsAnnexbSearch()
        private val seq_hdr = SrsFlvFrameBytes()
        private val sps_hdr = SrsFlvFrameBytes()
        private val sps_bb = SrsFlvFrameBytes()
        private val pps_hdr = SrsFlvFrameBytes()
        private val pps_bb = SrsFlvFrameBytes()
        fun isSps(frame: SrsFlvFrameBytes): Boolean {
            return frame.size >= 1 && (frame.data!![0] and 0x1f) == SrsAvcNaluType.SPS
        }

        fun isPps(frame: SrsFlvFrameBytes): Boolean {
            return frame.size >= 1 && (frame.data!![0] and 0x1f) == SrsAvcNaluType.PPS
        }

        fun muxNaluHeader(frame: SrsFlvFrameBytes): SrsFlvFrameBytes {
            val nalu_hdr = SrsFlvFrameBytes()
            nalu_hdr.data = ByteBuffer.allocate(4)
            nalu_hdr.size = 4
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size
            val NAL_unit_length = frame.size

            // mux the avc NALU in "ISO Base Media File Format"
            // from H.264-AVC-ISO_IEC_14496-15.pdf, page 20
            // NALUnitLength
            nalu_hdr.data.putInt(NAL_unit_length)

            // reset the buffer.
            nalu_hdr.data.rewind()
            return nalu_hdr
        }

        fun muxSequenceHeader(sps: ByteBuffer, pps: ByteBuffer, dts: Int, pts: Int,
                              frames: ArrayList<SrsFlvFrameBytes>) {
            // 5bytes sps/pps header:
            //      configurationVersion, AVCProfileIndication, profile_compatibility,
            //      AVCLevelIndication, lengthSizeMinusOne
            // 3bytes size of sps:
            //      numOfSequenceParameterSets, sequenceParameterSetLength(2B)
            // Nbytes of sps.
            //      sequenceParameterSetNALUnit
            // 3bytes size of pps:
            //      numOfPictureParameterSets, pictureParameterSetLength
            // Nbytes of pps:
            //      pictureParameterSetNALUnit

            // decode the SPS:
            // @see: 7.3.2.1.1, H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 62
            if (seq_hdr.data == null) {
                seq_hdr.data = ByteBuffer.allocate(5)
                seq_hdr.size = 5
            }
            seq_hdr.data!!.rewind()
            // @see: Annex A Profiles and levels, H.264-AVC-ISO_IEC_14496-10.pdf, page 205
            //      Baseline profile profile_idc is 66(0x42).
            //      Main profile profile_idc is 77(0x4d).
            //      Extended profile profile_idc is 88(0x58).
            val profile_idc = sps[1]
            //u_int8_t constraint_set = frame[2];
            val level_idc = sps[3]

            // generate the sps/pps header
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // configurationVersion
            seq_hdr.data!!.put(0x01.toByte())
            // AVCProfileIndication
            seq_hdr.data!!.put(profile_idc)
            // profile_compatibility
            seq_hdr.data!!.put(0x00.toByte())
            // AVCLevelIndication
            seq_hdr.data!!.put(level_idc)
            // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size,
            // so we always set it to 0x03.
            seq_hdr.data!!.put(0x03.toByte())

            // reset the buffer.
            seq_hdr.data!!.rewind()
            frames.add(seq_hdr)

            // sps
            if (sps_hdr.data == null) {
                sps_hdr.data = ByteBuffer.allocate(3)
                sps_hdr.size = 3
            }
            sps_hdr.data!!.rewind()
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // numOfSequenceParameterSets, always 1
            sps_hdr.data!!.put(0x01.toByte())
            // sequenceParameterSetLength
            sps_hdr.data!!.putShort(sps.array().size.toShort())
            sps_hdr.data!!.rewind()
            frames.add(sps_hdr)

            // sequenceParameterSetNALUnit
            sps_bb.size = sps.array().size
            sps_bb.data = sps.duplicate()
            frames.add(sps_bb)

            // pps
            if (pps_hdr.data == null) {
                pps_hdr.data = ByteBuffer.allocate(3)
                pps_hdr.size = 3
            }
            pps_hdr.data!!.rewind()
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // numOfPictureParameterSets, always 1
            pps_hdr.data!!.put(0x01.toByte())
            // pictureParameterSetLength
            pps_hdr.data!!.putShort(pps.array().size.toShort())
            pps_hdr.data!!.rewind()
            frames.add(pps_hdr)

            // pictureParameterSetNALUnit
            pps_bb.size = pps.array().size
            pps_bb.data = pps.duplicate()
            frames.add(pps_bb)
        }

        fun muxFlvTag(frames: ArrayList<SrsFlvFrameBytes>, frame_type: Int,
                      avc_packet_type: Int, dts: Int, pts: Int): SrsAllocator.Allocation? {
            // for h264 in RTMP video payload, there is 5bytes header:
            //      1bytes, FrameType | CodecID
            //      1bytes, AVCPacketType
            //      3bytes, CompositionTime, the cts.
            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            var size = 5
            for (i in frames.indices) {
                size += frames[i].size
            }
            val allocation = mVideoAllocator.allocate(size)

            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            // Frame Type, Type of video frame.
            // CodecID, Codec Identifier.
            // set the rtmp header
            allocation!!.put(((frame_type shl 4) or SrsCodecVideo.AVC).toByte())

            // AVCPacketType
            allocation.put(avc_packet_type.toByte())

            // CompositionTime
            // pts = dts + cts, or
            // cts = pts - dts.
            // where cts is the header in rtmp video packet payload header.
            val cts = pts - dts
            allocation.put((cts shr 16).toByte())
            allocation.put((cts shr 8).toByte())
            allocation.put(cts.toByte())

            // h.264 raw data.
            for (i in frames.indices) {
                val frame = frames[i]
                frame.data!![allocation.array(), allocation.size(), frame.size]
                allocation.appendOffset(frame.size)
            }
            return allocation
        }

        private fun searchStartcode(bb: ByteBuffer, bi: MediaCodec.BufferInfo): SrsAnnexbSearch {
            annexb.match = false
            annexb.nb_start_code = 0
            if (bi.size - 4 > 0) {
                if ((bb[0].toInt() == 0x00) && (bb[1].toInt() == 0x00) && (bb[2].toInt() == 0x00) && (bb[3].toInt() == 0x01)) {
                    // match N[00] 00 00 00 01, where N>=0
                    annexb.match = true
                    annexb.nb_start_code = 4
                } else if ((bb[0].toInt() == 0x00) && (bb[1].toInt() == 0x00) && (bb[2].toInt() == 0x01)) {
                    // match N[00] 00 00 01, where N>=0
                    annexb.match = true
                    annexb.nb_start_code = 3
                }
            }
            return annexb
        }

        private fun searchAnnexb(bb: ByteBuffer, bi: MediaCodec.BufferInfo): SrsAnnexbSearch {
            annexb.match = false
            annexb.nb_start_code = 0
            for (i in bb.position() until bi.size - 4) {
                // not match.
                if (bb[i].toInt() != 0x00 || bb[i + 1].toInt() != 0x00) {
                    continue
                }
                // match N[00] 00 00 01, where N>=0
                if (bb[i + 2].toInt() == 0x01) {
                    annexb.match = true
                    annexb.nb_start_code = i + 3 - bb.position()
                    break
                }
                // match N[00] 00 00 00 01, where N>=0
                if (bb[i + 2].toInt() == 0x00 && bb[i + 3].toInt() == 0x01) {
                    annexb.match = true
                    annexb.nb_start_code = i + 4 - bb.position()
                    break
                }
            }
            return annexb
        }

        fun demuxAnnexb(bb: ByteBuffer, bi: MediaCodec.BufferInfo, isOnlyChkHeader: Boolean): SrsFlvFrameBytes {
            val tbb = SrsFlvFrameBytes()
            if (bb.position() < bi.size - 4) {
                // each frame must prefixed by annexb format.
                // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
                val tbbsc = if (isOnlyChkHeader) searchStartcode(bb, bi) else searchAnnexb(bb, bi)
                // tbbsc.nb_start_code always 4 , after 00 00 00 01
                if (!tbbsc.match || tbbsc.nb_start_code < 3) {
                    Log.e(Companion.TAG, "annexb not match.")
                } else {
                    // the start codes.
                    for (i in 0 until tbbsc.nb_start_code) {
                        bb.get()
                    }

                    // find out the frame size.
                    tbb.data = bb.slice()
                    tbb.size = bi.size - bb.position()
                }
            }
            return tbb
        }

        companion object {
            private val TAG = "SrsFlvMuxer"
        }
    }

    private inner class SrsRawAacStreamCodec() {
        var protection_absent: Byte = 0

        // SrsAacObjectType
        var aac_object = 0
        var sampling_frequency_index: Byte = 0
        var channel_configuration: Byte = 0
        var frame_length: Short = 0
        var sound_format: Byte = 0
        var sound_rate: Byte = 0
        var sound_size: Byte = 0
        var sound_type: Byte = 0

        // 0 for sh; 1 for raw data.
        var aac_packet_type: Byte = 0
        var frame: ByteArray
    }

    /**
     * remux the annexb to flv tags.
     */
    private inner class SrsFlv() {
        private var videoTrack: MediaFormat? = null
        private var audioTrack: MediaFormat? = null
        private var achannel = 0
        private var asample_rate = 0
        private val avc = SrsRawH264Stream()
        private val ipbs = ArrayList<SrsFlvFrameBytes>()
        private var audio_tag: SrsAllocator.Allocation? = null
        private var video_tag: SrsAllocator.Allocation? = null
        private var h264_sps: ByteBuffer? = null
        private var h264_sps_changed = false
        private var h264_pps: ByteBuffer? = null
        private var h264_pps_changed = false
        private var h264_sps_pps_sent = false
        private var aac_specific_config_got = false
        fun reset() {
            h264_sps_changed = false
            h264_pps_changed = false
            h264_sps_pps_sent = false
            aac_specific_config_got = false
            if (null != h264_sps) {
                Arrays.fill(h264_sps!!.array(), 0x00.toByte())
                h264_sps!!.clear()
            }
            if (null != h264_pps) {
                Arrays.fill(h264_pps!!.array(), 0x00.toByte())
                h264_pps!!.clear()
            }
        }

        fun setVideoTrack(format: MediaFormat?) {
            videoTrack = format
        }

        fun setAudioTrack(format: MediaFormat) {
            audioTrack = format
            achannel = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            asample_rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        }

        fun writeAudioSample(bb: ByteBuffer, bi: MediaCodec.BufferInfo) {
            val pts = (bi.presentationTimeUs / 1000).toInt()
            val dts = pts
            audio_tag = mAudioAllocator.allocate(bi.size + 2)
            var aac_packet_type: Byte = 1 // 1 = AAC raw
            if (!aac_specific_config_got) {
                // @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf
                // AudioSpecificConfig (), page 33
                // 1.6.2.1 AudioSpecificConfig
                // audioObjectType; 5 bslbf
                var ch = (bb[0] and 0xf8) as Byte
                // 3bits left.

                // samplingFrequencyIndex; 4 bslbf
                var samplingFrequencyIndex: Byte = 0x04
                if (asample_rate == SrsCodecAudioSampleRate.R22050) {
                    samplingFrequencyIndex = 0x07
                } else if (asample_rate == SrsCodecAudioSampleRate.R11025) {
                    samplingFrequencyIndex = 0x0a
                } else if (asample_rate == SrsCodecAudioSampleRate.R32000) {
                    samplingFrequencyIndex = 0x05
                } else if (asample_rate == SrsCodecAudioSampleRate.R16000) {
                    samplingFrequencyIndex = 0x08
                }
                ch = ch or ((samplingFrequencyIndex shr 1) and 0x07)
                audio_tag!!.put(ch, 2)
                ch = ((samplingFrequencyIndex shl 7) and 0x80)
                // 7bits left.

                // channelConfiguration; 4 bslbf
                var channelConfiguration: Byte = 1
                if (achannel == 2) {
                    channelConfiguration = 2
                }
                ch = ch or ((channelConfiguration shl 3) and 0x78)
                // 3bits left.

                // GASpecificConfig(), page 451
                // 4.4.1 Decoder configuration (GASpecificConfig)
                // frameLengthFlag; 1 bslbf
                // dependsOnCoreCoder; 1 bslbf
                // extensionFlag; 1 bslbf
                audio_tag!!.put(ch, 3)
                aac_specific_config_got = true
                aac_packet_type = 0 // 0 = AAC sequence header
                writeAdtsHeader(audio_tag!!.array(), 4)
                audio_tag!!.appendOffset(7)
            } else {
                bb[audio_tag!!.array(), 2, bi.size]
                audio_tag!!.appendOffset(bi.size + 2)
            }
            val sound_format: Byte = 10 // AAC
            var sound_type: Byte = 0 // 0 = Mono sound
            if (achannel == 2) {
                sound_type = 1 // 1 = Stereo sound
            }
            val sound_size: Byte = 1 // 1 = 16-bit samples
            var sound_rate: Byte = 3 // 44100, 22050, 11025, 5512
            if (asample_rate == 22050) {
                sound_rate = 2
            } else if (asample_rate == 11025) {
                sound_rate = 1
            } else if (asample_rate == 5512) {
                sound_rate = 0
            }

            // for audio frame, there is 1 or 2 bytes header:
            //      1bytes, SoundFormat|SoundRate|SoundSize|SoundType
            //      1bytes, AACPacketType for SoundFormat == 10, 0 is sequence header.
            var audio_header = (sound_type and 0x01) as Byte
            audio_header = audio_header or ((sound_size shl 1) and 0x02)
            audio_header = audio_header or ((sound_rate shl 2) and 0x0c)
            audio_header = audio_header or ((sound_format shl 4) and 0xf0)
            audio_tag!!.put(audio_header, 0)
            audio_tag!!.put(aac_packet_type, 1)
            writeRtmpPacket(SrsCodecFlvTag.Audio, dts, 0, aac_packet_type.toInt(), audio_tag)
        }

        private fun writeAdtsHeader(frame: ByteArray?, offset: Int) {
            // adts sync word 0xfff (12-bit)
            frame!![offset] = 0xff.toByte()
            frame[offset + 1] = 0xf0.toByte()
            // versioin 0 for MPEG-4, 1 for MPEG-2 (1-bit)
            frame[offset + 1] = frame[offset + 1] or (0 shl 3)
            // layer 0 (2-bit)
            frame[offset + 1] = frame[offset + 1] or (0 shl 1)
            // protection absent: 1 (1-bit)
            frame[offset + 1] = frame[offset + 1] or 1
            // profile: audio_object_type - 1 (2-bit)
            frame[offset + 2] = ((SrsAacObjectType.AacLC - 1) shl 6).toByte()
            // sampling frequency index: 4 (4-bit)
            frame[offset + 2] = (frame[offset + 2] or ((4 and 0xf) shl 2)).toByte()
            // channel configuration (3-bit)
            frame[offset + 2] = (frame[offset + 2] or ((2 and 0x4.toByte()) shr 2)).toByte()
            frame[offset + 3] = ((2 and 0x03.toByte()) shl 6).toByte()
            // original: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 5)
            // home: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 4)
            // copyright id bit: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 3)
            // copyright id start: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 2)
            // frame size (13-bit)
            frame[offset + 3] = frame[offset + 3] or (((frame.size - 2) and 0x1800) shr 11)
            frame[offset + 4] = (((frame.size - 2) and 0x7f8) shr 3).toByte()
            frame[offset + 5] = (((frame.size - 2) and 0x7) shl 5).toByte()
            // buffer fullness (0x7ff for variable bitrate)
            frame[offset + 5] = frame[offset + 5] or 0x1f.toByte()
            frame[offset + 6] = 0xfc.toByte()
            // number of data block (nb - 1)
            frame[offset + 6] = frame[offset + 6] or 0x0
        }

        fun writeVideoSample(bb: ByteBuffer, bi: MediaCodec.BufferInfo) {
            if (bi.size < 4) return
            val pts = (bi.presentationTimeUs / 1000).toInt()
            val dts = pts
            var type = SrsCodecVideoAVCFrame.InterFrame
            val frame = avc.demuxAnnexb(bb, bi, true)
            val nal_unit_type: Int = frame.data!![0] and 0x1f
            if (nal_unit_type == SrsAvcNaluType.IDR) {
                type = SrsCodecVideoAVCFrame.KeyFrame
            } else if (nal_unit_type == SrsAvcNaluType.SPS || nal_unit_type == SrsAvcNaluType.PPS) {
                val frame_pps = avc.demuxAnnexb(bb, bi, false)
                frame.size = frame.size - frame_pps.size - 4 // 4 ---> 00 00 00 01 pps
                if (frame.data != h264_sps) {
                    val sps = ByteArray(frame.size)
                    frame.data!![sps]
                    h264_sps_changed = true
                    h264_sps = ByteBuffer.wrap(sps)
                    //                    writeH264SpsPps(dts, pts);
                }
                val frame_sei = avc.demuxAnnexb(bb, bi, false)
                if (frame_sei.size > 0) {
                    if (SrsAvcNaluType.SEI == (frame_sei.data!![0] and 0x1f) as Int) frame_pps.size = frame_pps.size - frame_sei.size - 3 // 3 ---> 00 00 01 SEI
                }
                if (frame_pps.data != h264_pps) {
                    val pps = ByteArray(frame_pps.size)
                    frame_pps.data!![pps]
                    h264_pps_changed = true
                    h264_pps = ByteBuffer.wrap(pps)
                    writeH264SpsPps(dts, pts)
                }
                return
            } else if (nal_unit_type != SrsAvcNaluType.NonIDR) {
                return
            }
            ipbs.add(avc.muxNaluHeader(frame))
            ipbs.add(frame)

            //writeH264SpsPps(dts, pts);
            writeH264IpbFrame(ipbs, type, dts, pts)
            ipbs.clear()
        }

        private fun writeH264SpsPps(dts: Int, pts: Int) {
            // when sps or pps changed, update the sequence header,
            // for the pps maybe not changed while sps changed.
            // so, we must check when each video ts message frame parsed.
            if (h264_sps_pps_sent && !h264_sps_changed && !h264_pps_changed) {
                return
            }

            // when not got sps/pps, wait.
            if (h264_pps == null || h264_sps == null) {
                return
            }

            // h264 raw to h264 packet.
            val frames = ArrayList<SrsFlvFrameBytes>()
            avc.muxSequenceHeader(h264_sps!!, h264_pps!!, dts, pts, frames)

            // h264 packet to flv packet.
            val frame_type = SrsCodecVideoAVCFrame.KeyFrame
            val avc_packet_type = SrsCodecVideoAVCType.SequenceHeader
            video_tag = avc.muxFlvTag(frames, frame_type, avc_packet_type, dts, pts)

            // the timestamp in rtmp message header is dts.
            writeRtmpPacket(SrsCodecFlvTag.Video, dts, frame_type, avc_packet_type, video_tag)

            // reset sps and pps.
            h264_sps_changed = false
            h264_pps_changed = false
            h264_sps_pps_sent = true
            Log.i(TAG, String.format("flv: h264 sps/pps sent, sps=%dB, pps=%dB",
                    h264_sps!!.array().size, h264_pps!!.array().size))
        }

        private fun writeH264IpbFrame(frames: ArrayList<SrsFlvFrameBytes>, type: Int, dts: Int, pts: Int) {
            // when sps or pps not sent, ignore the packet.
            // @see https://github.com/simple-rtmp-server/srs/issues/203
            if (!h264_sps_pps_sent) {
                return
            }
            video_tag = avc.muxFlvTag(frames, type, SrsCodecVideoAVCType.NALU, dts, pts)

            // the timestamp in rtmp message header is dts.
            writeRtmpPacket(SrsCodecFlvTag.Video, dts, type, SrsCodecVideoAVCType.NALU, video_tag)
        }

        private fun writeRtmpPacket(type: Int, dts: Int, frame_type: Int, avc_aac_type: Int, tag: SrsAllocator.Allocation?) {
            val frame = SrsFlvFrame()
            frame.flvTag = tag
            frame.type = type
            frame.dts = dts
            frame.frame_type = frame_type
            frame.avc_aac_type = avc_aac_type
            if (frame.isVideo) {
                if (needToFindKeyFrame) {
                    if (frame.isKeyFrame) {
                        needToFindKeyFrame = false
                        flvTagCacheAdd(frame)
                    }
                } else {
                    flvTagCacheAdd(frame)
                }
            } else if (frame.isAudio) {
                flvTagCacheAdd(frame)
            }
        }

        private fun flvTagCacheAdd(frame: SrsFlvFrame) {
            if (started) {
                mFlvTagCache.add(frame)
                if (frame.isVideo) {
                    videoFrameCacheNumber!!.incrementAndGet()
                }
            }
            synchronized(txFrameLock) { txFrameLock.notifyAll() }
        }

        init {
            reset()
        }
    }

    companion object {
        private val VIDEO_ALLOC_SIZE = 128 * 1024
        private val AUDIO_ALLOC_SIZE = 4 * 1024
        private val VIDEO_TRACK = 100
        private val AUDIO_TRACK = 101
        private val TAG = "SrsFlvMuxer"
    }

    /**
     * constructor.
     * @param handler the rtmp event handler.
     */
    init {
        publisher = DefaultRtmpPublisher(handler)
    }
}