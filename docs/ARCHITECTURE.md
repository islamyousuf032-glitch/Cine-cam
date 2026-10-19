# CineCam Architecture - MotionCam Inspired

## Overview
Professional RAW Camera App - Android (Kotlin + NDK/C++)

## Modules

### 1. MemoryPool (`app/src/main/cpp/include/memory_pool.h`)
- Pre-allocated 4096-aligned buffers
- Zero-copy design: no malloc in hot path (60fps)
- LIFO free stack with mutex protection

### 2. RingBuffer (`ring_buffer.h`)
- SPSC Lock-Free Circular Queue
- Producer: Camera2 thread (onImageAvailable)
- Consumer: Writer thread
- Policies: DropOldest (preview) / DropNewest (recording)

### 3. RawWriter (`raw_writer.h`)
- High-speed storage pipeline
- Supports: `.mcraw` (custom) and CinemaDNG sequence
- Direct I/O friendly, 64KB chunked writes
- ZSTD compression option (Level 3 default)

### 4. CineRawEngine (`cine_raw_engine.h`)
- Central controller: JNI ↔ C++ pipeline
- Lifecycle: initialize → startRecording → stopRecording → release
- Stats: poolAvailable, ringFillRatio, framesWritten

### 5. GlPreviewRenderer (`gl_preview_renderer.h`)
- OpenGL ES 3.1 debayer + tonemap
- Future: Vulkan external memory path

### 6. Kotlin Layer
- `CameraController.kt`: Camera2 device/session management
- `CameraCapabilities.kt`: RAW_SENSOR checker
- `NativeRawEngine.kt`: JNI bridge
- Compose UI: ManualControls, CameraScreen

## Thread Model
```
Main (UI) → start/stop/configure
Camera2 CB Thread → ImageReader → MemoryPool.acquire() → RingBuffer.push()
Writer Thread → RingBuffer.pop() → RawWriter → Disk
GL Thread → preview texture upload
```

## Build
- AGP 8.5.2, Gradle 8.7, Kotlin 1.9.22, NDK 26.1, C++17, minSdk 26
- ABIs: arm64-v8a, armeabi-v7a, x86_64

## Next Steps
- Phase 2: Live preview + capability UI
- Phase 3: Manual controls + DNG still
- Phase 4: NDK ring buffer + audio sync
- Phase 5: RAW video recording
