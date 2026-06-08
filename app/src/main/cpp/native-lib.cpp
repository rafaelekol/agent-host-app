#include <jni.h>
#include <cstdlib>
#include <cstring>

#include "node.h"

// Bridges Kotlin's NodeEngineManager.startNodeWithArguments into the embedded
// Node.js runtime. node::Start runs the libuv event loop and only returns once
// the Node process exits, so callers must invoke this off the main thread.
extern "C" JNIEXPORT jint JNICALL
Java_com_aizonme_agenthost_NodeEngineManager_startNodeWithArguments(
        JNIEnv *env, jobject /* this */, jobjectArray arguments) {

    jsize argument_count = env->GetArrayLength(arguments);

    // node::Start expects argv as a contiguous, NUL-separated buffer. First
    // pass: measure the total size needed.
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        const char *arg = env->GetStringUTFChars(
                (jstring) env->GetObjectArrayElement(arguments, i), nullptr);
        c_arguments_size += strlen(arg);
        c_arguments_size++; // room for the NUL terminator
    }

    char *args_buffer = (char *) calloc(c_arguments_size, sizeof(char));
    char *argv[argument_count];
    char *current_position = args_buffer;

    // Second pass: copy each argument into the buffer and record its pointer.
    for (int i = 0; i < argument_count; i++) {
        const char *current_argument = env->GetStringUTFChars(
                (jstring) env->GetObjectArrayElement(arguments, i), nullptr);
        strncpy(current_position, current_argument, strlen(current_argument));
        argv[i] = current_position;
        current_position += strlen(current_position) + 1;
    }

    int exit_code = node::Start(argument_count, argv);

    free(args_buffer);
    return (jint) exit_code;
}
