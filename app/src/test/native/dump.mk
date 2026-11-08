# Lê os Android.mk dos motores sem o NDK e imprime, por módulo, fontes e opções de compilação.
# Usado só pelos testes (build_host_engines.py), para compilar os motores para Linux.
my-dir = $(patsubst %/,%,$(dir $(lastword $(MAKEFILE_LIST))))
CLEAR_VARS := $(SHIM)/clear.mk
BUILD_STATIC_LIBRARY := $(SHIM)/module.mk
BUILD_SHARED_LIBRARY := $(SHIM)/module.mk
BUILD_EXECUTABLE := $(SHIM)/module.mk
TARGET_ARCH := x86_64
TARGET_ARCH_ABI := x86_64
APP_OPTIM := release
NDK_TOOLCHAIN_VERSION := clang
MODULES :=
call = $(if $(filter my-dir,$(1)),$(my-dir),)
import-module =

include $(ROOT)/Android.mk

dump:
	@$(foreach m,$(MODULES),\
	  echo "MODULE $(m)"; \
	  echo "PATH $(MODULE_PATH.$(m))"; \
	  echo "SRC $(MODULE_SRC.$(m))"; \
	  echo "CFLAGS $(subst ",\",$(MODULE_CFLAGS.$(m)))"; \
	  echo "CPPFLAGS $(subst ",\",$(MODULE_CPPFLAGS.$(m)))"; \
	  echo "INC $(MODULE_INC.$(m))"; \
	  echo "EXPORT $(MODULE_EXPORT.$(m))"; \
	  echo "STATIC $(MODULE_STATIC.$(m))"; \
	  echo "LDLIBS $(MODULE_LDLIBS.$(m))";)
