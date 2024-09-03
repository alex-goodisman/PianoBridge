//
// Created by agoodisman on 9/1/2024.
//

#ifndef PIANOBRIDGE_OUTPUTPROCESSOR_H
#define PIANOBRIDGE_OUTPUTPROCESSOR_H

#include "DataQueue.h"

#define OUTPUT_TAG "pianobridge-output"

class OutputProcessor {

public:
    OutputProcessor(DataQueue* q, DataQueue* d): fromLoopbackQueue(q), fromDownlinkQueue(d) {}
    // start playback. this is async and should return as soon as the stream is created.
    // actual playback may not happen until later.
    bool requestStart(int requestedSampleRate, int requestedChannelCount);
    // after calling requestStart, call this to wait for start to commence. this is for UI only.
    bool waitForStarted();
    // release resources
    void shutdown();

private:
    std::shared_ptr<oboe::AudioStream> stream;
    // in case close is called before open
    bool hasStream = false;
    DataQueue* fromLoopbackQueue;
    DataQueue* fromDownlinkQueue;
    int channelCount = 0;

    // oboe callbacks
    static void handleError(oboe::Result errorCode);
    void populateNextData(short* dataArray, int32_t numFrames);


    // oboe callbacks handler, just forwards requests back to the processor
    class OutputCallbackHandler : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
        public:
            explicit OutputCallbackHandler(OutputProcessor* parent): processor(parent) {}

            oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override;
            bool onError(oboe::AudioStream*, oboe::Result error) override;

        private:
            OutputProcessor* processor;
    };
};


#endif //PIANOBRIDGE_OUTPUTPROCESSOR_H
