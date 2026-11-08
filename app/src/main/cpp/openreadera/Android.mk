# Alexandria: só os módulos usados pelo app (MOBI, DJVU e quadrinhos CBR/CBZ) e suas dependências.
# O Android.mk original do OpenReadEra incluía todos os subdiretórios (também EraPDF e EraEPUB).
OREADERA_ROOT := $(call my-dir)
include $(OREADERA_ROOT)/orebridge/Android.mk
include $(OREADERA_ROOT)/orecrop/Android.mk
include $(OREADERA_ROOT)/jpeg-turbo/Android.mk
include $(OREADERA_ROOT)/orelibpng/Android.mk
include $(OREADERA_ROOT)/easybmp/Android.mk
include $(OREADERA_ROOT)/libwebp/Android.mk
include $(OREADERA_ROOT)/dmc_unrar/Android.mk
include $(OREADERA_ROOT)/minizip/Android.mk
include $(OREADERA_ROOT)/eramobi/Android.mk
include $(OREADERA_ROOT)/eradjvu/Android.mk
include $(OREADERA_ROOT)/eracomic/Android.mk
