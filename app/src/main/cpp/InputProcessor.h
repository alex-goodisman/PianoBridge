//
// Created by agoodisman on 9/1/2024.
//

#ifndef PIANOBRIDGE_INPUTPROCESSOR_H
#define PIANOBRIDGE_INPUTPROCESSOR_H

#include "DataQueue.h"

#define INPUT_TAG "pianobridge-input"

class InputProcessor {

public:
    InputProcessor(DataQueue* q, DataQueue* u): toLoopbackQueue(q), toUplinkQueue(u) {}
    // start recording. this is async and should return as soon as the stream is created.
    // actual recording may not happen until later.
    bool requestStart(int requestedSampleRate, int requestedID, int requestedChannelCount);
    // after calling requestStart, call this to wait for start to commence. this is for UI only.
    bool waitForStarted();
    // begin stopping
    bool requestStop();
    // wait for stop to complete then close
    bool waitForStopped();
    //release resources
    void shutdown();

private:
    std::shared_ptr<oboe::AudioStream> stream;
    // in case shutdown is called after we've stopped a stream but before we've started a new one
    bool hasStream = false;
    DataQueue* toLoopbackQueue;
    DataQueue* toUplinkQueue;
    int channelCount = 0;

    // oboe callbacks
    static void handleError(oboe::Result errorCode);
    void retrieveNextData(const short* dataArray, int32_t numFrames);

    // oboe callback handler, just forward calls back to the processor
    class InputCallbackHandler : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
        public:
        explicit InputCallbackHandler(InputProcessor* parent): processor(parent) {}

            oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override;
            bool onError(oboe::AudioStream*, oboe::Result error) override;

        private:
        InputProcessor* processor;
    };
};


#endif //PIANOBRIDGE_INPUTPROCESSOR_H
