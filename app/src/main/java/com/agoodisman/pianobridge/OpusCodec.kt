package com.agoodisman.pianobridge

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder


class OpusCodec(sampleRate: Int) {

    private val encoder = OpusEncoder(sampleRate, CHANNEL_COUNT, OpusApplication.OPUS_APPLICATION_AUDIO)
    private val decoder = OpusDecoder(sampleRate, CHANNEL_COUNT)
    private val codecSampleRate = sampleRate
    private val frameSize = computeFrameSize(sampleRate)


    init {
        // additional encoding parameters
        encoder.complexity = 10
        encoder.bitrate = 128000
    }

    // convert pcm -> opus for sending to discord
    fun encode(buffer: ShortArray): ByteArray {
        synchronized(encoder) {
            // we don't know a priori what size buffer we need for the opus data
            // however, if we didn't do any compression at all, we would go from shorts to bytes so it would grow by a factor of 2
            // It seems deeply unlikely it will reach that size, but just to be safe, double it again to 4x. It's all going out of scope in a second anyway.
            val opusDataStorage = ByteArray(buffer.size * 4)

            // write into the buffer and count how much we actually wrote
            val written = encoder.encode(buffer, 0, frameSize, opusDataStorage, 0, opusDataStorage.size)

            // make a second, smaller buffer of the actual size, and copy into that for returning
            val opusFrame = ByteArray(written)
            return opusDataStorage.copyInto(opusFrame, 0, 0, written)
        }
    }

    // convert opus to pcm for playing
    fun decode(data: ByteArray): ShortArray {
        synchronized(decoder) {
            // we don't know a priori what size buffer we need for the PCM data.
            // but we expect it to be 1 opus frame's worth of opus data, so we double that just to be safe
            val pcmDataStorage = ShortArray(computeNeededPCMSamples(codecSampleRate) * 2)

            // write into the buffer and count how much we actually wrote
            val writtenPerChannel = decoder.decode(data, 0, data.size, pcmDataStorage, 0, frameSize, false)

            // make a second smaller buffer of the actual size, and copy into that for returning
            val pcmFrame = ShortArray(writtenPerChannel * 2) // stereo
            return pcmDataStorage.copyInto(pcmFrame, 0, 0, writtenPerChannel * 2)
        }
    }

    companion object {
        // Stereo
        const val CHANNEL_COUNT = 2
        // Discord wants and provides 20 ms of audio at a time, so that's the frame size we will use
        private const val FRAME_DURATION_MS = 20
        // get the frame size corresponding to a given sample rate
        private fun computeFrameSize(sampleRate: Int) : Int {
            // samples/s * frame ms / (1000 ms/s) = samples per frame
            return sampleRate * FRAME_DURATION_MS / 1000;
        }
        // get the number of pcm samples needed to supply one Opus frame at the given parameters
        fun computeNeededPCMSamples(sampleRate: Int) : Int {
            // Opus Codec measures in samples-per-channel, so to get the total buffer size, multiply by channel count
            return CHANNEL_COUNT * computeFrameSize(sampleRate)
        }
    }
}