#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <atomic>
#include <cstdarg>
#include <pthread.h>
#include <chrono>
#include <algorithm>
#include <cerrno>
#include <cstring>

#include <android/log.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include <linux/videodev2.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaMuxer.h>
#include <media/NdkMediaFormat.h>

static constexpr const char* TAG = "CameraMp4Record";

static void logi(const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, args);
    va_end(args);
}
static void logw(const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, TAG, fmt, args);
    va_end(args);
}
static void loge(const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, TAG, fmt, args);
    va_end(args);
}

static std::string errnoStr() {
    // NDK'de strerrno/strerror var; burada sadece temel format.
    int e = errno;
    const char* s = strerror(errno);
    std::ostringstream oss;
    oss << e << " (" << (s ? s : "?") << ")";
    return oss.str();
}

static void uyvyToI420(const uint8_t* src,
                        uint8_t* dstY, uint8_t* dstU, uint8_t* dstV,
                        int width, int height,
                        int srcStrideBytes) {
    // width: 720, height: 240, U/V is derived from 2-pixel groups in UYVY.
    // We only fill planar I420: Y(w*h), U(w*h/4), V(w*h/4).
    for (int y = 0; y < height; y++) {
        const uint8_t* line = src + y * srcStrideBytes;
        uint8_t* yOut = dstY + y * width;
        if ((y & 1) == 0) {
            // For even rows, we write U/V for the corresponding subsampled row.
            uint8_t* uOut = dstU + (y / 2) * (width / 2);
            uint8_t* vOut = dstV + (y / 2) * (width / 2);

            for (int x = 0, uvx = 0; x < width; x += 2, uvx++) {
                int u = line[0];   // already 0..255 with center ~128
                int y0 = line[1];
                int v = line[2];
                int y1 = line[3];

                yOut[x] = (uint8_t)y0;
                yOut[x + 1] = (uint8_t)y1;

                uOut[uvx] = (uint8_t)u;
                vOut[uvx] = (uint8_t)v;

                line += 4;
            }
        } else {
            // odd rows: Y only
            for (int x = 0; x < width; x += 2) {
                // U/Y/V/Y for 2 pixels
                int /*u*/u = line[0];
                int y0 = line[1];
                int /*v*/v = line[2];
                int y1 = line[3];

                (void)u; (void)v;
                yOut[x] = (uint8_t)y0;
                yOut[x + 1] = (uint8_t)y1;
                line += 4;
            }
        }
    }
}

struct RecTask {
    int slot = -1;
    int videoIndex = -1;
    int outW = 720;
    int outH = 240;
    int fps = 15;
    int bitrate = 2500000;
    std::string outputPath;
};

struct SlotState {
    std::atomic<bool> running{false};
    pthread_t thread{};
    RecTask* task = nullptr;
};

static SlotState gSlots[4];

static void* recordThread(void* arg) {
    RecTask* task = static_cast<RecTask*>(arg);
    if (!task) return nullptr;

    // Open v4l2
    std::string devPath = "/dev/video" + std::to_string(task->videoIndex);
    int fd = open(devPath.c_str(), O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        loge("slot=%d open %s failed: %s", task->slot, devPath.c_str(), errnoStr().c_str());
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    // Query fmt
    v4l2_format fmt{};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd, VIDIOC_G_FMT, &fmt) < 0) {
        loge("slot=%d VIDIOC_G_FMT failed: %s", task->slot, errnoStr().c_str());
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    const int srcW = fmt.fmt.pix.width;
    const int srcH = fmt.fmt.pix.height;
    const int srcStride = fmt.fmt.pix.bytesperline;
    logi("slot=%d %s src=%dx%d stride=%d", task->slot, devPath.c_str(), srcW, srcH, srcStride);

    // For our preview convention: we take the top half only.
    const int recW = std::min(task->outW, srcW);
    const int recH = std::min(task->outH, srcH / 2);
    if (recH <= 0 || recW <= 0 || (recW % 2) != 0) {
        loge("slot=%d invalid output size %dx%d", task->slot, recW, recH);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    // Setup v4l2 buffers
    v4l2_requestbuffers req{};
    req.count = 2;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0 || req.count < 1) {
        logw("slot=%d VIDIOC_REQBUFS failed", task->slot);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    struct Buffer { void* start = nullptr; size_t length = 0; };
    std::vector<Buffer> buffers(req.count);

    for (unsigned i = 0; i < req.count; i++) {
        v4l2_buffer buf{};
        buf.type = req.type;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (ioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
            logw("slot=%d VIDIOC_QUERYBUF failed", task->slot);
            close(fd);
            gSlots[task->slot].running = false;
            delete task;
            return nullptr;
        }
        buffers[i].length = buf.length;
        buffers[i].start = mmap(nullptr, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, buf.m.offset);
        if (buffers[i].start == MAP_FAILED) {
            logw("slot=%d mmap failed", task->slot);
            close(fd);
            gSlots[task->slot].running = false;
            delete task;
            return nullptr;
        }
        if (ioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            logw("slot=%d VIDIOC_QBUF failed", task->slot);
            close(fd);
            gSlots[task->slot].running = false;
            delete task;
            return nullptr;
        }
    }

    v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        logw("slot=%d VIDIOC_STREAMON failed", task->slot);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    // Allocate I420
    size_t ySize = (size_t)recW * (size_t)recH;
    size_t uvW = (size_t)(recW / 2);
    size_t uvH = (size_t)(recH / 2);
    size_t uvSize = uvW * uvH;

    std::vector<uint8_t> i420(ySize + uvSize * 2);
    uint8_t* yPlane = i420.data();
    uint8_t* uPlane = yPlane + ySize;
    uint8_t* vPlane = uPlane + uvSize;

    // Create codec
    AMediaCodec* codec = AMediaCodec_createEncoderByType("video/avc");
    if (!codec) {
        loge("slot=%d AMediaCodec_createEncoderByType failed", task->slot);
        ioctl(fd, VIDIOC_STREAMOFF, &type);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, recW);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, recH);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, task->bitrate);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, task->fps);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1); // 1 sec

    // COLOR_FormatYUV420Planar = 0x13 (19)
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 19);

    media_status_t st = AMediaCodec_configure(codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    if (st != AMEDIA_OK) {
        loge("slot=%d AMediaCodec_configure failed: %d", task->slot, (int)st);
        AMediaCodec_delete(codec);
        ioctl(fd, VIDIOC_STREAMOFF, &type);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    st = AMediaCodec_start(codec);
    if (st != AMEDIA_OK) {
        loge("slot=%d AMediaCodec_start failed: %d", task->slot, (int)st);
        AMediaCodec_delete(codec);
        ioctl(fd, VIDIOC_STREAMOFF, &type);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    // Muxer: uses NDK file descriptor. We'll open file fd ourselves.
    // AMediaMuxer_new(int fd, OutputFormat) expects a file descriptor.
    int outFd = open(task->outputPath.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
    if (outFd < 0) {
        loge("slot=%d open output file failed: %s", task->slot, errnoStr().c_str());
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
        ioctl(fd, VIDIOC_STREAMOFF, &type);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    AMediaMuxer* muxer = AMediaMuxer_new(outFd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    if (!muxer) {
        loge("slot=%d AMediaMuxer_new failed", task->slot);
        close(outFd);
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
        ioctl(fd, VIDIOC_STREAMOFF, &type);
        close(fd);
        gSlots[task->slot].running = false;
        delete task;
        return nullptr;
    }

    ssize_t trackIndex = -1;
    bool muxerStarted = false;
    bool outputDone = false;

    // Fixed PTS throttling to target fps.
    const int64_t frameDurUs = 1000000LL / task->fps;
    int64_t nextPtsUs = 0;
    bool firstPtsInit = false;
    int64_t frameOutCount = 0;

    auto nowUs = []() -> int64_t {
        using namespace std::chrono;
        return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
    };

    int64_t startUs = nowUs();
    (void)startUs;

    // Encoding frame loop
    while (gSlots[task->slot].running) {
        v4l2_buffer buf{};
        buf.type = type;
        buf.memory = V4L2_MEMORY_MMAP;

        if (ioctl(fd, VIDIOC_DQBUF, &buf) < 0) {
            continue;
        }

        uint8_t* srcFrame = static_cast<uint8_t*>(buffers[buf.index].start);

        // Initialize PTS on first encoded frame
        int64_t now = nowUs();
        if (!firstPtsInit) {
            firstPtsInit = true;
            nextPtsUs = 0;
        }

        // Drop frames if we're ahead of schedule
        int64_t elapsedUs = now - startUs;
        if (elapsedUs >= nextPtsUs) {
            // Convert ONLY top half (recH lines)
            // UYVY pointer for top half starts at srcFrame (y=0).
            uyvyToI420(srcFrame, yPlane, uPlane, vPlane, recW, recH, srcStride);

            // Encode: queue input buffer
            ssize_t inIndex = AMediaCodec_dequeueInputBuffer(codec, 10000);
            if (inIndex >= 0) {
                size_t inSize = 0;
                uint8_t* inBuf = AMediaCodec_getInputBuffer(codec, (size_t)inIndex, &inSize);
                if (inBuf && inSize >= (ySize + uvSize * 2)) {
                    memcpy(inBuf, i420.data(), ySize + uvSize * 2);
                    int64_t pts = frameOutCount * frameDurUs;
                    uint32_t flags = 0;
                    if (AMediaCodec_queueInputBuffer(codec, (size_t)inIndex, 0,
                                                     ySize + uvSize * 2, (uint64_t)pts, flags) != AMEDIA_OK) {
                        logw("slot=%d queueInputBuffer failed", task->slot);
                    } else {
                        frameOutCount++;
                    }
                } else {
                    logw("slot=%d input buffer too small or null", task->slot);
                }
            }

            nextPtsUs += frameDurUs;
        }

        // Drain outputs
        while (true) {
            AMediaCodecBufferInfo info{};
            ssize_t outIndex = AMediaCodec_dequeueOutputBuffer(codec, &info, 0);
            if (outIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) break;

            if (outIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) continue;
                AMediaFormat* outFormat = AMediaCodec_getOutputFormat(codec);
                trackIndex = AMediaMuxer_addTrack(muxer, outFormat);
                AMediaFormat_delete(outFormat);
                if (trackIndex >= 0) {
                    AMediaMuxer_start(muxer);
                    muxerStarted = true;
                }
                continue;
            }

            if (outIndex < 0) {
                break;
            }

            if (info.size > 0 && muxerStarted) {
                size_t outSize = 0;
                uint8_t* outData = AMediaCodec_getOutputBuffer(codec, (size_t)outIndex, &outSize);
                // outData points to start; info.offset tells where real bytes start inside.
                // AMediaMuxer_writeSampleData uses info.offset/size.
                if (outData && info.size > 0) {
                    AMediaMuxer_writeSampleData(muxer, (size_t)trackIndex, outData, &info);
                }
            }

            bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
            AMediaCodec_releaseOutputBuffer(codec, (size_t)outIndex, false);
            if (isEos) {
                outputDone = true;
                break;
            }
        }

        // Requeue
        if (ioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            // ignore
        }
    }

    // Signal end-of-stream to encoder
    ssize_t inIndex = AMediaCodec_dequeueInputBuffer(codec, 10000);
    if (inIndex >= 0) {
        AMediaCodec_queueInputBuffer(codec, (size_t)inIndex, 0, 0,
                                       (uint64_t)(frameOutCount * frameDurUs),
                                       AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
    }

    // Drain until EOS
    while (!outputDone) {
        AMediaCodecBufferInfo info{};
        ssize_t outIndex = AMediaCodec_dequeueOutputBuffer(codec, &info, 10000);
        if (outIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) continue;
        if (outIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) continue;
        if (outIndex < 0) break;

        if (info.size > 0 && muxerStarted) {
            size_t outSize = 0;
            uint8_t* outData = AMediaCodec_getOutputBuffer(codec, (size_t)outIndex, &outSize);
            if (outData && info.size > 0) {
                AMediaMuxer_writeSampleData(muxer, (size_t)trackIndex, outData, &info);
            }
        }

        bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
        AMediaCodec_releaseOutputBuffer(codec, (size_t)outIndex, false);
        if (isEos) outputDone = true;
    }

    if (muxerStarted) {
        AMediaMuxer_stop(muxer);
    }
    AMediaMuxer_delete(muxer);
    if (outFd >= 0) close(outFd);

    AMediaCodec_stop(codec);
    AMediaCodec_delete(codec);

    // Stop v4l2
    ioctl(fd, VIDIOC_STREAMOFF, &type);

    for (unsigned i = 0; i < buffers.size(); i++) {
        if (buffers[i].start && buffers[i].start != MAP_FAILED) {
            munmap(buffers[i].start, buffers[i].length);
        }
    }
    close(fd);

    gSlots[task->slot].running = false;
    delete task;
    return nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_startMp4Record(JNIEnv* env, jclass /*clazz*/,
                                                     jint slot,
                                                     jint videoIndex,
                                                     jstring outputPath,
                                                     jint width,
                                                     jint height,
                                                     jint fps,
                                                     jint bitrate) {
    int s = (int)slot;
    if (s < 0 || s >= 4) return JNI_FALSE;
    if (gSlots[s].running.load()) return JNI_FALSE;
    if (!outputPath) return JNI_FALSE;

    const char* outC = env->GetStringUTFChars(outputPath, nullptr);
    if (!outC) return JNI_FALSE;
    std::string outPath(outC);
    env->ReleaseStringUTFChars(outputPath, outC);

    RecTask* task = new RecTask();
    task->slot = s;
    task->videoIndex = (int)videoIndex;
    task->outW = (int)width;
    task->outH = (int)height;
    task->fps = (int)fps;
    task->bitrate = (int)bitrate;
    task->outputPath = outPath;

    gSlots[s].running = true;
    int rc = pthread_create(&gSlots[s].thread, nullptr, recordThread, task);
    if (rc != 0) {
        gSlots[s].running = false;
        delete task;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_stopMp4Record(JNIEnv* /*env*/, jclass /*clazz*/, jint slot) {
    int s = (int)slot;
    if (s < 0 || s >= 4) return;
    if (!gSlots[s].running.load()) return;

    gSlots[s].running = false;
    // join
    pthread_t t = gSlots[s].thread;
    if (t) {
        pthread_join(t, nullptr);
        gSlots[s].thread = 0;
    }
}

