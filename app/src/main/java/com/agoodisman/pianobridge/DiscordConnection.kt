package com.agoodisman.pianobridge

import android.util.Log
import moe.kyokobot.libdave.NativeDaveFactory
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.audio.AudioReceiveHandler
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.audio.OpusPacket
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.managers.AudioManager
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.nio.ByteBuffer
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.concurrent.timer

class DiscordConnection(delegate: JDA, sampleRate: Int) {

    private val conn = delegate
    private val vcMap = mutableMapOf<String, VoiceChannel>()
    private var voiceConn: AudioManager? = null
    @Volatile
    private var lastDownlinkData = AtomicReference(ConcurrentHashMap<Long, ShortArray>())
    private var downlinkProcessorTimer: Timer? = null

    private val uplinkCodec = OpusCodec(sampleRate)
    private val downlinkCodecs = mutableMapOf<Long, OpusCodec>()
    private val codecSampleRate = sampleRate
    // populate the map of accessible voice channels
    fun getVoiceChannels(): MutableMap<String, VoiceChannel> {
        val guildList = conn.guilds.toList()
        guildList.forEach { g ->
            val channelList = g.channels.toList()
            channelList.forEach {
                if (it is VoiceChannel) {
                    vcMap[g.name + "->" + it.name] = it
                }
            }
        }

        return vcMap
    }

    fun startUplink(vcStr: String, getUplinkData: (arr: ShortArray, numSamples: Int) -> Unit): Boolean {
        val channel = vcMap[vcStr]
        if (channel == null) {
            Log.e(DISCORD_TAG, "Tried to connect to a voice channel that doesn't exist $vcStr")
            return false
        }

        val guild = channel.guild
        voiceConn = guild.audioManager

        try {
            // we want to send one opus frame, so get what size buffer we need to support that
            val size = OpusCodec.computeNeededPCMSamples(codecSampleRate)
            val buffer = ShortArray(size)
            voiceConn!!.sendingHandler = object: AudioSendHandler {
                override fun canProvide(): Boolean {
                    return true
                }

                override fun isOpus(): Boolean {
                    return true
                }

                override fun provide20MsAudio(): ByteBuffer? {
                    getUplinkData(buffer, buffer.size)
                    val d = uplinkCodec.encode(buffer)
                    return ByteBuffer.wrap(d)
                }
            }
            voiceConn!!.openAudioConnection(channel)
        } catch (e: Exception) {
            Log.e(DISCORD_TAG, "error initializing uplink ${e.message}")
            return false
        }

        return true
    }

    fun startDownlink(provideDownlinkData: (arr: ShortArray, numSamples: Int) -> Unit) {
        downlinkProcessorTimer = timer(period = 20) {
            val oldData = lastDownlinkData.getAndSet(ConcurrentHashMap())
            val samples = oldData.values

            val len = samples.maxOfOrNull { it.size } ?: 0
            val data = ShortArray(len)

            for (sample in samples) {
                for (i in sample.indices) {
                    data[i] = (data[i] + sample[i]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            provideDownlinkData(data, len)
        }

        voiceConn!!.receivingHandler = object : AudioReceiveHandler {
            override fun canReceiveEncoded(): Boolean {
                return true
            }

            override fun handleEncodedAudio(packet: OpusPacket) {
                val codec = downlinkCodecs.getOrPut(
                    packet.userId,
                ) { OpusCodec(codecSampleRate) }
                lastDownlinkData.get()[packet.userId] = codec.decode(packet.opusAudio)
            }
        }
    }

    fun stopLinks() {
        if (voiceConn != null) {
            voiceConn!!.closeAudioConnection()
        }
        if (downlinkProcessorTimer != null) {
            downlinkProcessorTimer!!.cancel()
        }
    }

    companion object {
        private const val DISCORD_TAG = "pianobridge-discord"
        fun connectToDiscord(token: String, sampleRate: Int): DiscordConnection? {
            return try {
                // connect
                val daveFactory = NativeDaveFactory()
                val daveSessionFactory = LDJDADaveSessionFactory(daveFactory)
                val delegate = JDABuilder.createLight(token)
                    .enableCache(CacheFlag.VOICE_STATE)
                    .setAudioModuleConfig(
                        AudioModuleConfig()
                            .withDaveSessionFactory(daveSessionFactory)
                            .withAudioSendFactory { p0 -> AndroidSendSystem(p0) }
                    ).build()

                // wait for on ready
                delegate.awaitReady()

                DiscordConnection(delegate, sampleRate)
            } catch (e: Exception) {
                Log.e(DISCORD_TAG, "error establishing discord connection ${e.message}")
                null
            }
        }
    }
}