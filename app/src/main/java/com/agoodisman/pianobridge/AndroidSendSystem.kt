package com.agoodisman.pianobridge

import net.dv8tion.jda.api.audio.factory.DefaultSendSystem
import net.dv8tion.jda.api.audio.factory.IPacketProvider

class AndroidSendSystem(packetProvider: IPacketProvider) :
    DefaultSendSystem(packetProvider) {

    override fun setupThread(t: Thread) {
        // the priority will be set in the thread, so reset it here
        val oldPriority = t.priority;
        super.setupThread(t)
        t.priority = oldPriority
    }

    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        super.run()
    }
}