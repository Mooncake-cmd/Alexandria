/* Substituto de <android/log.h> para compilar os motores no Linux (só nos testes). */
#ifndef ALEXANDRIA_STUB_ANDROID_LOG_H
#define ALEXANDRIA_STUB_ANDROID_LOG_H
#ifdef __cplusplus
extern "C" {
#endif
enum { ANDROID_LOG_UNKNOWN, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
       ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT };
int __android_log_print(int prio, const char* tag, const char* fmt, ...);
int __android_log_write(int prio, const char* tag, const char* text);
void __android_log_assert(const char* cond, const char* tag, const char* fmt, ...);
#ifdef __cplusplus
}
#endif
#endif
