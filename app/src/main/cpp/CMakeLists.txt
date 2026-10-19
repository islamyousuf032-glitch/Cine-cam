cmake_minimum_required(VERSION 3.22.1)
project("cinecam_raw")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -Wall -Wextra -ffast-math -fexceptions -frtti")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG -flto")

find_library(log-lib log)
find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(camera2ndk-lib camera2ndk)
find_library(jnigraphics-lib jnigraphics)

include_directories(${CMAKE_SOURCE_DIR}/include)

# Try to ensure libs exist, fallback to empty if not found
if(NOT log-lib)
  message(WARNING "log lib not found")
  set(log-lib "")
endif()
if(NOT android-lib)
  message(WARNING "android lib not found")
  set(android-lib "")
endif()

add_library(cinecam_raw SHARED
        src/jni_bridge.cpp
        src/cine_raw_engine.cpp
        src/ring_buffer.cpp
        src/raw_writer.cpp
        src/cinemadng_writer.cpp
        src/memory_pool.cpp
        src/gl_preview_renderer.cpp
        src/dng_writer.cpp
)

target_include_directories(cinecam_raw PRIVATE
        ${CMAKE_SOURCE_DIR}/include
        ${CMAKE_SOURCE_DIR}/src
)

target_link_libraries(cinecam_raw
        ${log-lib}
        ${android-lib}
        ${mediandk-lib}
        ${jnigraphics-lib}
        -lEGL
        -lGLESv3
        -lz
)
# Note: camera2ndk optionally linked if found
if(camera2ndk-lib)
  target_link_libraries(cinecam_raw ${camera2ndk-lib})
endif()
