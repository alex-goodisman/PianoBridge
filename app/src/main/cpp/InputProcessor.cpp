//
// Created by agoodisman on 9/1/2024.
//
#include <android/log.h>
#include <oboe/Oboe.h>
#include "InputProcessor.h"

bool InputProcessor::requestStart(int requestedSampleRate, int requestedID, int requestedChannelCount) {

    auto callbackHandler = std::make_shared<InputProcessor::InputCallbackHandler>(this);

    oboe::AudioStreamBuilder builder;
    oboe::AudioStreamBuilder* builderPtr = &builder;
    if (requestedID != -1) {
        builderPtr = builderPtr->setDeviceId(requestedID);
    }

    oboe::Result result = builderPtr->setSharingMode(oboe::SharingMode::Exclusive)
            ->setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(requestedChannelCount)
            ->setDataCallback(callbackHandler)
            ->setErrorCallback(callbackHandler)
            ->setSampleRate(requestedSampleRate)
            ->openStream(stream);
    hasStream = true;

    int32_t realSampleRate = stream->getSampleRate();
    if (realSampleRate != requestedSampleRate) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "sample rate mismatch: requested %d got %d", requestedSampleRate, realSampleRate);
        return false;
    }

    int32_t realID = stream->getDeviceId();
    if (requestedID != -1 && realID != requestedID) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "id mismatch: requested %d got %d", requestedID, realID);
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, INPUT_TAG, "input device id determined to be %d", realID);


    int32_t realChannelCount = stream->getChannelCount();
    if (realChannelCount != requestedChannelCount) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "channel count mismatch: requested %d got %d", requestedChannelCount, realChannelCount);
        return false;
    }
    this->channelCount = realChannelCount;

    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "failed to open stream %s", oboe::convertToText(result));
        return false;
    }

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "failed to start stream %s", oboe::convertToText(result));
        return false;
    }
    return true;
}

bool InputProcessor::waitForStarted() {
    oboe::StreamState resultState;
    oboe::Result result = stream->waitForStateChange(oboe::StreamState::Starting, &resultState, 1000*1000*1000); //1s timeout
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "failed to await state change from starting %s", oboe::convertToText(result));
        return false;
    }
    if (resultState != oboe::StreamState::Started) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "state change did not result in started %s", oboe::convertToText(resultState));
        return false;
    }
    return true;
}

bool InputProcessor::requestStop() {
    oboe::Result result = stream->requestStop();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "failed to stop stream %s", oboe::convertToText(result));
        return false;
    }
    return true;
}

bool InputProcessor::waitForStopped() {
    oboe::StreamState resultState;
    oboe::Result result = stream->waitForStateChange(oboe::StreamState::Stopping, &resultState, 1000*1000*1000); //1s timeout
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "failed to await state change from starting %s", oboe::convertToText(result));
        return false;
    }
    if (resultState != oboe::StreamState::Stopped) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "state change did not result in started %s", oboe::convertToText(resultState));
        return false;
    }

    // now close!
    result = stream->close();
    hasStream = false;
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "failed to close stream %s", oboe::convertToText(result));
        return false;
    }

    return true;
}

void InputProcessor::shutdown() {
    if (this->hasStream) {
        oboe::Result result = stream->close();
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "couldn't shut down right, proceeding anyway %s", oboe::convertToText(result));
        }
    }
}

void InputProcessor::retrieveNextData(const short* dataArray, int32_t numFrames) {
    int32_t numSamples = numFrames * this->channelCount;
    // get data from the source and put it in the temp array
    short samples[numSamples];

    for(int i = 0; i < numFrames; i++) {
        for (int j = 0; j < this->channelCount; j++) {
            samples[(i * this->channelCount) + j] = dataArray[(i * this->channelCount) + j];
        }
    }

    // de-multiplex the input both to uplink and loopback
    // enqueue is nondestructive so this works
    this->toLoopbackQueue->enqueue(samples, numFrames * this->channelCount);
    this->toUplinkQueue->enqueue(samples, numFrames * this->channelCount);
}

void InputProcessor::handleError(oboe::Result errorCode) {
    __android_log_print(ANDROID_LOG_ERROR, INPUT_TAG, "received error from stream %s", oboe::convertToText(errorCode));
}


oboe::DataCallbackResult InputProcessor::InputCallbackHandler::onAudioReady(
        oboe::AudioStream*, void* audioData, int32_t numFrames) {
    auto* shortPtr = (short*)(audioData);
    processor->retrieveNextData(shortPtr, numFrames);
    return oboe::DataCallbackResult::Continue;
}

bool InputProcessor::InputCallbackHandler::onError(oboe::AudioStream*, oboe::Result error) {
    InputProcessor::handleError(error);
    return false;
}