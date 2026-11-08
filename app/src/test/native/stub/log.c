/* Logs dos motores vão para a saída de erro (só nos testes). */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include "android/log.h"
int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    if (prio < ANDROID_LOG_WARN && !getenv("ORE_VERBOSE")) return 0;
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr);
    va_end(ap); return 0;
}
int __android_log_write(int prio, const char* tag, const char* text) { return __android_log_print(prio, tag, "%s", text); }
void __android_log_assert(const char* cond, const char* tag, const char* fmt, ...) { fprintf(stderr, "[%s] assert %s\n", tag, cond ? cond : ""); abort(); }
