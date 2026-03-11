#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "ScrcpyRemoteSDL"

static pthread_mutex_t g_session_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_frame_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_frame_cond = PTHREAD_COND_INITIALIZER;
static char g_endpoint[128] = "pending";
static char g_backend_url[256] = "pending";
static bool g_should_quit = false;
static bool g_external_video_mode = false;
static int g_surface_width = 720;
static int g_surface_height = 1280;
static uint8_t* g_frame_pixels = NULL;
static size_t g_frame_capacity = 0;
static int g_frame_width = 0;
static int g_frame_height = 0;
static int g_frame_stride = 0;
static uint64_t g_frame_serial = 0;
static bool g_has_frame = false;

static void copy_java_string(JNIEnv* env, jstring source, char* destination,
                             size_t destination_size) {
    if (destination_size == 0) {
        return;
    }

    if (source == NULL) {
        destination[0] = '\0';
        return;
    }

    const char* utf_chars = (*env)->GetStringUTFChars(env, source, NULL);
    if (utf_chars == NULL) {
        destination[0] = '\0';
        return;
    }

    snprintf(destination, destination_size, "%s", utf_chars);
    (*env)->ReleaseStringUTFChars(env, source, utf_chars);
}

JNIEXPORT void JNICALL Java_io_github_xiaotong6666_scrcpy_SdlSessionBridge_setSessionMetadata(
    JNIEnv* env, jclass clazz, jstring endpoint, jstring backend_url) {
    (void)clazz;
    pthread_mutex_lock(&g_session_mutex);
    copy_java_string(env, endpoint, g_endpoint, sizeof(g_endpoint));
    copy_java_string(env, backend_url, g_backend_url, sizeof(g_backend_url));
    pthread_mutex_unlock(&g_session_mutex);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "session=%s backend=%s", g_endpoint,
                        g_backend_url);
}

JNIEXPORT void JNICALL
Java_io_github_xiaotong6666_scrcpy_SdlSessionBridge_clearVideoFrame(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_frame_mutex);
    g_has_frame = false;
    g_frame_width = 0;
    g_frame_height = 0;
    g_frame_stride = 0;
    g_frame_serial++;
    pthread_cond_broadcast(&g_frame_cond);
    pthread_mutex_unlock(&g_frame_mutex);
}

JNIEXPORT void JNICALL Java_io_github_xiaotong6666_scrcpy_SdlSessionBridge_setExternalVideoMode(
    JNIEnv* env, jclass clazz, jboolean enabled) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_frame_mutex);
    g_external_video_mode = enabled == JNI_TRUE;
    pthread_cond_broadcast(&g_frame_cond);
    pthread_mutex_unlock(&g_frame_mutex);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "external_video_mode=%d",
                        g_external_video_mode ? 1 : 0);
}

JNIEXPORT void JNICALL Java_io_github_xiaotong6666_scrcpy_SdlSessionBridge_submitDecodedFrame(
    JNIEnv* env, jclass clazz, jobject frame_buffer, jint width, jint height, jint stride) {
    (void)clazz;

    if (frame_buffer == NULL || width <= 0 || height <= 0 || stride < width * 4) {
        return;
    }

    void* direct_address = (*env)->GetDirectBufferAddress(env, frame_buffer);
    jlong direct_capacity = (*env)->GetDirectBufferCapacity(env, frame_buffer);
    size_t required_bytes = (size_t)stride * (size_t)height;
    if (direct_address == NULL || direct_capacity < (jlong)required_bytes) {
        return;
    }

    pthread_mutex_lock(&g_frame_mutex);
    if (g_frame_capacity < required_bytes) {
        uint8_t* resized = (uint8_t*)realloc(g_frame_pixels, required_bytes);
        if (resized == NULL) {
            pthread_mutex_unlock(&g_frame_mutex);
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                "Failed to allocate %zu-byte frame buffer", required_bytes);
            return;
        }
        g_frame_pixels = resized;
        g_frame_capacity = required_bytes;
    }

    memcpy(g_frame_pixels, direct_address, required_bytes);
    g_frame_width = width;
    g_frame_height = height;
    g_frame_stride = stride;
    g_has_frame = true;
    g_frame_serial++;
    pthread_cond_broadcast(&g_frame_cond);
    pthread_mutex_unlock(&g_frame_mutex);
}

static void wait_for_next_frame(uint64_t known_serial, uint32_t timeout_ms) {
    struct timespec wake_deadline;
    clock_gettime(CLOCK_MONOTONIC, &wake_deadline);
    wake_deadline.tv_nsec += (long)timeout_ms * 1000000L;
    if (wake_deadline.tv_nsec >= 1000000000L) {
        wake_deadline.tv_sec += wake_deadline.tv_nsec / 1000000000L;
        wake_deadline.tv_nsec %= 1000000000L;
    }

    pthread_mutex_lock(&g_frame_mutex);
    while (!g_should_quit && g_frame_serial == known_serial) {
        if (pthread_cond_timedwait(&g_frame_cond, &g_frame_mutex, &wake_deadline) != 0) {
            break;
        }
    }
    pthread_mutex_unlock(&g_frame_mutex);
}

static uint32_t hash_endpoint_color(void) {
    uint32_t hash = 2166136261u;
    pthread_mutex_lock(&g_session_mutex);
    for (size_t i = 0; g_endpoint[i] != '\0'; ++i) {
        hash ^= (uint8_t)g_endpoint[i];
        hash *= 16777619u;
    }
    pthread_mutex_unlock(&g_session_mutex);
    return hash;
}

static void draw_placeholder(SDL_Renderer* renderer, int frame_index) {
    const uint32_t hash = hash_endpoint_color();
    const uint8_t accent_r = (uint8_t)(0x40 + (hash & 0x4f));
    const uint8_t accent_g = (uint8_t)(0x60 + ((hash >> 8) & 0x5f));
    const uint8_t accent_b = (uint8_t)(0x80 + ((hash >> 16) & 0x6f));

    SDL_SetRenderDrawColor(renderer, 8, 11, 19, 255);
    SDL_RenderClear(renderer);

    SDL_FRect outer = {(float)g_surface_width / 7.0f, (float)g_surface_height / 10.0f,
                       (float)(g_surface_width * 5) / 7.0f, (float)(g_surface_height * 4) / 5.0f};
    SDL_SetRenderDrawColor(renderer, 20, 26, 40, 255);
    SDL_RenderFillRect(renderer, &outer);

    SDL_FRect inner = {outer.x + 22.0f, outer.y + 22.0f, outer.w - 44.0f, outer.h - 44.0f};
    SDL_SetRenderDrawColor(renderer, 12, 18, 30, 255);
    SDL_RenderFillRect(renderer, &inner);

    for (int row = 0; row < 7; ++row) {
        const float y = inner.y + ((float)row * inner.h) / 7.0f;
        const uint8_t pulse = (uint8_t)((frame_index * 3 + row * 17) % 120);
        SDL_SetRenderDrawColor(renderer, accent_r, accent_g, (uint8_t)(accent_b - pulse / 3), 255);
        SDL_RenderLine(renderer, inner.x, y, inner.x + inner.w, y);
    }

    const float wave_x = inner.x + (float)((frame_index * 9) % (int)inner.w);
    SDL_SetRenderDrawColor(renderer, 180, 230, 255, 255);
    SDL_RenderLine(renderer, wave_x, inner.y, wave_x, inner.y + inner.h);

    SDL_FRect focus = {inner.x + inner.w / 5.0f, inner.y + inner.h / 5.0f, (inner.w * 3.0f) / 5.0f,
                       (inner.h * 3.0f) / 5.0f};
    SDL_SetRenderDrawColor(renderer, accent_r, accent_g, accent_b, 255);
    SDL_RenderRect(renderer, &focus);

    SDL_FRect status = {inner.x + 32.0f, inner.y + inner.h - 80.0f, inner.w - 64.0f, 24.0f};
    SDL_SetRenderDrawColor(renderer, accent_r, accent_g, accent_b, 255);
    SDL_RenderFillRect(renderer, &status);
}

static bool update_texture_from_frame(SDL_Renderer* renderer, SDL_Texture** texture,
                                      int* texture_width, int* texture_height,
                                      uint64_t* uploaded_serial) {
    bool has_frame = false;
    int frame_width = 0;
    int frame_height = 0;

    pthread_mutex_lock(&g_frame_mutex);
    if (g_has_frame && g_frame_pixels != NULL) {
        frame_width = g_frame_width;
        frame_height = g_frame_height;

        if (*texture == NULL || *texture_width != frame_width || *texture_height != frame_height) {
            if (*texture != NULL) {
                SDL_DestroyTexture(*texture);
                *texture = NULL;
            }

            *texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_RGBA32,
                                         SDL_TEXTUREACCESS_STREAMING, frame_width, frame_height);
            if (*texture == NULL) {
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SDL_CreateTexture failed: %s",
                                    SDL_GetError());
            } else {
                *texture_width = frame_width;
                *texture_height = frame_height;
                *uploaded_serial = 0;
            }
        }

        if (*texture != NULL && *uploaded_serial != g_frame_serial) {
            if (!SDL_UpdateTexture(*texture, NULL, g_frame_pixels, g_frame_stride)) {
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SDL_UpdateTexture failed: %s",
                                    SDL_GetError());
            } else {
                *uploaded_serial = g_frame_serial;
            }
        }

        has_frame = *texture != NULL;
    }
    pthread_mutex_unlock(&g_frame_mutex);

    if (!has_frame || *texture == NULL || frame_width <= 0 || frame_height <= 0) {
        return false;
    }

    SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
    SDL_RenderClear(renderer);

    const float video_aspect = (float)frame_width / (float)frame_height;
    const float surface_aspect = (float)g_surface_width / (float)g_surface_height;
    SDL_FRect destination = {0};

    if (video_aspect > surface_aspect) {
        destination.w = (float)g_surface_width;
        destination.h = destination.w / video_aspect;
        destination.x = 0.0f;
        destination.y = ((float)g_surface_height - destination.h) * 0.5f;
    } else {
        destination.h = (float)g_surface_height;
        destination.w = destination.h * video_aspect;
        destination.y = 0.0f;
        destination.x = ((float)g_surface_width - destination.w) * 0.5f;
    }

    if (!SDL_RenderTexture(renderer, *texture, NULL, &destination)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SDL_RenderTexture failed: %s",
                            SDL_GetError());
        return false;
    }

    return true;
}

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;

    g_should_quit = false;
    g_has_frame = false;

    SDL_SetHint("SDL_ANDROID_ALLOW_RECREATE_ACTIVITY", "1");

    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SDL_Init failed: %s", SDL_GetError());
        return 1;
    }

    SDL_Window* window = NULL;
    SDL_Renderer* renderer = NULL;
    if (!SDL_CreateWindowAndRenderer("scrcpy Remote", g_surface_width, g_surface_height,
                                     SDL_WINDOW_FULLSCREEN, &window, &renderer)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SDL_CreateWindowAndRenderer failed: %s",
                            SDL_GetError());
        SDL_Quit();
        return 1;
    }
    SDL_SetRenderVSync(renderer, 0);

    SDL_Texture* frame_texture = NULL;
    int texture_width = 0;
    int texture_height = 0;
    uint64_t uploaded_serial = 0;
    int frame_index = 0;

    while (!g_should_quit) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            if (event.type == SDL_EVENT_QUIT) {
                g_should_quit = true;
            }
        }

        if (!SDL_GetCurrentRenderOutputSize(renderer, &g_surface_width, &g_surface_height)) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "SDL_GetCurrentRenderOutputSize failed: %s", SDL_GetError());
        }

        if (g_external_video_mode) {
            wait_for_next_frame(uploaded_serial, 250);
            continue;
        }

        if (!update_texture_from_frame(renderer, &frame_texture, &texture_width, &texture_height,
                                       &uploaded_serial)) {
            draw_placeholder(renderer, frame_index++);
        }

        SDL_RenderPresent(renderer);
        wait_for_next_frame(uploaded_serial, g_has_frame ? 2 : 8);
    }

    if (frame_texture != NULL) {
        SDL_DestroyTexture(frame_texture);
    }
    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);

    pthread_mutex_lock(&g_frame_mutex);
    free(g_frame_pixels);
    g_frame_pixels = NULL;
    g_frame_capacity = 0;
    pthread_mutex_unlock(&g_frame_mutex);

    SDL_Quit();
    return 0;
}
