#include <jni.h>
#include <string>
#include <oboe/Oboe.h>
#include "OutputProcessor.h"
#include "InputProcessor.h"
#include "DataQueue.h"

// inter-stream queues. These buffer data between sources and sinks

// low-latency on-device queue. This connects the local source to the local sink.
static DataQueue loopbackQueue;
// queue for receiving output from discord
static DataQueue downlinkQueue;
// queue for sending input to discord
static DataQueue uplinkQueue;

// stream manager for the speaker/headset
static OutputProcessor outputProcessor(&loopbackQueue, &downlinkQueue);
// stream manager for the input/mic
static InputProcessor inputProcessor(&loopbackQueue, &uplinkQueue);

// start outputting async
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_initializeEngineOutput(JNIEnv*, jobject /* this */, jint requestedSampleRate, jint requestedChannelCount) {
    return outputProcessor.requestStart(requestedSampleRate, requestedChannelCount) ? JNI_TRUE : JNI_FALSE;
}

// confirm output started
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_awaitOutputInitialized(JNIEnv*, jobject /* this */) {
    return outputProcessor.waitForStarted() ? JNI_TRUE : JNI_FALSE;
}

// start inputting async
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_initializeEngineInput(JNIEnv*, jobject /* this */, jint requestedSampleRate, jint requestedID, jint requestedChannelCount) {
    return inputProcessor.requestStart(requestedSampleRate, requestedID, requestedChannelCount) ? JNI_TRUE : JNI_FALSE;
}

// confirm input started
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_awaitInputInitialized(JNIEnv*, jobject /* this */) {
    return inputProcessor.waitForStarted() ? JNI_TRUE : JNI_FALSE;
}

// stop inputting async
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_stopEngineInput(JNIEnv*, jobject /* this */) {
    return inputProcessor.requestStop() ? JNI_TRUE : JNI_FALSE;
}

// confirm input stopped, then close the stream
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_awaitInputStopped(JNIEnv*, jobject /* this */) {
    return inputProcessor.waitForStopped() ? JNI_TRUE : JNI_FALSE;
}

// insert data into the downlink queue to be played back, called from discord connection
extern "C"
JNIEXPORT void JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_provideDownlinkData(JNIEnv* env, jobject /* this */, jshortArray arr, jint numSamples) {
    jsize size = env->GetArrayLength(arr);
    short storage[size];
    env->GetShortArrayRegion(arr, 0, size, storage);
    downlinkQueue.enqueue(storage, numSamples);
}

// get data from the uplink queue to be sent to discord, called from the discord connection
extern "C"
JNIEXPORT void JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_retrieveUplinkData(JNIEnv* env, jobject /* this */, jshortArray arr, jint numSamples) {
    jsize size = env->GetArrayLength(arr);
    short storage[size];
    uplinkQueue.dequeue(storage, numSamples);
    env->SetShortArrayRegion(arr, 0, size, storage);
}

// release reources before quitting
extern "C"
JNIEXPORT void JNICALL
Java_com_agoodisman_pianobridge_EngineDelegate_shutdown(JNIEnv*, jobject /* this */) {
    inputProcessor.shutdown();
    outputProcessor.shutdown();
}