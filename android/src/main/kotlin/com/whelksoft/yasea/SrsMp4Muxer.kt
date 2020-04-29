package com.whelksoft.yasea

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.coremedia.iso.BoxParser
import com.coremedia.iso.IsoFile
import com.coremedia.iso.IsoTypeWriter
import com.coremedia.iso.boxes.*
import com.coremedia.iso.boxes.h264.AvcConfigurationBox
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.AudioSpecificConfig
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.DecoderConfigDescriptor
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.ESDescriptor
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.SLConfigDescriptor
import com.googlecode.mp4parser.util.Math
import com.googlecode.mp4parser.util.Matrix
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Created by LeoMa on 2016/5/21.
 */
class SrsMp4Muxer constructor(private val mHandler: SrsRecordHandler) {
    private var mRecFile: File? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private val avc: SrsRawH264Stream = SrsRawH264Stream()
    private val mp4Movie: Mp4Movie = Mp4Movie()
    private var aacSpecConfig: Boolean = false
    private var h264_sps: ByteBuffer? = null
    private var h264_pps: ByteBuffer? = null
    private val spsList: ArrayList<ByteArray> = ArrayList()
    private val ppsList: ArrayList<ByteArray> = ArrayList()
    private var worker: Thread? = null

    @Volatile
    private var bRecording: Boolean = false

    @Volatile
    private var bPaused: Boolean = false

    @Volatile
    private var needToFindKeyFrame: Boolean = true
    private val writeLock: Any = Any()
    private val frameCache: ConcurrentLinkedQueue<SrsEsFrame> = ConcurrentLinkedQueue()

    companion object {
        private val TAG: String = "SrsMp4Muxer"
        private val VIDEO_TRACK: Int = 100
        private val AUDIO_TRACK: Int = 101
        private val samplingFrequencyIndexMap: MutableMap<Int, Int> = HashMap()

        init {
            samplingFrequencyIndexMap.put(96000, 0x0)
            samplingFrequencyIndexMap.put(88200, 0x1)
            samplingFrequencyIndexMap.put(64000, 0x2)
            samplingFrequencyIndexMap.put(48000, 0x3)
            samplingFrequencyIndexMap.put(44100, 0x4)
            samplingFrequencyIndexMap.put(32000, 0x5)
            samplingFrequencyIndexMap.put(24000, 0x6)
            samplingFrequencyIndexMap.put(22050, 0x7)
            samplingFrequencyIndexMap.put(16000, 0x8)
            samplingFrequencyIndexMap.put(12000, 0x9)
            samplingFrequencyIndexMap.put(11025, 0xa)
            samplingFrequencyIndexMap.put(8000, 0xb)
        }
    }

    /**
     * start recording.
     */
    fun record(outputFile: File?): Boolean {
        if (videoFormat == null && audioFormat == null) {
            return false
        }
        mRecFile = outputFile
        createMovie(mRecFile)
        mHandler.notifyRecordStarted(mRecFile!!.getPath())
        if (!spsList.isEmpty() && !ppsList.isEmpty()) {
            mp4Movie.addTrack(videoFormat, false)
        }
        mp4Movie.addTrack(audioFormat, true)
        worker = Thread(object : Runnable {
            public override fun run() {
                bRecording = true
                while (bRecording) {
                    // Keep at least one audio and video frame in cache to ensure monotonically increasing.
                    while (!frameCache.isEmpty()) {
                        val frame: SrsEsFrame = frameCache.poll()
                        writeSampleData(frame.bb, frame.bi, frame.is_audio())
                    }
                    // Waiting for next frame
                    synchronized(writeLock, {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500)
                        } catch (ie: InterruptedException) {
                            worker!!.interrupt()
                        }
                    })
                }
            }
        })
        worker!!.start()
        return true
    }

    /**
     * pause recording.
     */
    fun pause() {
        if (bRecording) {
            bPaused = true
            mHandler.notifyRecordPause()
        }
    }

    /**
     * resume recording.
     */
    fun resume() {
        if (bRecording) {
            bPaused = false
            needToFindKeyFrame = true
            mHandler.notifyRecordResume()
        }
    }

    /**
     * finish recording.
     */
    fun stop() {
        bRecording = false
        bPaused = false
        needToFindKeyFrame = true
        aacSpecConfig = false
        frameCache.clear()
        if (worker != null) {
            try {
                worker!!.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                worker!!.interrupt()
            }
            worker = null
            finishMovie()
            mHandler.notifyRecordFinished(mRecFile!!.getPath())
        }
        Log.i(TAG, "SrsMp4Muxer closed")
    }

    /**
     * Adds a track with the specified format.
     *
     * @param format The media format for the track.
     * @return The track index for this newly added track.
     */
    fun addTrack(format: MediaFormat): Int {
        if (format.getString(MediaFormat.KEY_MIME).contentEquals(SrsEncoder.Companion.VCODEC)) {
            videoFormat = format
            return VIDEO_TRACK
        } else {
            audioFormat = format
            return AUDIO_TRACK
        }
    }

    /**
     * send the annexb frame to SRS over RTMP.
     *
     * @param trackIndex The track index for this sample.
     * @param byteBuf    The encoded sample.
     * @param bufferInfo The buffer information related to this sample.
     */
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (VIDEO_TRACK == trackIndex) {
            writeVideoSample(byteBuf, bufferInfo)
        } else {
            writeAudioSample(byteBuf, bufferInfo)
        }
    }

    /**
     * Table 7-1 – NAL unit type codes, syntax element categories, and NAL unit type classes
     * H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 83.
     */
    private object SrsAvcNaluType {
        // Unspecified
        val Reserved: Int = 0

        // Coded slice of a non-IDR picture slice_layer_without_partitioning_rbsp( )
        val NonIDR: Int = 1

        // Coded slice data partition A slice_data_partition_a_layer_rbsp( )
        val DataPartitionA: Int = 2

        // Coded slice data partition B slice_data_partition_b_layer_rbsp( )
        val DataPartitionB: Int = 3

        // Coded slice data partition C slice_data_partition_c_layer_rbsp( )
        val DataPartitionC: Int = 4

        // Coded slice of an IDR picture slice_layer_without_partitioning_rbsp( )
        val IDR: Int = 5

        // Supplemental enhancement information (SEI) sei_rbsp( )
        val SEI: Int = 6

        // Sequence parameter set seq_parameter_set_rbsp( )
        val SPS: Int = 7

        // Picture parameter set pic_parameter_set_rbsp( )
        val PPS: Int = 8

        // Access unit delimiter access_unit_delimiter_rbsp( )
        val AccessUnitDelimiter: Int = 9

        // End of sequence end_of_seq_rbsp( )
        val EOSequence: Int = 10

        // End of stream end_of_stream_rbsp( )
        val EOStream: Int = 11

        // Filler data filler_data_rbsp( )
        val FilterData: Int = 12

        // Sequence parameter set extension seq_parameter_set_extension_rbsp( )
        val SPSExt: Int = 13

        // Prefix NAL unit prefix_nal_unit_rbsp( )
        val PrefixNALU: Int = 14

        // Subset sequence parameter set subset_seq_parameter_set_rbsp( )
        val SubsetSPS: Int = 15

        // Coded slice of an auxiliary coded picture without partitioning slice_layer_without_partitioning_rbsp( )
        val LayerWithoutPartition: Int = 19

        // Coded slice extension slice_layer_extension_rbsp( )
        val CodedSliceExt: Int = 20
    }

    private fun writeVideoSample(bb: ByteBuffer, bi: MediaCodec.BufferInfo) {
        val nal_unit_type: Int = bb.get(4) and 0x1f
        if (nal_unit_type == SrsAvcNaluType.IDR || nal_unit_type == SrsAvcNaluType.NonIDR) {
            writeFrameByte(VIDEO_TRACK, bb, bi, nal_unit_type == SrsAvcNaluType.IDR)
        } else {
            while (bb.position() < bi.size) {
                val frame: SrsEsFrameBytes = avc.annexb_demux(bb, bi)
                if (avc.is_sps(frame)) {
                    if (!(frame.data == h264_sps)) {
                        val sps: ByteArray = ByteArray(frame.size)
                        frame.data!!.get(sps)
                        h264_sps = ByteBuffer.wrap(sps)
                        spsList.clear()
                        spsList.add(sps)
                    }
                    continue
                }
                if (avc.is_pps(frame)) {
                    if (!(frame.data == h264_pps)) {
                        val pps: ByteArray = ByteArray(frame.size)
                        frame.data!!.get(pps)
                        h264_pps = ByteBuffer.wrap(pps)
                        ppsList.clear()
                        ppsList.add(pps)
                    }
                    continue
                }
            }
        }
    }

    private fun writeAudioSample(bb: ByteBuffer, bi: MediaCodec.BufferInfo) {
        if (!aacSpecConfig) {
            aacSpecConfig = true
        } else {
            writeFrameByte(AUDIO_TRACK, bb, bi, false)
        }
    }

    private fun writeFrameByte(track: Int, bb: ByteBuffer, bi: MediaCodec.BufferInfo, isKeyFrame: Boolean) {
        val frame: SrsEsFrame = SrsEsFrame()
        frame.bb = bb
        frame.bi = bi
        frame.isKeyFrame = isKeyFrame
        frame.track = track
        if (bRecording && !bPaused) {
            if (needToFindKeyFrame) {
                if (frame.isKeyFrame) {
                    needToFindKeyFrame = false
                    frameCache.add(frame)
                    synchronized(writeLock, { writeLock.notifyAll() })
                }
            } else {
                frameCache.add(frame)
                synchronized(writeLock, { writeLock.notifyAll() })
            }
        }
    }

    /**
     * the search result for annexb.
     */
    private inner class SrsAnnexbSearch constructor() {
        var nb_start_code: Int = 0
        var match: Boolean = false
    }

    /**
     * the demuxed tag frame.
     */
    private inner class SrsEsFrameBytes constructor() {
        var data: ByteBuffer? = null
        var size: Int = 0
    }

    /**
     * the AV frame.
     */
    private inner class SrsEsFrame constructor() {
        var bb: ByteBuffer? = null
        var bi: MediaCodec.BufferInfo? = null
        var track: Int = 0
        var isKeyFrame: Boolean = false
        fun is_video(): Boolean {
            return track == VIDEO_TRACK
        }

        fun is_audio(): Boolean {
            return track == AUDIO_TRACK
        }
    }

    /**
     * the raw h.264 stream, in annexb.
     */
    private inner class SrsRawH264Stream constructor() {
        fun is_sps(frame: SrsEsFrameBytes): Boolean {
            if (frame.size < 1) {
                return false
            }
            return (frame.data!!.get(0) and 0x1f) == SrsAvcNaluType.SPS
        }

        fun is_pps(frame: SrsEsFrameBytes): Boolean {
            if (frame.size < 1) {
                return false
            }
            return (frame.data!!.get(0) and 0x1f) == SrsAvcNaluType.PPS
        }

        fun srs_avc_startswith_annexb(bb: ByteBuffer, bi: MediaCodec.BufferInfo): SrsAnnexbSearch {
            val `as`: SrsAnnexbSearch = SrsAnnexbSearch()
            `as`.match = false
            var pos: Int = bb.position()
            while (pos < bi.size - 3) {
                // not match.
                if (bb.get(pos).toInt() != 0x00 || bb.get(pos + 1).toInt() != 0x00) {
                    break
                }

                // match N[00] 00 00 01, where N>=0
                if (bb.get(pos + 2).toInt() == 0x01) {
                    `as`.match = true
                    `as`.nb_start_code = pos + 3 - bb.position()
                    break
                }
                pos++
            }
            return `as`
        }

        fun annexb_demux(bb: ByteBuffer, bi: MediaCodec.BufferInfo): SrsEsFrameBytes {
            val tbb: SrsEsFrameBytes = SrsEsFrameBytes()
            while (bb.position() < bi.size) {
                // each frame must prefixed by annexb format.
                // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
                val tbbsc: SrsAnnexbSearch = srs_avc_startswith_annexb(bb, bi)
                if (!tbbsc.match || tbbsc.nb_start_code < 3) {
                    Log.e(TAG, "annexb not match.")
                    mHandler.notifyRecordIllegalArgumentException(IllegalArgumentException(String.format("annexb not match for %dB, pos=%d", bi.size, bb.position())))
                }

                // the start codes.
                val tbbs: ByteBuffer = bb.slice()
                for (i in 0 until tbbsc.nb_start_code) {
                    bb.get()
                }

                // find out the frame size.
                tbb.data = bb.slice()
                val pos: Int = bb.position()
                while (bb.position() < bi.size) {
                    val bsc: SrsAnnexbSearch = srs_avc_startswith_annexb(bb, bi)
                    if (bsc.match) {
                        break
                    }
                    bb.get()
                }
                tbb.size = bb.position() - pos
                break
            }
            return tbb
        }
    }

    private inner class Sample constructor(offset: Long, size: Long) {
        val offset: Long = 0
        val size: Long = 0

        init {
            this.offset = offset
            this.size = size
        }
    }

    private inner class Track constructor(id: Int, format: MediaFormat, audio: Boolean) {
        var trackId: Int = 0
        val samples: ArrayList<Sample> = ArrayList()
        var duration: Long = 0
            private set
        var handler: String? = null
        var mediaHeaderBox: AbstractMediaHeaderBox? = null
        var sampleDescriptionBox: SampleDescriptionBox? = null
        private var syncSamples: LinkedList<Int>? = null
        var timeScale: Int = 0
        val creationTime: Date = Date()
        var height: Int = 0
        var width: Int = 0
        var volume: Float = 0f
        val sampleDurations: ArrayList<Long> = ArrayList()
        var isAudio: Boolean = false
        private var lastPresentationTimeUs: Long = 0
        private var first: Boolean = true
        fun addSample(offset: Long, bi: MediaCodec.BufferInfo?) {
            var delta: Long = bi!!.presentationTimeUs - lastPresentationTimeUs
            if (delta < 0) {
                return
            }
            val isSyncFrame: Boolean = !isAudio && (bi.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0
            samples.add(Sample(offset, bi.size.toLong()))
            if (syncSamples != null && isSyncFrame) {
                syncSamples.add(samples.size)
            }
            delta = (delta * timeScale + 500000L) / 1000000L
            lastPresentationTimeUs = bi.presentationTimeUs
            if (!first) {
                sampleDurations.add(sampleDurations.size - 1, delta)
                duration += delta
            }
            first = false
        }

        fun clearSample() {
            first = true
            samples.clear()
            syncSamples!!.clear()
            sampleDurations.clear()
        }

        fun getSyncSamples(): LongArray? {
            if (syncSamples == null || syncSamples.isEmpty()) {
                return null
            }
            val returns: LongArray = LongArray(syncSamples.size)
            for (i in syncSamples.indices) {
                returns.get(i) = syncSamples.get(i).toLong()
            }
            return returns
        }

        init {
            trackId = id
            isAudio = audio
            if (!isAudio) {
                sampleDurations.add(3015.toLong())
                duration = 3015
                width = format.getInteger(MediaFormat.KEY_WIDTH)
                height = format.getInteger(MediaFormat.KEY_HEIGHT)
                timeScale = 90000
                syncSamples = LinkedList()
                handler = "vide"
                mediaHeaderBox = VideoMediaHeaderBox()
                sampleDescriptionBox = SampleDescriptionBox()
                if (format.getString(MediaFormat.KEY_MIME).contentEquals(SrsEncoder.Companion.VCODEC)) {
                    val visualSampleEntry: VisualSampleEntry = VisualSampleEntry("avc1")
                    visualSampleEntry.setDataReferenceIndex(1)
                    visualSampleEntry.setDepth(24)
                    visualSampleEntry.setFrameCount(1)
                    visualSampleEntry.setHorizresolution(72.0)
                    visualSampleEntry.setVertresolution(72.0)
                    visualSampleEntry.setWidth(width)
                    visualSampleEntry.setHeight(height)
                    visualSampleEntry.setCompressorname("AVC Coding")
                    val avcConfigurationBox: AvcConfigurationBox = AvcConfigurationBox()
                    avcConfigurationBox.setConfigurationVersion(1)
                    avcConfigurationBox.setAvcProfileIndication(h264_sps!!.get(1).toInt())
                    avcConfigurationBox.setProfileCompatibility(0)
                    avcConfigurationBox.setAvcLevelIndication(h264_sps!!.get(3).toInt())
                    avcConfigurationBox.setLengthSizeMinusOne(3)
                    avcConfigurationBox.setSequenceParameterSets(spsList)
                    avcConfigurationBox.setPictureParameterSets(ppsList)
                    avcConfigurationBox.setBitDepthLumaMinus8(-1)
                    avcConfigurationBox.setBitDepthChromaMinus8(-1)
                    avcConfigurationBox.setChromaFormat(-1)
                    avcConfigurationBox.setHasExts(false)
                    visualSampleEntry.addBox(avcConfigurationBox)
                    sampleDescriptionBox.addBox(visualSampleEntry)
                }
            } else {
                sampleDurations.add(1024.toLong())
                duration = 1024
                volume = 1f
                timeScale = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                handler = "soun"
                mediaHeaderBox = SoundMediaHeaderBox()
                sampleDescriptionBox = SampleDescriptionBox()
                val audioSampleEntry: AudioSampleEntry = AudioSampleEntry("mp4a")
                audioSampleEntry.setChannelCount(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                audioSampleEntry.setSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toLong())
                audioSampleEntry.setDataReferenceIndex(1)
                audioSampleEntry.setSampleSize(16)
                val esds: ESDescriptorBox = ESDescriptorBox()
                val descriptor: ESDescriptor = ESDescriptor()
                descriptor.setEsId(0)
                val slConfigDescriptor: SLConfigDescriptor = SLConfigDescriptor()
                slConfigDescriptor.setPredefined(2)
                descriptor.setSlConfigDescriptor(slConfigDescriptor)
                val decoderConfigDescriptor: DecoderConfigDescriptor = DecoderConfigDescriptor()
                decoderConfigDescriptor.setObjectTypeIndication(0x40)
                decoderConfigDescriptor.setStreamType(5)
                decoderConfigDescriptor.setBufferSizeDB(1536)
                decoderConfigDescriptor.setMaxBitRate(96000)
                decoderConfigDescriptor.setAvgBitRate(96000)
                val audioSpecificConfig: AudioSpecificConfig = AudioSpecificConfig()
                audioSpecificConfig.setAudioObjectType(2)
                audioSpecificConfig.setSamplingFrequencyIndex((samplingFrequencyIndexMap.get(audioSampleEntry.getSampleRate().toInt()))!!)
                audioSpecificConfig.setChannelConfiguration(audioSampleEntry.getChannelCount())
                decoderConfigDescriptor.setAudioSpecificInfo(audioSpecificConfig)
                descriptor.setDecoderConfigDescriptor(decoderConfigDescriptor)
                val data: ByteBuffer = descriptor.serialize()
                esds.setEsDescriptor(descriptor)
                esds.setData(data)
                audioSampleEntry.addBox(esds)
                sampleDescriptionBox.addBox(audioSampleEntry)
            }
        }
    }

    private inner class Mp4Movie constructor() {
        val matrix: Matrix = Matrix.ROTATE_0
        val tracks: HashMap<Int, Track> = HashMap()

        fun addSample(trackIndex: Int, offset: Long, bi: MediaCodec.BufferInfo?) {
            val track: Track? = tracks.get(trackIndex)
            track!!.addSample(offset, bi)
        }

        fun addTrack(format: MediaFormat?, isAudio: Boolean) {
            if (format != null) {
                if (isAudio) {
                    tracks.put(AUDIO_TRACK, Track(tracks.size, format, true))
                } else {
                    tracks.put(VIDEO_TRACK, Track(tracks.size, format, false))
                }
            }
        }

        fun removeTrack(trackIndex: Int) {
            tracks.remove(trackIndex)
        }
    }

    private inner class InterleaveChunkMdat constructor() : Box {
        val first: Boolean = true
        private var parent: ContainerBox? = null
        private val header: ByteBuffer = ByteBuffer.allocate(16)
        var contentSize: Long = 1024 * 1024 * 1024.toLong()
        public override fun getParent(): ContainerBox {
            return (parent)!!
        }

        public override fun setParent(parent: ContainerBox) {
            this.parent = parent
        }

        public override fun getType(): String {
            return "mdat"
        }

        public override fun getSize(): Long {
            return header.limit() + contentSize
        }

        val headerSize: Int
            get() {
                return header.limit()
            }

        private fun isSmallBox(contentSize: Long): Boolean {
            return (contentSize + header.limit()) < 4294967296L
        }

        public override fun getBox(writableByteChannel: WritableByteChannel) {
            header.rewind()
            val size: Long = getSize()
            if (isSmallBox(size)) {
                IsoTypeWriter.writeUInt32(header, size)
            } else {
                IsoTypeWriter.writeUInt32(header, 1)
            }
            header.put(IsoFile.fourCCtoBytes("mdat"))
            if (isSmallBox(size)) {
                header.put(ByteArray(8))
            } else {
                IsoTypeWriter.writeUInt64(header, size)
            }
            header.rewind()
            try {
                writableByteChannel.write(header)
            } catch (e: IOException) {
                mHandler.notifyRecordIOException(e)
            }
        }

        @Throws(IOException::class)
        public override fun parse(readableByteChannel: ReadableByteChannel, header: ByteBuffer, contentSize: Long, boxParser: BoxParser) {
        }
    }

    private var mdat: InterleaveChunkMdat? = null
    private var fos: FileOutputStream? = null
    private var fc: FileChannel? = null

    @Volatile
    private var recFileSize: Long = 0

    @Volatile
    private var mdatOffset: Long = 0

    @Volatile
    private var flushBytes: Long = 0
    private val track2SampleSizes: HashMap<Track, LongArray> = HashMap()
    private fun createMovie(outputFile: File?) {
        try {
            fos = FileOutputStream(outputFile)
            fc = fos!!.getChannel()
            mdat = InterleaveChunkMdat()
            mdatOffset = 0
            val fileTypeBox: FileTypeBox = createFileTypeBox()
            fileTypeBox.getBox(fc)
            recFileSize += fileTypeBox.getSize()
        } catch (e: IOException) {
            e.printStackTrace()
            mHandler.notifyRecordIOException(e)
        }
    }

    private fun writeSampleData(byteBuf: ByteBuffer?, bi: MediaCodec.BufferInfo?, isAudio: Boolean) {
        val trackIndex: Int = if (isAudio) AUDIO_TRACK else VIDEO_TRACK
        if (!mp4Movie.tracks.containsKey(trackIndex)) {
            return
        }
        try {
            if (mdat!!.first) {
                mdat!!.contentSize = 0
                mdat!!.getBox((fc)!!)
                mdatOffset = recFileSize
                recFileSize += mdat!!.headerSize.toLong()
                mdat!!.first = false
            }
            mp4Movie.addSample(trackIndex, recFileSize, bi)
            byteBuf!!.position(bi!!.offset + (if (isAudio) 0 else 4))
            byteBuf.limit(bi.offset + bi.size)
            if (!isAudio) {
                val size: ByteBuffer = ByteBuffer.allocate(4)
                size.position(0)
                size.putInt(bi.size - 4)
                size.position(0)
                recFileSize += fc!!.write(size).toLong()
            }
            val writeBytes: Int = fc!!.write(byteBuf)
            recFileSize += writeBytes.toLong()
            flushBytes += writeBytes.toLong()
            if (flushBytes > 64 * 1024) {
                fos!!.flush()
                flushBytes = 0
            }
        } catch (e: IOException) {
            e.printStackTrace()
            mHandler.notifyRecordIOException(e)
        }
    }

    private fun finishMovie() {
        try {
            if (flushBytes > 0) {
                fos!!.flush()
                flushBytes = 0
            }
            if (mdat!!.getSize() != 0L) {
                // flush cached mdat box
                val oldPosition: Long = fc!!.position()
                fc!!.position(mdatOffset)
                mdat!!.contentSize = recFileSize - mdat!!.headerSize - mdatOffset
                mdat!!.getBox((fc)!!)
                fc!!.position(oldPosition)
                mdat!!.contentSize = 0
                fos!!.flush()
            }
            for (track: Track in mp4Movie.tracks.values) {
                val samples: List<Sample> = track.samples
                val sizes: LongArray = LongArray(samples.size)
                for (i in sizes.indices) {
                    sizes.get(i) = samples.get(i).size
                }
                track2SampleSizes.put(track, sizes)
            }
            val moov: Box = createMovieBox(mp4Movie)
            moov.getBox(fc)
            fos!!.flush()
            fc!!.close()
            fos!!.close()
            mp4Movie.tracks.clear()
            track2SampleSizes.clear()
            recFileSize = 0
            flushBytes = 0
        } catch (e: IOException) {
            mHandler.notifyRecordIOException(e)
        }
    }

    private fun createFileTypeBox(): FileTypeBox {
        val minorBrands: LinkedList<String> = LinkedList()
        minorBrands.add("isom")
        minorBrands.add("3gp4")
        return FileTypeBox("isom", 0, minorBrands)
    }

    private fun getTimescale(mp4Movie: Mp4Movie): Long {
        var timescale: Long = 0
        if (!mp4Movie.tracks.isEmpty()) {
            timescale = mp4Movie.tracks.values.iterator().next().timeScale.toLong()
        }
        for (track: Track in mp4Movie.tracks.values) {
            timescale = Math.gcd(track.timeScale.toLong(), timescale)
        }
        return timescale
    }

    private fun createMovieBox(movie: Mp4Movie): MovieBox {
        val movieBox: MovieBox = MovieBox()
        val mvhd: MovieHeaderBox = MovieHeaderBox()
        mvhd.setCreationTime(Date())
        mvhd.setModificationTime(Date())
        mvhd.setMatrix(Matrix.ROTATE_0)
        val movieTimeScale: Long = getTimescale(movie)
        var duration: Long = 0
        for (track: Track in movie.tracks.values) {
            val tracksDuration: Long = track.duration * movieTimeScale / track.timeScale
            if (tracksDuration > duration) {
                duration = tracksDuration
            }
        }
        mvhd.setDuration(duration)
        mvhd.setTimescale(movieTimeScale)
        mvhd.setNextTrackId(movie.tracks.size + 1.toLong())
        movieBox.addBox(mvhd)
        for (track: Track in movie.tracks.values) {
            movieBox.addBox(createTrackBox(track, movie))
        }
        return movieBox
    }

    private fun createTrackBox(track: Track, movie: Mp4Movie): TrackBox {
        val trackBox: TrackBox = TrackBox()
        val tkhd: TrackHeaderBox = TrackHeaderBox()
        tkhd.setEnabled(true)
        tkhd.setInMovie(true)
        tkhd.setInPreview(true)
        if (track.isAudio) {
            tkhd.setMatrix(Matrix.ROTATE_0)
        } else {
            tkhd.setMatrix(movie.matrix)
        }
        tkhd.setAlternateGroup(0)
        tkhd.setCreationTime(track.creationTime)
        tkhd.setModificationTime(track.creationTime)
        tkhd.setDuration(track.duration * getTimescale(movie) / track.timeScale)
        tkhd.setHeight(track.height.toDouble())
        tkhd.setWidth(track.width.toDouble())
        tkhd.setLayer(0)
        tkhd.setModificationTime(Date())
        tkhd.setTrackId(track.trackId + 1.toLong())
        tkhd.setVolume(track.volume)
        trackBox.addBox(tkhd)
        val mdia: MediaBox = MediaBox()
        trackBox.addBox(mdia)
        val mdhd: MediaHeaderBox = MediaHeaderBox()
        mdhd.setCreationTime(track.creationTime)
        mdhd.setModificationTime(track.creationTime)
        mdhd.setDuration(track.duration)
        mdhd.setTimescale(track.timeScale.toLong())
        mdhd.setLanguage("eng")
        mdia.addBox(mdhd)
        val hdlr: HandlerBox = HandlerBox()
        hdlr.setName(if (track.isAudio) "SoundHandle" else "VideoHandle")
        hdlr.setHandlerType(track.handler)
        mdia.addBox(hdlr)
        val minf: MediaInformationBox = MediaInformationBox()
        minf.addBox(track.mediaHeaderBox)
        val dinf: DataInformationBox = DataInformationBox()
        val dref: DataReferenceBox = DataReferenceBox()
        dinf.addBox(dref)
        val url: DataEntryUrlBox = DataEntryUrlBox()
        url.setFlags(1)
        dref.addBox(url)
        minf.addBox(dinf)
        val stbl: Box = createStbl(track)
        minf.addBox(stbl)
        mdia.addBox(minf)
        return trackBox
    }

    private fun createStbl(track: Track): Box {
        val stbl: SampleTableBox = SampleTableBox()
        createStsd(track, stbl)
        createStts(track, stbl)
        createStss(track, stbl)
        createStsc(track, stbl)
        createStsz(track, stbl)
        createStco(track, stbl)
        return stbl
    }

    private fun createStsd(track: Track, stbl: SampleTableBox) {
        stbl.addBox(track.sampleDescriptionBox)
    }

    private fun createStts(track: Track, stbl: SampleTableBox) {
        var lastEntry: TimeToSampleBox.Entry? = null
        val entries: MutableList<TimeToSampleBox.Entry> = ArrayList()
        for (delta: Long in track.sampleDurations) {
            if (lastEntry != null && lastEntry.getDelta() == delta) {
                lastEntry.setCount(lastEntry.getCount() + 1)
            } else {
                lastEntry = TimeToSampleBox.Entry(1, delta)
                entries.add(lastEntry)
            }
        }
        val stts: TimeToSampleBox = TimeToSampleBox()
        stts.setEntries(entries)
        stbl.addBox(stts)
    }

    private fun createStss(track: Track, stbl: SampleTableBox) {
        val syncSamples: LongArray? = track.getSyncSamples()
        if (syncSamples != null && syncSamples.size > 0) {
            val stss: SyncSampleBox = SyncSampleBox()
            stss.setSampleNumber(syncSamples)
            stbl.addBox(stss)
        }
    }

    private fun createStsc(track: Track, stbl: SampleTableBox) {
        val stsc: SampleToChunkBox = SampleToChunkBox()
        stsc.setEntries(LinkedList())
        var lastOffset: Long
        var lastChunkNumber: Int = 1
        var lastSampleCount: Int = 0
        var previousWritedChunkCount: Int = -1
        val samplesCount: Int = track.samples.size
        for (a in 0 until samplesCount) {
            val sample: Sample = track.samples.get(a)
            val offset: Long = sample.offset
            val size: Long = sample.size
            lastOffset = offset + size
            lastSampleCount++
            var write: Boolean = false
            if (a != samplesCount - 1) {
                val nextSample: Sample = track.samples.get(a + 1)
                if (lastOffset != nextSample.offset) {
                    write = true
                }
            } else {
                write = true
            }
            if (write) {
                if (previousWritedChunkCount != lastSampleCount) {
                    stsc.getEntries().add(SampleToChunkBox.Entry(lastChunkNumber.toLong(), lastSampleCount.toLong(), 1))
                    previousWritedChunkCount = lastSampleCount
                }
                lastSampleCount = 0
                lastChunkNumber++
            }
        }
        stbl.addBox(stsc)
    }

    private fun createStsz(track: Track, stbl: SampleTableBox) {
        val stsz: SampleSizeBox = SampleSizeBox()
        stsz.setSampleSizes(track2SampleSizes.get(track))
        stbl.addBox(stsz)
    }

    private fun createStco(track: Track, stbl: SampleTableBox) {
        val chunksOffsets: ArrayList<Long> = ArrayList()
        var lastOffset: Long = -1
        for (sample: Sample in track.samples) {
            val offset: Long = sample.offset
            if (lastOffset != -1L && lastOffset != offset) {
                lastOffset = -1
            }
            if (lastOffset == -1L) {
                chunksOffsets.add(offset)
            }
            lastOffset = offset + sample.size
        }
        val chunkOffsetsLong: LongArray = LongArray(chunksOffsets.size)
        for (a in chunksOffsets.indices) {
            chunkOffsetsLong.get(a) = chunksOffsets.get(a)
        }
        val stco: StaticChunkOffsetBox = StaticChunkOffsetBox()
        stco.setChunkOffsets(chunkOffsetsLong)
        stbl.addBox(stco)
    }

}