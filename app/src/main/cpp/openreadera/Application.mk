# Alexandria: configuração do ndk-build para os motores do OpenReadEra.
APP_STL := c++_static
APP_PLATFORM := android-23
APP_CPPFLAGS += -fexceptions -frtti -Wno-register
# O OpenReadEra foi escrito para o NDK r20. Compiladores atuais tratam como erro padrões antigos de C
# (função de biblioteca usada sem #include). São chamadas conhecidas pelo compilador (memcpy, strlen...),
# então voltam a ser só avisos, sem alterar o código original.
APP_CFLAGS += -Wno-error=implicit-function-declaration -Wno-error=int-conversion \
              -Wno-error=incompatible-function-pointer-types -Wno-error=implicit-int
