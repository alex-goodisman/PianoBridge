//
// Created by agoodisman on 9/1/2024.
//

#ifndef PIANOBRIDGE_DATAQUEUE_H
#define PIANOBRIDGE_DATAQUEUE_H


#include <deque>
#include <mutex>


class DataQueue {
public:
    void enqueue(short* data, int32_t numFrames);
    void dequeue(short* data, int32_t numFrames);
private:
    std::mutex mutex;
    // as long as everyone agrees on the sample rate
    // we can just mush everything together into one long queue and insert/remove data from either end
    std::deque<short> data;
};


#endif //PIANOBRIDGE_DATAQUEUE_H
