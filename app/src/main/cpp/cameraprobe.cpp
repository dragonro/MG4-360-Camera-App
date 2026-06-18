// Updated: AdrianBega/DualBytes
#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <cerrno>
#include <cstring>
#include <algorithm>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include <android/log.h>
#include <android/native_window_jni.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <pthread.h>

#include <linux/videodev2.h>

static constexpr const char *TAG = "CameraProbeOnly";

static void logi(const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, args);
    va_end(args);
}
static void logw(const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, TAG, fmt, args);
    va_end(args);
}

static std::string errnoStr()
{
    std::ostringstream oss;
    oss << errno << " (" << std::strerror(errno) << ")";
    return oss.str();
}

static std::string fourccToStr(__u32 f)
{
    char s[5];
    s[0] = static_cast<char>(f & 0xFF);
    s[1] = static_cast<char>((f >> 8) & 0xFF);
    s[2] = static_cast<char>((f >> 16) & 0xFF);
    s[3] = static_cast<char>((f >> 24) & 0xFF);
    s[4] = '\0';
    return std::string(s);
}

static std::string probeOne(int fd)
{
    v4l2_capability cap{};
    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) != 0)
    {
        return "VIDIOC_QUERYCAP failed: " + errnoStr();
    }

    std::ostringstream oss;
    oss << "OK driver=" << cap.driver
        << " card=" << cap.card
        << " bus=" << cap.bus_info
        << " ver=" << cap.version;

    __u32 caps = cap.capabilities;
    oss << " caps=0x" << std::hex << caps << std::dec;

    bool hasVideoCapture = (caps & V4L2_CAP_VIDEO_CAPTURE) != 0;
    bool hasVideoOutput = (caps & V4L2_CAP_VIDEO_OUTPUT) != 0;
    bool hasMplaneCapture = (caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE) != 0;
    bool hasMplaneOutput = (caps & V4L2_CAP_VIDEO_OUTPUT_MPLANE) != 0;

    oss << " [";
    if (hasVideoCapture)
        oss << "CAPTURE ";
    if (hasVideoOutput)
        oss << "OUTPUT ";
    if (hasMplaneCapture)
        oss << "CAPTURE_MPLANE ";
    if (hasMplaneOutput)
        oss << "OUTPUT_MPLANE ";
    oss << "]";

    std::vector<std::string> fmts;
    v4l2_fmtdesc fdesc{};
    fdesc.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    for (fdesc.index = 0;; fdesc.index++)
    {
        if (ioctl(fd, VIDIOC_ENUM_FMT, &fdesc) != 0)
            break;
        fmts.push_back(fourccToStr(fdesc.pixelformat));
    }
    if (!fmts.empty())
    {
        oss << " fmts=[";
        for (size_t i = 0; i < fmts.size(); i++)
        {
            if (i)
                oss << ",";
            oss << fmts[i];
        }
        oss << "]";
    }
    return oss.str();
}

// ---- Preview state ----
static int g_fd = -1;
static std::vector<ANativeWindow *> g_windows;
static pthread_mutex_t g_windowsMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_thread = 0;
static volatile bool g_running = false;

static int g_width = 0;
static int g_height = 0;
static int g_srcStrideBytes = 0;
static int g_videoIndex = -1;
static volatile int g_processingMode = 0;
static cv::Mat g_undistortMapX;
static cv::Mat g_undistortMapY;
static int g_undistortMapWidth = 0;
static int g_undistortMapHeight = 0;
static pthread_mutex_t g_processingMutex = PTHREAD_MUTEX_INITIALIZER;

static void releaseAllPreviewWindowsLocked();

static void stopPreviewInternal()
{
    if (!g_running)
        return;
    g_running = false;
    if (g_thread)
    {
        pthread_join(g_thread, nullptr);
        g_thread = 0;
    }
    pthread_mutex_lock(&g_windowsMutex);
    releaseAllPreviewWindowsLocked();
    pthread_mutex_unlock(&g_windowsMutex);
    if (g_fd >= 0)
    {
        close(g_fd);
        g_fd = -1;
    }
    g_videoIndex = -1;
}

static void releaseAllPreviewWindowsLocked()
{
    for (ANativeWindow *window : g_windows)
    {
        if (window)
        {
            ANativeWindow_release(window);
        }
    }
    g_windows.clear();
}

static bool addPreviewWindow(JNIEnv *env, jobject surface)
{
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window)
    {
        logw("ANativeWindow_fromSurface failed");
        return false;
    }

    int displayWidth = g_width;
    int displayHeight = g_height / 2;
    ANativeWindow_setBuffersGeometry(window,
                                     displayWidth,
                                     displayHeight,
                                     AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

    pthread_mutex_lock(&g_windowsMutex);
    for (ANativeWindow *existing : g_windows)
    {
        if (existing == window)
        {
            pthread_mutex_unlock(&g_windowsMutex);
            ANativeWindow_release(window);
            return true;
        }
    }
    g_windows.push_back(window);
    pthread_mutex_unlock(&g_windowsMutex);
    return true;
}

static bool removePreviewWindow(JNIEnv *env, jobject surface)
{
    if (!surface)
    {
        return false;
    }

    ANativeWindow *target = ANativeWindow_fromSurface(env, surface);
    if (!target)
    {
        return false;
    }

    bool removed = false;
    pthread_mutex_lock(&g_windowsMutex);
    for (auto it = g_windows.begin(); it != g_windows.end(); ++it)
    {
        if (*it == target)
        {
            ANativeWindow_release(*it);
            g_windows.erase(it);
            removed = true;
            break;
        }
    }
    pthread_mutex_unlock(&g_windowsMutex);

    ANativeWindow_release(target);
    return removed;
}

static void clearUndistortMapsLocked()
{
    g_undistortMapX.release();
    g_undistortMapY.release();
    g_undistortMapWidth = 0;
    g_undistortMapHeight = 0;
}

static void buildUndistortMapsLocked(int width, int height)
{
    if (width <= 0 || height <= 0)
        return;
    if (g_undistortMapWidth == width && g_undistortMapHeight == height &&
        !g_undistortMapX.empty() && !g_undistortMapY.empty())
    {
        return;
    }

    g_undistortMapX.create(height, width, CV_32FC1);
    g_undistortMapY.create(height, width, CV_32FC1);
    g_undistortMapWidth = width;
    g_undistortMapHeight = height;

    const float cx = (width - 1) * 0.5f;
    const float cy = (height - 1) * 0.5f;
    const float scale = std::min(cx, cy);
    const float k1 = -0.26f;
    const float k2 = 0.06f;

    for (int y = 0; y < height; ++y)
    {
        float *mapX = g_undistortMapX.ptr<float>(y);
        float *mapY = g_undistortMapY.ptr<float>(y);
        const float yn = (y - cy) / scale;
        for (int x = 0; x < width; ++x)
        {
            const float xn = (x - cx) / scale;
            const float r2 = xn * xn + yn * yn;
            const float radial = 1.0f + k1 * r2 + k2 * r2 * r2;
            mapX[x] = cx + xn * radial * scale;
            mapY[x] = cy + yn * radial * scale;
        }
    }
}

static void applyUndistortionIfNeeded(cv::Mat &rgbaFrame)
{
    if (g_processingMode != 1 || rgbaFrame.empty())
        return;

    cv::Mat mapX;
    cv::Mat mapY;
    pthread_mutex_lock(&g_processingMutex);
    buildUndistortMapsLocked(rgbaFrame.cols, rgbaFrame.rows);
    mapX = g_undistortMapX;
    mapY = g_undistortMapY;
    pthread_mutex_unlock(&g_processingMutex);

    if (mapX.empty() || mapY.empty())
        return;

    cv::Mat undistorted;
    cv::remap(rgbaFrame, undistorted, mapX, mapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0, 255));
    rgbaFrame = undistorted;
}

static void *previewThread(void * /*arg*/)
{
    v4l2_requestbuffers req{};
    req.count = 4;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (ioctl(g_fd, VIDIOC_REQBUFS, &req) < 0 || req.count < 1)
    {
        logw("VIDIOC_REQBUFS failed");
        g_running = false;
        return nullptr;
    }

    struct Buffer
    {
        void *start;
        size_t length;
    };
    Buffer buffers[4]{};

    for (unsigned i = 0; i < req.count; i++)
    {
        v4l2_buffer buf{};
        buf.type = req.type;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (ioctl(g_fd, VIDIOC_QUERYBUF, &buf) < 0)
        {
            g_running = false;
            return nullptr;
        }
        buffers[i].length = buf.length;
        buffers[i].start = mmap(nullptr, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, g_fd, buf.m.offset);
        if (buffers[i].start == MAP_FAILED)
        {
            g_running = false;
            return nullptr;
        }
        if (ioctl(g_fd, VIDIOC_QBUF, &buf) < 0)
        {
            g_running = false;
            return nullptr;
        }
    }

    v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(g_fd, VIDIOC_STREAMON, &type) < 0)
    {
        logw("VIDIOC_STREAMON failed");
        g_running = false;
        return nullptr;
    }

    while (g_running)
    {
        // Poll before DQBUF to avoid busy-waiting when the driver stalls.
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(g_fd, &fds);
        timeval tv{0, 100000}; // 100ms timeout
        if (select(g_fd + 1, &fds, nullptr, nullptr, &tv) <= 0)
            continue;

        v4l2_buffer buf{};
        buf.type = type;
        buf.memory = V4L2_MEMORY_MMAP;
        if (ioctl(g_fd, VIDIOC_DQBUF, &buf) < 0)
        {
            continue;
        }

        int displayWidth = g_width;
        int displayHeight = g_height / 2; // The driver duplicates the frame vertically, so use the top half.

        cv::Mat uyvyFrame(g_height, g_width, CV_8UC2,
                          buffers[buf.index].start, g_srcStrideBytes);
        cv::Mat uyvyCropped = uyvyFrame(cv::Rect(0, 0, displayWidth, displayHeight));
        cv::Mat rgbaFrame(displayHeight, displayWidth, CV_8UC4);
        cv::cvtColor(uyvyCropped, rgbaFrame, cv::COLOR_YUV2RGBA_UYVY);

        // Mirror the rear camera (videoIndex 17)
        if (g_videoIndex == 17)
        {
            cv::flip(rgbaFrame, rgbaFrame, 1);
        }

        applyUndistortionIfNeeded(rgbaFrame);

        std::vector<ANativeWindow *> windows;
        pthread_mutex_lock(&g_windowsMutex);
        windows = g_windows;
        for (ANativeWindow *window : windows)
        {
            ANativeWindow_acquire(window);
        }
        pthread_mutex_unlock(&g_windowsMutex);

        for (ANativeWindow *window : windows)
        {
            ANativeWindow_Buffer outBuf{};
            if (window && ANativeWindow_lock(window, &outBuf, nullptr) == 0)
            {
                uint8_t *dst = static_cast<uint8_t *>(outBuf.bits);
                int dstStrideBytes = outBuf.stride * 4;
                cv::Mat outFrame(displayHeight, displayWidth, CV_8UC4, dst, dstStrideBytes);
                rgbaFrame.copyTo(outFrame);
                ANativeWindow_unlockAndPost(window);
            }
            ANativeWindow_release(window);
        }

        ioctl(g_fd, VIDIOC_QBUF, &buf);
    }

    ioctl(g_fd, VIDIOC_STREAMOFF, &type);
    for (unsigned i = 0; i < req.count; i++)
    {
        if (buffers[i].start && buffers[i].start != MAP_FAILED)
        {
            munmap(buffers[i].start, buffers[i].length);
        }
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_drivehub_kamera_CameraProbe_probeAll(JNIEnv *env, jclass, jint maxIndex)
{
    if (maxIndex <= 0)
        maxIndex = 4;
    if (maxIndex > 32)
        maxIndex = 32;

    std::ostringstream summary;
    summary << "Probe /dev/video0.." << (maxIndex - 1) << "\n";

    for (int i = 0; i < maxIndex; i++)
    {
        std::string path = "/dev/video" + std::to_string(i);
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0)
        {
            std::string msg = path + " open FAILED: " + errnoStr();
            logw("%s", msg.c_str());
            summary << msg << "\n";
            continue;
        }

        std::string result = probeOne(fd);
        close(fd);

        std::string msg = path + " " + result;
        logi("%s", msg.c_str());
        summary << msg << "\n";
    }

    return env->NewStringUTF(summary.str().c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_startPreview(JNIEnv *env, jclass, jint videoIndex, jobject surface)
{
    if (g_running)
    {
        if (videoIndex != g_videoIndex)
        {
            logw("Preview already running for video%d, refusing video%d", g_videoIndex, videoIndex);
            return JNI_FALSE;
        }
        return addPreviewWindow(env, surface) ? JNI_TRUE : JNI_FALSE;
    }

    std::string path = "/dev/video" + std::to_string(videoIndex);
    g_fd = open(path.c_str(), O_RDWR | O_CLOEXEC);
    if (g_fd < 0)
    {
        logw("open %s failed: %s", path.c_str(), errnoStr().c_str());
        return JNI_FALSE;
    }

    v4l2_format fmt{};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(g_fd, VIDIOC_G_FMT, &fmt) < 0)
    {
        logw("VIDIOC_G_FMT failed: %s", errnoStr().c_str());
        close(g_fd);
        g_fd = -1;
        return JNI_FALSE;
    }
    g_width = fmt.fmt.pix.width;
    g_height = fmt.fmt.pix.height;
    g_srcStrideBytes = fmt.fmt.pix.bytesperline;
    g_videoIndex = videoIndex;
    logi("Using size %dx%d stride=%d", g_width, g_height, g_srcStrideBytes);

    pthread_mutex_lock(&g_windowsMutex);
    releaseAllPreviewWindowsLocked();
    pthread_mutex_unlock(&g_windowsMutex);

    if (!addPreviewWindow(env, surface))
    {
        close(g_fd);
        g_fd = -1;
        return JNI_FALSE;
    }

    g_running = true;
    if (pthread_create(&g_thread, nullptr, previewThread, nullptr) != 0)
    {
        logw("pthread_create failed");
        g_running = false;
        pthread_mutex_lock(&g_windowsMutex);
        releaseAllPreviewWindowsLocked();
        pthread_mutex_unlock(&g_windowsMutex);
        close(g_fd);
        g_fd = -1;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_stopPreviewSurface(JNIEnv *env, jclass /*clazz*/, jobject surface)
{
    if (!g_running)
        return;

    removePreviewWindow(env, surface);

    pthread_mutex_lock(&g_windowsMutex);
    bool hasWindows = !g_windows.empty();
    pthread_mutex_unlock(&g_windowsMutex);

    if (!hasWindows)
    {
        stopPreviewInternal();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_stopPreview(JNIEnv * /*env*/, jclass /*clazz*/)
{
    stopPreviewInternal();
}

extern "C" JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_setProcessingMode(JNIEnv * /*env*/, jclass /*clazz*/, jint mode)
{
    int normalized = mode == 1 ? 1 : 0;
    pthread_mutex_lock(&g_processingMutex);
    if (g_processingMode != normalized)
    {
        g_processingMode = normalized;
        clearUndistortMapsLocked();
        logi("Processing mode set to %d", normalized);
    }
    pthread_mutex_unlock(&g_processingMutex);
}
