#include "bridge_capture.h"

#include "bridge_frame_buffer.h"
#include "bridge_preview.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

struct NativeCapturer {
    AImageReader *reader = nullptr;
    ANativeWindow *window = nullptr;
    AImageReader_ImageListener listener{};
    int width = 0;
    int height = 0;
};

static NativeCapturer *g_capturer = nullptr;

static void onImageAvailable(void *context, AImageReader *reader) {
    (void) context;

    AImage *image = nullptr;
    if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK || !image) {
        return;
    }

    // ## 诊断：核对 image 真实尺寸与 AImageReader 声明尺寸
    //
    // `AImageReader_newWithUsage(1920, 1080, ...)` 只是**请求**，
    // 实际拿到的 image 可能是别的尺寸。
    //
    // 如果游戏按物理屏比例（荣耀 2.2）渲染，image 可能是 1920x872，
    // 而 `ProcessFrameDataV2` 会按 target（1920x1080）去读 ——
    // 读到越界数据、画面撕裂，或者只读到上半部分导致下半屏全黑。
    //
    // 这类问题**编译期毫无提示**，只有真机才暴露，所以必须打日志。
    {
        int32_t imgW = 0, imgH = 0;
        AImage_getWidth(image, &imgW);
        AImage_getHeight(image, &imgH);
        if (g_capturer && (imgW != g_capturer->width || imgH != g_capturer->height)) {
            // 只报一次，避免每帧刷屏
            static bool mismatchLogged = false;
            if (!mismatchLogged) {
                mismatchLogged = true;
                LOGE(
                    "AImageReader 尺寸不匹配！收到 %dx%d，声明 %dx%d "
                    "—— 画面将被拉伸/裁切。这通常意味着 ROM 按物理屏比例渲染了虚拟屏",
                    imgW, imgH, g_capturer->width, g_capturer->height);
            }
        } else {
            static bool okLogged = false;
            if (!okLogged) {
                okLogged = true;
                LOGI("AImageReader 尺寸正常: %dx%d", imgW, imgH);
            }
        }
    }

    AHardwareBuffer *hb = nullptr;
    if (AImage_getHardwareBuffer(image, &hb) == AMEDIA_OK && hb) {
        WriteHardwareBufferToFrame(hb);
    }

    bool handedOver = false;
    if (IsPreviewEnabled()) {
        handedOver = DispatchPreview(image);
    }

    if (!handedOver) {
        AImage_delete(image);
    }
}

jobject SetupNativeCapturer(JNIEnv *env, int width, int height) {
    ReleaseNativeCapturer();
    InitFrameBuffers(width, height);

    g_capturer = new NativeCapturer();
    g_capturer->width = width;
    g_capturer->height = height;

    media_status_t status = AImageReader_newWithUsage(
            width, height, AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 5,
            &g_capturer->reader);
    if (status != AMEDIA_OK) {
        LOGE("AImageReader_newWithUsage failed: %d", status);
        delete g_capturer;
        g_capturer = nullptr;
        ReleaseFrameBuffers();
        return nullptr;
    }

    g_capturer->listener.context = g_capturer;
    g_capturer->listener.onImageAvailable = onImageAvailable;
    status = AImageReader_setImageListener(g_capturer->reader, &g_capturer->listener);
    if (status != AMEDIA_OK) {
        LOGE("SetupNativeCapturer: AImageReader_setImageListener failed: %d", status);
        AImageReader_delete(g_capturer->reader);
        delete g_capturer;
        g_capturer = nullptr;
        ReleaseFrameBuffers();
        return nullptr;
    }

    status = AImageReader_getWindow(g_capturer->reader, &g_capturer->window);
    if (status != AMEDIA_OK || !g_capturer->window) {
        LOGE("SetupNativeCapturer: AImageReader_getWindow failed: status=%d, window=%p",
             status, g_capturer->window);
        AImageReader_setImageListener(g_capturer->reader, nullptr);
        AImageReader_delete(g_capturer->reader);
        delete g_capturer;
        g_capturer = nullptr;
        ReleaseFrameBuffers();
        return nullptr;
    }

    return ANativeWindow_toSurface(env, g_capturer->window);
}

void ReleaseNativeCapturer() {
    DrainPreviewQueue();

    if (g_capturer) {
        if (g_capturer->reader) {
            AImageReader_setImageListener(g_capturer->reader, nullptr);
        }
        if (g_capturer->reader) {
            AImageReader_delete(g_capturer->reader);
        }

        delete g_capturer;
        g_capturer = nullptr;
        LOGI("NativeCapturer released");
    }

    ReleaseFrameBuffers();
}
