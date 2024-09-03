package com.agoodisman.pianobridge

// JNI bridge methods
object EngineDelegate {
    // start outputting from the queues (async)
    external fun initializeEngineOutput(requestedSampleRate: Int, requestedChannelCount: Int): Boolean

    // wait for outputting to actually start (sync)
    external fun awaitOutputInitialized(): Boolean

    // start recording into the queues (async)
    external fun initializeEngineInput(requestedSampleRate: Int, requestedID: Int, requestedChannelCount: Int): Boolean

    // wait for recording to actually start (sync)
    external fun awaitInputInitialized(): Boolean

    // stop recording into the queues and close the stream (async)
    external fun stopEngineInput(): Boolean

    // wait for closing to actually happen (sync)
    external fun awaitInputStopped(): Boolean

    // provide data to the downlink queue
    external fun provideDownlinkData(arr: ShortArray, numSamples: Int)

    // get a data frame from the uplink queue to send to discord
    external fun retrieveUplinkData(arr: ShortArray, numSamples: Int)

    // stop streams
    external fun shutdown()
}