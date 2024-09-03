//
// Created by agoodisman on 9/1/2024.
//
#include <android/log.h>
#include <oboe/Oboe.h>
#include "OutputProcessor.h"

bool OutputProcessor::requestStart(int requestedSampleRate, int requestedChannelCount) {

    auto callbackHandler = std::make_shared<OutputProcessor::OutputCallbackHandler>(this);

    oboe::AudioStreamBuilder builder;
    oboe::Result result = builder.setSharingMode(oboe::SharingMode::Exclusive)
            ->setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(requestedChannelCount)
            ->setDataCallback(callbackHandler)
            ->setErrorCallback(callbackHandler)
            ->setSampleRate(requestedSampleRate)
            ->openStream(stream);
    this->hasStream = true;

    int32_t realSampleRate = stream->getSampleRate();
    if (realSampleRate != requestedSampleRate) {
        __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "sample rate mismatch: requested %d got %d", requestedSampleRate, realSampleRate);
        return false;
    }

    int32_t realChannelCount = stream->getChannelCount();
    if (realChannelCount != requestedChannelCount) {
        __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "channel count mismatch: requested %d got %d", requestedChannelCount, realChannelCount);
        return false;
    }
    this->channelCount = realChannelCount;

    int32_t realID = stream->getDeviceId();

    __android_log_print(ANDROID_LOG_INFO, OUTPUT_TAG, "output device id determined to be %d", realID);

    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "failed to open stream %s", oboe::convertToText(result));
        return false;
    }

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "failed to start stream %s", oboe::convertToText(result));
        return false;
    }
    return true;
}

bool OutputProcessor::waitForStarted() {
    oboe::StreamState resultState;
    oboe::Result result = stream->waitForStateChange(oboe::StreamState::Starting, &resultState, 1000*1000*1000); //1s timeout
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "failed to await state change from starting %s", oboe::convertToText(result));
        return false;
    }
    if (resultState != oboe::StreamState::Started) {
        __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "state change did not result in started %s", oboe::convertToText(resultState));
        return false;
    }
    return true;
}

void OutputProcessor::shutdown() {
    if (this->hasStream) {
        oboe::Result result = stream->close();
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "couldn't shut down right, proceeding anyway %s", oboe::convertToText(result));
        }
    }
}

void OutputProcessor::populateNextData(short* dataArray, int32_t numFrames) {
    int32_t numSamples = numFrames * this->channelCount;

    // get data from loopback queue
    short loopbackSamples[numSamples];
    this->fromLoopbackQueue->dequeue(loopbackSamples, numSamples);

    // get data from downlink queue
    short downlinkSamples[numSamples];
    this->fromDownlinkQueue->dequeue(downlinkSamples, numSamples);

    // mix together into output data.
    // the assumption here is that they won't add up to more than SHORT_MAX so we can just add
    // this prevents it from getting two quiet.
    // Fancy averaging is better but who cares.
    for(int i = 0; i < numSamples; i++) {
        dataArray[i] = (short)(loopbackSamples[i] + downlinkSamples[i]);
    }
}

void OutputProcessor::handleError(oboe::Result errorCode) {
    __android_log_print(ANDROID_LOG_ERROR, OUTPUT_TAG, "received error from stream %s", oboe::convertToText(errorCode));
}

oboe::DataCallbackResult OutputProcessor::OutputCallbackHandler::onAudioReady(
        oboe::AudioStream*, void* audioData, int32_t numFrames) {
    auto* shortPtr = (short*)(audioData);
    processor->populateNextData(shortPtr, numFrames);
    return oboe::DataCallbackResult::Continue;
}

bool OutputProcessor::OutputCallbackHandler::onError(oboe::AudioStream*, oboe::Result error) {
    OutputProcessor::handleError(error);
    return false;
}