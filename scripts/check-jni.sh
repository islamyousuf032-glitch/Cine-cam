#!/usr/bin/env bash
# Fast, host-only checks for the JNI boundary (no Android SDK needed):
#   1. every Kotlin `external fun` must match its C++ implementation type for type,
#   2. jni_bridge.cpp must compile (syntax only) against a minimal JNI/NDK stub.
# A Kotlin Long read as `jint` silently truncates at runtime; that is exactly what broke v116.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-jni-signatures.py
# No -Werror here: the NDK build owns warning policy. This check exists for type errors.
"${CXX:-g++}" -std=c++17 -fsyntax-only \
  -Itests/native/jni-stub -Iapp/src/main/cpp/include app/src/main/cpp/src/jni_bridge.cpp
printf 'JNI bridge signatures and syntax verified.\n'
