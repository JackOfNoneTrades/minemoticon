#ifndef MINEMOTICON_JNI_MD_H
#define MINEMOTICON_JNI_MD_H

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;

#ifdef _WIN64
typedef __int64 jlong;
#else
typedef long long jlong;
#endif

typedef signed char jbyte;

#endif
