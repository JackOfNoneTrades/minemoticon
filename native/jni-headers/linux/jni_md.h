#ifndef MINEMOTICON_JNI_MD_H
#define MINEMOTICON_JNI_MD_H

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL

typedef int jint;

#ifdef __LP64__
typedef long jlong;
#else
typedef long long jlong;
#endif

typedef signed char jbyte;

#endif
