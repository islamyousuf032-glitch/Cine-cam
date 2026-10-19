#pragma once
/**
 * CineCam GL Preview Renderer - Zero-lag live preview
 * Uses OpenGL ES 3.1 for debayer + tonemap preview
 * Future: Vulkan path (VK_KHR_android_external_memory)
 */

#include <GLES3/gl3.h>
#include <EGL/egl.h>
#include <string>
#include <mutex>

namespace cinecam {

class GlPreviewRenderer {
public:
    GlPreviewRenderer();
    ~GlPreviewRenderer();

    bool initialize(int32_t previewWidth, int32_t previewHeight);
    void release();

    // Called from GL thread
    void onSurfaceCreated();
    void onSurfaceChanged(int32_t width, int32_t height);
    void onDrawFrame();

    // Update preview frame (called from camera thread, does texture upload)
    bool updateFrame(const uint8_t* yuvData, int32_t width, int32_t height, int32_t stride);

    void setExposure(float ev) { exposure_ = ev; }
    void setWhiteBalance(float tempK) { whiteBalanceK_ = tempK; }

private:
    bool compileShaders();
    void checkGlError(const char* op);

    GLuint program_ = 0;
    GLuint vertexShader_ = 0;
    GLuint fragmentShader_ = 0;
    GLuint textureId_ = 0;
    GLuint vbo_ = 0;
    GLuint vao_ = 0;

    int32_t previewWidth_ = 1920;
    int32_t previewHeight_ = 1080;
    int32_t surfaceWidth_ = 0;
    int32_t surfaceHeight_ = 0;

    float exposure_ = 0.f;
    float whiteBalanceK_ = 5500.f;

    bool initialized_ = false;
    std::mutex mutex_;

    // Vertex data for full-screen quad
    static const float kVertices[];
};

} // namespace cinecam
