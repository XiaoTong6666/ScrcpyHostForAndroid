APP_LOCAL_PATH := $(call my-dir)
LOCAL_PATH := $(APP_LOCAL_PATH)

include $(APP_LOCAL_PATH)/../../../../SDL/Android.mk

LOCAL_PATH := $(APP_LOCAL_PATH)

include $(CLEAR_VARS)

LOCAL_MODULE := main

SDL_PATH := ../../../../SDL

LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(SDL_PATH)/include

LOCAL_SRC_FILES := \
	src/remote_main.c

LOCAL_SHARED_LIBRARIES := SDL3

LOCAL_LDLIBS := -lGLESv1_CM -lGLESv2 -lOpenSLES -llog -landroid

include $(BUILD_SHARED_LIBRARY)
