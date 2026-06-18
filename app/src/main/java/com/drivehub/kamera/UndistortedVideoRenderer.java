// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class UndistortedVideoRenderer {

    interface PositionProvider {
        int positionMs(int durationMs);
    }

    private static final String TAG = "UndistortedRenderer";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int MESH_COLUMNS = 40;
    private static final int MESH_ROWS = 24;
    private static final long FRAME_WAIT_MS = 250L;

    private final Object frameLock = new Object();
    private final Surface targetSurface;
    private final File videoFile;
    private final int cameraIndex;
    private final PositionProvider positionProvider;
    private final Object playerLock = new Object();

    private Thread renderThread;
    private volatile boolean running;
    private volatile boolean frameAvailable;
    private MediaPlayer mediaPlayer;

    UndistortedVideoRenderer(Surface targetSurface, File videoFile, int cameraIndex, PositionProvider positionProvider) {
        this.targetSurface = targetSurface;
        this.videoFile = videoFile;
        this.cameraIndex = cameraIndex;
        this.positionProvider = positionProvider;
    }

    boolean start() {
        if (targetSurface == null || !targetSurface.isValid() || videoFile == null || !videoFile.isFile()) {
            return false;
        }
        running = true;
        renderThread = new Thread(this::renderLoop, "UndistortedVideoRenderer-" + cameraIndex);
        renderThread.start();
        return true;
    }

    void stop() {
        running = false;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        synchronized (playerLock) {
            MediaPlayer player = mediaPlayer;
            if (player != null) {
                try {
                    player.setOnPreparedListener(null);
                    player.setOnCompletionListener(null);
                    player.setOnErrorListener(null);
                    player.release();
                } catch (Throwable ignored) {
                }
                mediaPlayer = null;
            }
        }
        Thread thread = renderThread;
        renderThread = null;
        if (thread != null) {
            try {
                thread.join(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void renderLoop() {
        EglState egl = null;
        SurfaceTexture inputTexture = null;
        Surface inputSurface = null;
        int program = 0;
        int textureId = 0;
        try {
            egl = EglState.create(targetSurface);
            textureId = createExternalTexture();
            inputTexture = new SurfaceTexture(textureId);
            inputTexture.setOnFrameAvailableListener(surfaceTexture -> {
                synchronized (frameLock) {
                    frameAvailable = true;
                    frameLock.notifyAll();
                }
            });
            inputSurface = new Surface(inputTexture);

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            Mesh mesh = Mesh.create();
            int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            int aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            int uTextureMatrix = GLES20.glGetUniformLocation(program, "uTextureMatrix");
            int uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            float[] textureMatrix = new float[16];

            MediaPlayer player = new MediaPlayer();
            player.setDataSource(videoFile.getAbsolutePath());
            player.setSurface(inputSurface);
            player.setLooping(false);
            player.setOnPreparedListener(mp -> {
                try {
                    int seekMs = positionProvider.positionMs(mp.getDuration());
                    Log.i(TAG, "Starting undistorted camera " + cameraIndex + " from "
                            + videoFile.getName() + " at " + seekMs + "ms");
                    if (seekMs > 0) {
                        mp.seekTo(seekMs);
                    }
                    mp.start();
                } catch (Throwable t) {
                    running = false;
                    synchronized (frameLock) {
                        frameLock.notifyAll();
                    }
                }
            });
            player.setOnCompletionListener(mp -> {
                try {
                    mp.seekTo(0);
                    mp.start();
                } catch (Throwable ignored) {
                    running = false;
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error camera=" + cameraIndex + " what=" + what + " extra=" + extra);
                running = false;
                synchronized (frameLock) {
                    frameLock.notifyAll();
                }
                return true;
            });
            synchronized (playerLock) {
                if (!running) {
                    player.release();
                    return;
                }
                mediaPlayer = player;
            }
            player.prepareAsync();

            long fpsWindowStartMs = SystemClock.elapsedRealtime();
            int fpsFrames = 0;
            while (running && targetSurface.isValid()) {
                waitForFrame();
                if (!running) break;
                inputTexture.updateTexImage();
                inputTexture.getTransformMatrix(textureMatrix);

                GLES20.glViewport(0, 0, egl.width, egl.height);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(program);
                GLES20.glUniformMatrix4fv(uTextureMatrix, 1, false, textureMatrix, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
                GLES20.glUniform1i(uTexture, 0);

                mesh.vertices.position(0);
                GLES20.glEnableVertexAttribArray(aPosition);
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, mesh.vertices);
                mesh.texCoords.position(0);
                GLES20.glEnableVertexAttribArray(aTexCoord);
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, mesh.texCoords);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount);
                GLES20.glDisableVertexAttribArray(aPosition);
                GLES20.glDisableVertexAttribArray(aTexCoord);
                EGL14.eglSwapBuffers(egl.display, egl.surface);

                fpsFrames++;
                long now = SystemClock.elapsedRealtime();
                if (fpsFrames >= 120) {
                    long elapsed = Math.max(1L, now - fpsWindowStartMs);
                    Log.i(TAG, "camera=" + cameraIndex + " fps=" + (fpsFrames * 1000L / elapsed));
                    fpsWindowStartMs = now;
                    fpsFrames = 0;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Renderer failed for camera " + cameraIndex, t);
        } finally {
            synchronized (playerLock) {
                MediaPlayer player = mediaPlayer;
                mediaPlayer = null;
                if (player != null) {
                    try {
                        player.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            }
            if (inputSurface != null) {
                inputSurface.release();
            }
            if (inputTexture != null) {
                inputTexture.release();
            }
            if (egl != null) {
                egl.release();
            }
        }
    }

    private void waitForFrame() {
        synchronized (frameLock) {
            if (!frameAvailable && running) {
                try {
                    frameLock.wait(FRAME_WAIT_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            frameAvailable = false;
        }
    }

    private static int createExternalTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int texture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static int createProgram(String vertexShader, String fragmentShader) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("GL program link failed: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("GL shader compile failed: " + log);
        }
        return shader;
    }

    private static FloatBuffer directFloatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer
                .allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static final class Mesh {
        final FloatBuffer vertices;
        final FloatBuffer texCoords;
        final int vertexCount;

        private Mesh(FloatBuffer vertices, FloatBuffer texCoords, int vertexCount) {
            this.vertices = vertices;
            this.texCoords = texCoords;
            this.vertexCount = vertexCount;
        }

        static Mesh create() {
            int cells = MESH_COLUMNS * MESH_ROWS;
            float[] positions = new float[cells * 6 * 2];
            float[] texCoords = new float[cells * 6 * 2];
            int p = 0;
            int t = 0;
            for (int y = 0; y < MESH_ROWS; y++) {
                float y0 = y / (float) MESH_ROWS;
                float y1 = (y + 1) / (float) MESH_ROWS;
                for (int x = 0; x < MESH_COLUMNS; x++) {
                    float x0 = x / (float) MESH_COLUMNS;
                    float x1 = (x + 1) / (float) MESH_COLUMNS;
                    p = putQuadPositions(positions, p, x0, y0, x1, y1);
                    t = putUndistortTexCoords(texCoords, t, x0, y0, x1, y1);
                }
            }
            return new Mesh(directFloatBuffer(positions), directFloatBuffer(texCoords), positions.length / 2);
        }

        private static int putQuadPositions(float[] out, int index, float x0, float y0, float x1, float y1) {
            index = putPosition(out, index, x0, y0);
            index = putPosition(out, index, x1, y0);
            index = putPosition(out, index, x0, y1);
            index = putPosition(out, index, x1, y0);
            index = putPosition(out, index, x1, y1);
            index = putPosition(out, index, x0, y1);
            return index;
        }

        private static int putUndistortTexCoords(float[] out, int index, float x0, float y0, float x1, float y1) {
            index = putUndistortTexCoord(out, index, x0, y0);
            index = putUndistortTexCoord(out, index, x1, y0);
            index = putUndistortTexCoord(out, index, x0, y1);
            index = putUndistortTexCoord(out, index, x1, y0);
            index = putUndistortTexCoord(out, index, x1, y1);
            index = putUndistortTexCoord(out, index, x0, y1);
            return index;
        }

        private static int putPosition(float[] out, int index, float x, float y) {
            out[index++] = x * 2f - 1f;
            out[index++] = 1f - y * 2f;
            return index;
        }

        private static int putUndistortTexCoord(float[] out, int index, float x, float y) {
            float xn = x * 2f - 1f;
            float yn = y * 2f - 1f;
            float r2 = xn * xn + yn * yn;
            float radial = 1.0f - 0.30f * r2 + 0.08f * r2 * r2;
            out[index++] = clamp01(0.5f + xn * radial * 0.5f);
            // External OES textures use GL coordinates; keep Android top-left screen geometry upright.
            out[index++] = 1f - clamp01(0.5f + yn * radial * 0.5f);
            return index;
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }

    private static final class EglState {
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface surface;
        final int width;
        final int height;

        private EglState(EGLDisplay display, EGLContext context, EGLSurface surface, int width, int height) {
            this.display = display;
            this.context = context;
            this.surface = surface;
            this.width = width;
            this.height = height;
        }

        static EglState create(Surface targetSurface) {
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("eglInitialize failed");
            }
            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) || count[0] <= 0) {
                throw new IllegalStateException("eglChooseConfig failed");
            }
            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            int[] surfaceAttribs = {EGL14.EGL_NONE};
            EGLSurface surface = EGL14.eglCreateWindowSurface(display, configs[0], targetSurface, surfaceAttribs, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreateWindowSurface failed");
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("eglMakeCurrent failed");
            }
            int[] width = new int[1];
            int[] height = new int[1];
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, width, 0);
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, height, 0);
            return new EglState(display, context, surface, Math.max(1, width[0]), Math.max(1, height[0]));
        }

        void release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uTextureMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTexCoord = (uTextureMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";
}
