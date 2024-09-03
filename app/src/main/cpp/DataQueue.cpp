//
// Created by agoodisman on 9/1/2024.
//

#include <android/log.h>
#include "DataQueue.h"

void DataQueue::dequeue(short* receiver, int32_t numSamples) {

    // don't enqueue while we're de-queueing just in case
    std::unique_lock<std::mutex> lk(this->mutex);

    // how much to actually read, in case we have less than we need
    int32_t samplesToRead = std::min(numSamples, (int32_t)(this->data.size()));
    // copy data
    for(int i = 0; i < samplesToRead; i++) {
        receiver[i] = this->data.front();
        this->data.pop_front();
    }
    // at this point either we've taken all we need, or we've taken everything. either way we can drop the lock.
    lk.unlock();

    // in case there's more we need to write, fill with 0s.
    for(int i = samplesToRead; i < numSamples; i++) {
        receiver[i] = 0;
    }
}

void DataQueue::enqueue(short* provider, int32_t numSamples) {
    std::lock_guard<std::mutex> lk(this->mutex);

    for (int i = 0; i < numSamples; i++) {
        this->data.push_back(provider[i]);
    }
}