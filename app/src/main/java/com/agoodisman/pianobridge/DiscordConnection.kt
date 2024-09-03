package com.agoodisman.pianobridge

import android.util.Log
import dev.kord.common.annotation.KordVoice
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.connect
import dev.kord.core.entity.channel.VoiceChannel
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.on
import dev.kord.voice.AudioFrame
import dev.kord.voice.VoiceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(KordVoice::class)
class DiscordConnection private constructor(delegate: Kord, sampleRate: Int) {

    private val conn = delegate
    private val vcMap = mutableMapOf<String, VoiceChannel>()
    private var voiceConn: VoiceConnection? = null

    private val codec = OpusCodec(sampleRate)
    private val codecSampleRate = sampleRate

    // populate the map of accessible voice channels
    suspend fun getVoiceChannels(): MutableMap<String, VoiceChannel> {
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

    // start sending data up to discord
    // every time it asks for an opus frame, fetch PCM data from the uplink, convert it, and give it to discord
    suspend fun startUplink(vcStr: String, getUplinkData: (arr: ShortArray, numSamples: Int) -> Unit): Boolean {
        val channel = vcMap[vcStr]
        if (channel == null) {
            Log.e(DISCORD_TAG, "Tried to connect to a voice channel that doesn't exist $vcStr")
            return false
        }

        try {
            // we want to send one opus frame, so get what size buffer we need to support that
            val buffer = ShortArray(OpusCodec.computeNeededPCMSamples(codecSampleRate))
            voiceConn = channel.connect {
                receiveVoice = true
                audioProvider {
                    getUplinkData(buffer, buffer.size)
                    val d = codec.encode(buffer)

                    AudioFrame.fromData(d)
                }
            }
        } catch (e: Exception) {
            Log.e(DISCORD_TAG, "error initializing uplink ${e.message}")
            return false
        }

        return true
    }

    // start playing discord voice data
    // continuously aggregate incoming opus packets, convert them to pcm, and send them to the downlink
    suspend fun startDownlink(provideDownlinkData: (arr: ShortArray, numSamples: Int) -> Unit) {
        voiceConn!!.streams.incomingAudioFrames.collect {
            val frame = it.second
            val data = codec.decode(frame.data)
            provideDownlinkData(data, data.size)
        }
    }

    // shutdown
    suspend fun stopLinks() {
        if (voiceConn != null) {
            voiceConn!!.shutdown()
        }
    }


    companion object {
        private const val DISCORD_TAG = "pianobridge-discord"
        // suspending factory method
        // this has to take a coroutineScope parameter because the login will block indefinitely
        // and so we want to start that in a separate coroutine and not block the calling one.
        // invoking the coroutineScope { } function will block, and this way it's a receiver on the
        // companion object instead of having to attach to the coroutineScope itself.
        suspend fun connectToDiscord(coroutineScope: CoroutineScope, token: String, sampleRate: Int): DiscordConnection? {
            return try {
                // connect
                val delegate = Kord(token)

                // start login process async
                coroutineScope.launch {
                    delegate.login {
                        presence { playing("piano") }
                    }
                }

                // wait for on ready
                suspendCoroutine {
                    delegate.on<ReadyEvent> {
                        it.resume(Unit)
                    }
                }

                DiscordConnection(delegate, sampleRate)
            } catch (e: Exception) {
                Log.e(DISCORD_TAG, "error establishing discord connection ${e.message}")
                null
            }
        }
    }
}