package com.agoodisman.pianobridge

import net.dv8tion.jda.api.audio.OpusPacket
import net.dv8tion.jda.api.audio.factory.IAudioSendSystem
import net.dv8tion.jda.api.audio.factory.IPacketProvider
import net.dv8tion.jda.internal.audio.AudioConnection
import net.dv8tion.jda.internal.utils.JDALogger
import org.slf4j.MDC
import java.net.NoRouteToHostException
import java.net.SocketException
import java.util.concurrent.ConcurrentMap

// This is a copy-paste of DefaultSendSystem from JDA, with very slight changes
// 1. it's Kotlin, obviously. This was done automatically in android studio.
// 2. Thread priority is set using android process thread priority, as it is an audio thread.
// This is done from inside the thread.
// 3. Fix a bug with the sleep time calculation being wrong(!)
class AndroidSendSystem(private val packetProvider: IPacketProvider) :
    IAudioSendSystem {
    private var sendThread: Thread? = null
    private var contextMap: ConcurrentMap<String?, String?>? = null

    override fun setContextMap(contextMap: ConcurrentMap<String?, String?>?) {
        this.contextMap = contextMap
    }

    override fun start() {
        val udpSocket = packetProvider.udpSocket

        sendThread = Thread {
            if (contextMap != null) {
                MDC.setContextMap(contextMap)
            }
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            var lastFrameSent = System.currentTimeMillis()
            var sentPacket = true
            while (!udpSocket.isClosed && !sendThread!!.isInterrupted) {
                try {
                    val changeTalking =
                        !sentPacket || (System.currentTimeMillis() - lastFrameSent) > OpusPacket.OPUS_FRAME_TIME_AMOUNT
                    val packet = packetProvider.getNextPacket(changeTalking)

                    sentPacket = packet != null
                    if (sentPacket) {
                        udpSocket.send(packet)
                    }
                } catch (_: NoRouteToHostException) {
                    packetProvider.onConnectionLost()
                } catch (_: SocketException) {
                    // Most likely the socket has been closed due to the audio connection be closed.
                    // Next iteration will kill loop.
                } catch (e: Exception) {
                    AudioConnection.LOG.error("Error while sending udp audio data", e)
                } finally {
                    val sleepTime =
                        OpusPacket.OPUS_FRAME_TIME_AMOUNT - (System.currentTimeMillis() - lastFrameSent)
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime)
                        } catch (_: InterruptedException) {
                            // We've been asked to stop.
                            Thread.currentThread().interrupt()
                        }
                    }
                    if (System.currentTimeMillis() < lastFrameSent + 60) {
                        // If the sending didn't take longer than 60ms (3 times the time frame)
                        lastFrameSent += OpusPacket.OPUS_FRAME_TIME_AMOUNT.toLong()
                    } else {
                        // else reset lastFrameSent to current time
                        lastFrameSent = System.currentTimeMillis()
                    }
                }
            }
        }
        sendThread!!.uncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { _: Thread?, throwable: Throwable? ->
                JDALogger.getLog(AndroidSendSystem::class.java)
                    .error("Uncaught exception in audio send thread", throwable)
                start()
            }
        sendThread!!.isDaemon = true
        sendThread!!.name = packetProvider.identifier + " Sending Thread"
        sendThread!!.start()
    }

    override fun shutdown() {
        if (sendThread != null) {
            sendThread!!.interrupt()
        }
    }
}