#include "gl_preview_renderer.h"
#include <android/log.h>
#include <GLES3/gl3.h>

#define LOG_TAG "CineGLRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace cinecam {

const float GlPreviewRenderer::kVertices[] = {
    // positions   // texCoords
    -1.0f, -1.0f,  0.0f, 0.0f,
     1.0f, -1.0f,  1.0f, 0.0f,
    -1.0f,  1.0f,  0.0f, 1.0f,
     1.0f,  1.0f,  1.0f, 1.0f,
};

static const char* kVertexShaderSrc = R"(
#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = aTex;
}
)";

static const char* kFragmentShaderSrc = R"(
#version 300 es
precision mediump float;
in vec2 vTex;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform float uExposure;
uniform float uWhiteBalance;
void main() {
    vec3 color = texture(uTexture, vTex).rgb;
    // Simple tonemap: exposure + gamma
    color = color * pow(2.0, uExposure);
    // White balance (approx): scale R/B based on temp
    float wb = uWhiteBalance / 5500.0;
    color.r *= mix(1.2, 0.9, wb);
    color.b *= mix(0.8, 1.2, wb);
    // Gamma 2.2
    color = pow(color, vec3(1.0/2.2));
    fragColor = vec4(color, 1.0);
}
)";

GlPreviewRenderer::GlPreviewRenderer() {}
GlPreviewRenderer::~GlPreviewRenderer() { release(); }

bool GlPreviewRenderer::initialize(int32_t previewWidth, int32_t previewHeight) {
    previewWidth_ = previewWidth;
    previewHeight_ = previewHeight;
    LOGI("GlPreviewRenderer init: %dx%d", previewWidth, previewHeight);
    return true; // Shaders compiled on GL thread in onSurfaceCreated
}

void GlPreviewRenderer::release() {
    if (program_) { glDeleteProgram(program_); program_ = 0; }
    if (textureId_) { glDeleteTextures(1, &textureId_); textureId_ = 0; }
    if (vbo_) { glDeleteBuffers(1, &vbo_); vbo_ = 0; }
    if (vao_) { glDeleteVertexArrays(1, &vao_); vao_ = 0; }
    initialized_ = false;
    LOGI("GlPreviewRenderer released");
}

void GlPreviewRenderer::onSurfaceCreated() {
    if (!compileShaders()) {
        LOGE("Failed to compile shaders");
        return;
    }
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kVertices), kVertices, GL_STATIC_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));
    glEnableVertexAttribArray(1);

    glGenTextures(1, &textureId_);
    glBindTexture(GL_TEXTURE_2D, textureId_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    initialized_ = true;
    LOGI("GlPreviewRenderer surface created, program=%u", program_);
}

void GlPreviewRenderer::onSurfaceChanged(int32_t width, int32_t height) {
    surfaceWidth_ = width;
    surfaceHeight_ = height;
    glViewport(0, 0, width, height);
    LOGI("Surface changed: %dx%d", width, height);
}

void GlPreviewRenderer::onDrawFrame() {
    if (!initialized_) return;
    glClearColor(0.05f, 0.05f, 0.05f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(program_);
    glUniform1f(glGetUniformLocation(program_, "uExposure"), exposure_);
    glUniform1f(glGetUniformLocation(program_, "uWhiteBalance"), whiteBalanceK_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId_);
    glUniform1i(glGetUniformLocation(program_, "uTexture"), 0);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

bool GlPreviewRenderer::updateFrame(const uint8_t* yuvData, int32_t width, int32_t height, int32_t stride) {
    if (!initialized_ || !yuvData) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    glBindTexture(GL_TEXTURE_2D, textureId_);
    // Upload as RGB (preview path converts YUV->RGB on CPU or via shader; here simplified)
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, yuvData);
    return true;
}

bool GlPreviewRenderer::compileShaders() {
    auto compile = [](GLenum type, const char* src) -> GLuint {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &src, nullptr);
        glCompileShader(shader);
        GLint ok = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[512];
            glGetShaderInfoLog(shader, 512, nullptr, log);
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Shader compile error: %s", log);
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    };

    vertexShader_ = compile(GL_VERTEX_SHADER, kVertexShaderSrc);
    fragmentShader_ = compile(GL_FRAGMENT_SHADER, kFragmentShaderSrc);
    if (!vertexShader_ || !fragmentShader_) return false;

    program_ = glCreateProgram();
    glAttachShader(program_, vertexShader_);
    glAttachShader(program_, fragmentShader_);
    glLinkProgram(program_);
    GLint ok = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(program_, 512, nullptr, log);
        LOGE("Program link error: %s", log);
        return false;
    }
    glDeleteShader(vertexShader_);
    glDeleteShader(fragmentShader_);
    vertexShader_ = 0;
    fragmentShader_ = 0;
    return true;
}

void GlPreviewRenderer::checkGlError(const char* op) {
    GLint err = glGetError();
    if (err) LOGE("GL error %d after %s", err, op);
}

} // namespace cinecam
