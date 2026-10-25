#pragma once
#include <cstdint>
#include <cstddef>
typedef int32_t jint; typedef int64_t jlong; typedef float jfloat; typedef double jdouble;
typedef uint8_t jboolean; typedef signed char jbyte; typedef int32_t jsize; typedef void* jobject;
typedef void* jstring; typedef void* jclass; typedef void* jarray; typedef void* jintArray;
typedef void* jdoubleArray; typedef void* jbyteArray; typedef void* jmethodID;
#define JNIEXPORT
#define JNICALL
#define JNI_FALSE 0
#define JNI_TRUE 1
#define JNI_OK 0
#define JNI_ERR (-1)
#define JNI_ABORT 2
#define JNI_VERSION_1_6 0x00010006
struct JNIEnv {
    const char* GetStringUTFChars(jstring, jboolean*);
    void ReleaseStringUTFChars(jstring, const char*);
    jsize GetArrayLength(jarray);
    void GetIntArrayRegion(jintArray, jsize, jsize, jint*);
    void GetDoubleArrayRegion(jdoubleArray, jsize, jsize, jdouble*);
    jbyte* GetByteArrayElements(jbyteArray, jboolean*);
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint);
    jstring NewStringUTF(const char*);
    jintArray NewIntArray(jsize);
    void SetIntArrayRegion(jintArray, jsize, jsize, const jint*);
    void* GetDirectBufferAddress(jobject);
    jlong GetDirectBufferCapacity(jobject);
    jboolean ExceptionCheck();
};
struct JavaVM_ { jint GetEnv(void**, jint); };
typedef JavaVM_ JavaVM;
