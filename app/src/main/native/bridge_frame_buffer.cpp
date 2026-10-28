#include "bridge_frame_buffer.h"

#include <android/bitmap.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <thread>

#if defined(__ARM_NEON)

#include <arm_neon.h>

#endif

static FrameBuffer g_buffers[FRAME_BUFFER_COUNT] = {};
static std::atomic<int> g_buffer_states[FRAME_BUFFER_COUNT] = {
        FRAME_STATE_FREE, FRAME_STATE_FREE, FRAME_STATE_FREE
};
static std::atomic<int> g_reader_counts[FRAME_BUFFER_COUNT] = {0, 0, 0};
static std::atomic<FrameBuffer *> g_read_buffer{nullptr};
static std::atomic<int64_t> g_frame_count{0};
static std::atomic<bool> g_frame_buffers_initialized{false};

/**
 * 把 RGBA 源**按实际尺寸**读出来，再**最近邻缩放**到目标尺寸的 BGR。
 *
 * # 为什么要支持缩放
 *
 * 老版本 `ProcessFrameDataV2` 假定 `srcW == dstW && srcH == dstH`，
 * 直接按目标尺寸逐行读。只要硬件 buffer 的实际尺寸和
 * `InitFrameBuffers` 声明的不一致，就会：
 *
 * | 实际 vs 目标 | 后果 |
 * |---|---|
 * | 实际更小 | 读到未初始化内存，下半屏全是脏数据 |
 * | 实际更大 | 只读了左上角一块，**UI 被切在外面** |
 * | 宽高比不同 | 画面横向压扁 |
 *
 * 实测就踩到了：荣耀设备物理屏 2.2 比例，虚拟屏声明 1920×1080，
 * 但硬件 buffer 可能是 1920×872 —— 引擎读到 872 行之后全是垃圾，
 * OCR 只看得到一个 `U`，任务永远卡在「点击进入」。
 *
 * 而预览那条路用的是 `eglCreateImageKHR` + GL 采样，**天然支持任意尺寸**，
 * 所以「预览看得见、引擎认不出」这个矛盾现象就出现了。
 *
 * # 实现
 *
 * 逐目标像素反查源坐标（最近邻），保证：
 * - 一定在源范围之内（不会越界读）
 * - 宽高比不同也能铺满（轻微形变，但比全黑/裁切好得多）
 *
 * 性能：1920×1080 ≈ 200 万像素，纯标量循环在 arm64 上约 8~12 ms，
 * 和原来的 NEON 版本（约 3 ms）相比多了几毫秒，但只在**尺寸不一致**时才走这条路，
 * 正常设备仍然使用下面那个零拷贝的快速路径。
 */
static void ProcessFrameDataScaled(
        const uint8_t *__restrict src,
        uint8_t *__restrict dst_bgr,
        int srcW, int srcH, int srcStride,
        int dstW, int dstH) {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;

    for (int y = 0; y < dstH; ++y) {
        // 反查源行
        int sy = static_cast<int>(static_cast<int64_t>(y) * srcH / dstH);
        if (sy >= srcH) sy = srcH - 1;
        const uint8_t *srow = src + static_cast<size_t>(sy) * srcStride;
        uint8_t *drow = dst_bgr + static_cast<size_t>(y) * dstW * 3;

        for (int x = 0; x < dstW; ++x) {
            int sx = static_cast<int>(static_cast<int64_t>(x) * srcW / dstW);
            if (sx >= srcW) sx = srcW - 1;
            const uint8_t *px = srow + static_cast<size_t>(sx) * 4;
            uint8_t *d = drow + x * 3;
            d[0] = px[2];
            d[1] = px[1];
            d[2] = px[0];
        }
    }
}

static void ProcessFrameDataV2(
        const uint8_t *__restrict src,
        uint8_t *__restrict dst_bgr,
        int width,
        int height,
        int src_stride) {
    for (int y = 0; y < height; ++y) {
        const uint8_t *s = src + static_cast<size_t>(y) * src_stride;
        uint8_t *d3 = dst_bgr + y * width * 3;
        int x = 0;

#if defined(__ARM_NEON)
        for (; x <= width - 16; x += 16) {
            uint8x16x4_t rgba = vld4q_u8(s);
            s += 64;
            uint8x16x3_t bgr;
            bgr.val[0] = rgba.val[2];
            bgr.val[1] = rgba.val[1];
            bgr.val[2] = rgba.val[0];
            vst3q_u8(d3, bgr);
            d3 += 48;
        }
        for (; x <= width - 8; x += 8) {
            uint8x8x4_t rgba = vld4_u8(s);
            s += 32;
            uint8x8x3_t bgr;
            bgr.val[0] = rgba.val[2];
            bgr.val[1] = rgba.val[1];
            bgr.val[2] = rgba.val[0];
            vst3_u8(d3, bgr);
            d3 += 24;
        }
#endif
        for (; x < width; ++x) {
            d3[0] = s[2];
            d3[1] = s[1];
            d3[2] = s[0];
            s += 4;
            d3 += 3;
        }
    }
}

static int GetBufferIndex(FrameBuffer *buf) {
    return buf->index;
}

static void ReleaseBuffer(FrameBuffer *buf) {
    if (!buf) {
        return;
    }
    if (buf->bgr_data) {
        free(buf->bgr_data);
        buf->bgr_data = nullptr;
    }
    buf->width = 0;
    buf->height = 0;
    buf->bgr_size = 0;
    buf->frame_count = 0;
}

static void MarkBufferFree(FrameBuffer *buf) {
    int idx = GetBufferIndex(buf);
    if (idx >= 0) {
        g_buffer_states[idx].store(FRAME_STATE_FREE, std::memory_order_release);
    }
}

static void CommitWriteBuffer(FrameBuffer *buf) {
    int idx = GetBufferIndex(buf);
    if (idx < 0) {
        return;
    }
    g_buffer_states[idx].store(FRAME_STATE_FREE, std::memory_order_release);
    if (g_frame_buffers_initialized.load(std::memory_order_acquire)) {
        g_read_buffer.store(buf, std::memory_order_release);
    }
}

static FrameBuffer *AcquireWriteBuffer() {
    if (!g_frame_buffers_initialized.load(std::memory_order_acquire)) {
        return nullptr;
    }

    FrameBuffer *currentReadBuffer = g_read_buffer.load(std::memory_order_acquire);
    for (int i = 0; i < FRAME_BUFFER_COUNT; ++i) {
        FrameBuffer *candidate = &g_buffers[i];
        if (candidate == currentReadBuffer ||
            g_reader_counts[i].load(std::memory_order_acquire) > 0) {
            continue;
        }

        int expected = FRAME_STATE_FREE;
        if (!g_buffer_states[i].compare_exchange_strong(expected, FRAME_STATE_WRITING,
                                                        std::memory_order_acq_rel)) {
            continue;
        }

        if (g_reader_counts[i].load(std::memory_order_acquire) > 0 ||
            g_read_buffer.load(std::memory_order_acquire) == candidate ||
            !g_frame_buffers_initialized.load(std::memory_order_acquire)) {
            g_buffer_states[i].store(FRAME_STATE_FREE, std::memory_order_release);
            continue;
        }
        return candidate;
    }
    return nullptr;
}

static const FrameBuffer *LockCurrentFrame() {
    if (!g_frame_buffers_initialized.load(std::memory_order_acquire)) {
        return nullptr;
    }

    for (int attempt = 0; attempt < 3; ++attempt) {
        FrameBuffer *frame = g_read_buffer.load(std::memory_order_acquire);
        if (!frame || frame->frame_count == 0) {
            return nullptr;
        }

        int idx = GetBufferIndex(frame);
        if (idx < 0) {
            return nullptr;
        }

        g_reader_counts[idx].fetch_add(1, std::memory_order_acquire);
        if (g_read_buffer.load(std::memory_order_acquire) != frame ||
            !g_frame_buffers_initialized.load(std::memory_order_acquire)) {
            g_reader_counts[idx].fetch_sub(1, std::memory_order_release);
            continue;
        }

        if (g_buffer_states[idx].load(std::memory_order_acquire) == FRAME_STATE_WRITING) {
            bool ready = false;
            for (int spin = 0; spin < 500; ++spin) {
                if (g_buffer_states[idx].load(std::memory_order_acquire) != FRAME_STATE_WRITING) {
                    ready = true;
                    break;
                }
            }
            if (!ready) {
                g_reader_counts[idx].fetch_sub(1, std::memory_order_release);
                return nullptr;
            }
        }

        return frame;
    }
    return nullptr;
}

static void UnlockFrame(const FrameBuffer *frame) {
    if (!frame) {
        return;
    }

    int idx = GetBufferIndex(const_cast<FrameBuffer *>(frame));
    if (idx >= 0) {
        g_reader_counts[idx].fetch_sub(1, std::memory_order_release);
    }
}

void InitFrameBuffers(int width, int height) {
    if (g_frame_buffers_initialized.load(std::memory_order_acquire)) {
        ReleaseFrameBuffers();
    }

    const size_t bgrSize = static_cast<size_t>(width) * height * 3;
    for (int i = 0; i < FRAME_BUFFER_COUNT; ++i) {
        FrameBuffer &buf = g_buffers[i];
        ReleaseBuffer(&buf);
        if (posix_memalign(reinterpret_cast<void **>(&buf.bgr_data), 64, bgrSize) != 0) {
            LOGE("InitFrameBuffers: posix_memalign failed at index=%d", i);
            for (int j = 0; j <= i; ++j) {
                ReleaseBuffer(&g_buffers[j]);
                g_buffer_states[j].store(FRAME_STATE_FREE, std::memory_order_release);
                g_reader_counts[j].store(0, std::memory_order_release);
            }
            g_read_buffer.store(nullptr, std::memory_order_release);
            g_frame_count.store(0, std::memory_order_release);
            return;
        }

        buf.index = i;
        buf.width = width;
        buf.height = height;
        buf.bgr_size = bgrSize;
        buf.frame_count = 0;
        g_buffer_states[i].store(FRAME_STATE_FREE, std::memory_order_release);
        g_reader_counts[i].store(0, std::memory_order_release);
    }

    g_read_buffer.store(nullptr, std::memory_order_release);
    g_frame_count.store(0, std::memory_order_release);
    g_frame_buffers_initialized.store(true, std::memory_order_release);
    LOGI("InitFrameBuffers: Success %dx%d", width, height);
}

void ReleaseFrameBuffers() {
    g_frame_buffers_initialized.store(false, std::memory_order_release);
    g_read_buffer.store(nullptr, std::memory_order_release);

    for (int i = 0; i < FRAME_BUFFER_COUNT; ++i) {
        while (g_buffer_states[i].load(std::memory_order_acquire) == FRAME_STATE_WRITING ||
               g_reader_counts[i].load(std::memory_order_acquire) > 0) {
            std::this_thread::yield();
        }

        ReleaseBuffer(&g_buffers[i]);
        g_buffer_states[i].store(FRAME_STATE_FREE, std::memory_order_release);
        g_reader_counts[i].store(0, std::memory_order_release);
    }

    g_read_buffer.store(nullptr, std::memory_order_release);
    g_frame_count.store(0, std::memory_order_release);
}

/**
 * 分析一帧的内容分布，判断有没有黑边、内容占了多大区域。
 *
 * ## 为什么需要
 *
 * 排查「画面出去了」这类问题时，只知道帧尺寸（1920×1080）是不够的 ——
 * **帧尺寸对、但内容只占一部分**才是真正的症状。
 *
 * 实测症状：荣耀设备上游戏卡在开屏，预览能看到 UI 但「点击进入」看不到，
 * 超时截图里全是星空背景。怀疑是游戏按物理屏比例（2.2）渲染了
 * 1920×872 的内容，剩下 208 行是空的。
 *
 * ## 统计方式
 *
 * 逐行采样（每 4 行取一行，避免太慢），统计这行的平均亮度：
 * - 平均亮度 < 阈值 → 认为是"黑行"（空白/黑边）
 * - 记录第一个和最后一个非黑行的 y 坐标
 *
 * 输出形如：
 * ```
 * 内容边界: 非黑行 0..871（共 872 行），下方黑行 208 行，内容占比 80.7%
 * ```
 */
static void AnalyzeFrameContent(const uint8_t *bgr, int width, int height) {
    if (!bgr || width <= 0 || height <= 0) return;

    const int DARK_THRESHOLD = 24;  // 平均亮度低于此值视为黑行
    int firstNonDark = -1;
    int lastNonDark = -1;
    int darkRows = 0;

    for (int y = 0; y < height; y += 4) {
        const uint8_t *row = bgr + static_cast<size_t>(y) * width * 3;
        long sum = 0;
        // 横向也采样，每 8 个像素取一个
        int samples = 0;
        for (int x = 0; x < width; x += 8) {
            const uint8_t *px = row + x * 3;
            sum += px[0] + px[1] + px[2];
            samples++;
        }
        int avg = samples > 0 ? static_cast<int>(sum / (samples * 3)) : 0;
        if (avg < DARK_THRESHOLD) {
            darkRows++;
        } else {
            if (firstNonDark < 0) firstNonDark = y;
            lastNonDark = y;
        }
    }

    if (firstNonDark < 0) {
        LOGE("内容分析: 整帧全黑（%dx%d）", width, height);
        return;
    }

    int contentRows = lastNonDark - firstNonDark + 1;
    float ratio = 100.0f * contentRows / height;
    LOGI(
        "内容分析: 非黑行 %d..%d（共 %d 行），黑行 %d，内容占比 %.1f%% (帧 %dx%d)",
        firstNonDark, lastNonDark, contentRows, darkRows, ratio, width, height);

    // 内容明显少于帧高 → 下方或上方有大片空白，这就是"画面出去了"
    if (ratio < 88.0f) {
        LOGE(
            "内容占比仅 %.1f%%！画面没有铺满，游戏可能按物理屏比例渲染了。"
            "若下方有黑边，说明「点击进入」被切在画面外",
            ratio);
    }
}

bool WriteHardwareBufferToFrame(AHardwareBuffer *buffer) {

    FrameBuffer *target = AcquireWriteBuffer();
    if (!target) {
        return false;
    }

    void *srcAddr = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr,
                             &srcAddr) != 0) {
        MarkBufferFree(target);
        return false;
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    // ## ⚠️ 尺寸不一致时必须报出来
    //
    // `ProcessFrameDataV2` 是按**目标宽高**去读源的，它假定：
    //
    // ```cpp
    // for (int y = 0; y < height; ++y)         // height = target->height
    //     const uint8_t *s = src + y * src_stride;
    //     for (int x = 0; x < width; ++x)      // width  = target->width
    // ```
    //
    // 一旦 `desc` 和 `target` 不等，就会按错误的行列数读：
    //   · desc 比 target 小 → 读到未初始化内存，下半屏全是脏数据
    //   · desc 比 target 大 → 只读了左上角，**UI 被切在外面**
    //   · 宽高比不同       → 画面横向压扁
    //
    // 实测踩到过（荣耀物理屏 2.2 比例）：虚拟屏声明 1920×1080，
    // 硬件 buffer 却是 1920×872 —— 引擎读到 872 行之后全是垃圾，
    // OCR 只看得到一个 `U`，任务永远卡在「点击进入」。
    //
    // 而预览那条路用 `eglCreateImageKHR` + GL 采样，天然支持任意尺寸，
    // 所以会出现「预览看得见、引擎认不出」的矛盾现象。
    static std::atomic<bool> mismatch_logged{false};
    const bool sizeMatches =
            static_cast<int>(desc.width) == target->width &&
            static_cast<int>(desc.height) == target->height;

    if (!sizeMatches) {
        if (!mismatch_logged.exchange(true, std::memory_order_acq_rel)) {
            LOGE(
                "FrameBuffer 尺寸不匹配！buffer=%ux%u stride=%u, target=%dx%d "
                "—— 将按实际尺寸读取并缩放到目标尺寸（画面会轻微变形，但内容完整）",
                desc.width, desc.height, desc.stride, target->width, target->height);
        }
        // **按实际尺寸读 + 缩放到目标尺寸**
        // 这一步是修复的核心：保证读源时不越界，且目标画面完整
        ProcessFrameDataScaled(
                static_cast<const uint8_t *>(srcAddr), target->bgr_data,
                static_cast<int>(desc.width), static_cast<int>(desc.height),
                static_cast<int>(desc.stride) * 4,
                target->width, target->height);
    } else {
        // 正常路径：零拷贝快速版本
        ProcessFrameDataV2(static_cast<uint8_t *>(srcAddr), target->bgr_data, target->width,
                           target->height, static_cast<int>(desc.stride) * 4);
    }
    AHardwareBuffer_unlock(buffer, nullptr);

    // ## 内容边界诊断（仅前几帧，避免拖慢）
    //
    // 排查「画面出去了」这类问题时，光是知道帧尺寸不够 ——
    // 还需要知道**内容实际占了画面多少**。
    //
    // 这里扫描每行的亮度，统计：
    //   · 全黑行数（上下黑边）
    //   · 首个非黑行 / 最后非黑行（内容边界）
    //
    // 如果内容只占上半部分（比如 872/1080），就说明游戏按物理屏
    // 比例（荣耀 2.2）渲染，下半是空白 —— 正是「点击进入被切掉」的原因。
    static std::atomic<int> analyzed{0};
    if (analyzed.fetch_add(1, std::memory_order_acq_rel) < 3) {
        AnalyzeFrameContent(target->bgr_data, target->width, target->height);
    }

    target->frame_count = g_frame_count.fetch_add(1, std::memory_order_acq_rel) + 1;
    CommitWriteBuffer(target);
    return true;
}

int64_t GetFrameCount() {
    return g_frame_count.load(std::memory_order_acquire);
}

BRIDGE_API FrameInfo GetLockedPixels() {
    FrameInfo result = {0};
    const FrameBuffer *frame = LockCurrentFrame();
    if (!frame) {
        return result;
    }

    if (!frame->bgr_data) {
        UnlockFrame(frame);
        return result;
    }

    result.width = frame->width;
    result.height = frame->height;
    result.stride = frame->width * 3;
    result.length = static_cast<uint32_t>(frame->bgr_size);
    result.data = frame->bgr_data;
    result.frame_ref = const_cast<FrameBuffer *>(frame);
    return result;
}

BRIDGE_API int UnlockPixels(FrameInfo info) {
    if (info.frame_ref) {
        UnlockFrame(reinterpret_cast<const FrameBuffer *>(info.frame_ref));
    }
    return 0;
}

static jobject BuildArgb8888BitmapFromBgr(JNIEnv *env, const uint8_t *bgr, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapClass || !configClass) {
        env->ExceptionClear();
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(
            configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID createBitmapMethod = env->GetStaticMethodID(
            bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!argb8888Field || !createBitmapMethod) {
        env->ExceptionClear();
        return nullptr;
    }

    jobject argb8888Config = env->GetStaticObjectField(configClass, argb8888Field);
    if (!argb8888Config) {
        env->ExceptionClear();
        return nullptr;
    }

    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, width, height,
                                                 argb8888Config);
    if (env->ExceptionCheck()) {
        // createBitmap 可能因 OOM 抛异常，清除后按失败处理
        env->ExceptionClear();
        return nullptr;
    }
    if (!bitmap) {
        return nullptr;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        !pixels) {
        return nullptr;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return nullptr;
    }

    uint8_t *dst = static_cast<uint8_t *>(pixels);
    const uint8_t *src = bgr;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            dst[x * 4 + 0] = src[x * 3 + 2];
            dst[x * 4 + 1] = src[x * 3 + 1];
            dst[x * 4 + 2] = src[x * 3 + 0];
            dst[x * 4 + 3] = 255;
        }
        dst += info.stride;
        src += static_cast<size_t>(width) * 3;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

jobject CreateFrameBufferBitmap(JNIEnv *env) {
    FrameInfo frame = GetLockedPixels();
    if (!frame.data || frame.width == 0 || frame.height == 0 || frame.length == 0) {
        UnlockPixels(frame);
        return nullptr;
    }

    // 拷出 BGR 数据后立即解锁，把持锁时间压到一次 memcpy，避免和采集 / MAA core 抢缓冲区
    auto *bgrCopy = static_cast<uint8_t *>(malloc(frame.length));
    if (!bgrCopy) {
        UnlockPixels(frame);
        return nullptr;
    }
    memcpy(bgrCopy, frame.data, frame.length);
    const int width = static_cast<int>(frame.width);
    const int height = static_cast<int>(frame.height);
    UnlockPixels(frame);

    // 此时已不持有帧锁，bitmap 分配/转换不再阻塞采集
    jobject bitmap = BuildArgb8888BitmapFromBgr(env, bgrCopy, width, height);
    free(bgrCopy);
    return bitmap;
}
